package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.hosted.job.JobArtifact;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQueue;
import io.gen2spring.mcp.application.hosted.job.JobView;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.job.JobTransitionPolicy;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PostgresJobQueue implements JobQueue {
    private static final int MAX_RUNNING_PER_OWNER = 2;
    private static final String JOB_COLUMNS = """
            id, owner_account_id, kind, status, specification_id, attempt, cancel_requested
            """;
    private static final RowMapper<JobView> JOB_VIEW = PostgresJobQueue::mapJobView;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public PostgresJobQueue(DataSource dataSource, Clock clock) {
        DataSource requiredDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(requiredDataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(requiredDataSource));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreateJobResult create(CreateJob command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status -> createInTransaction(command)));
    }

    @Override
    public Optional<JobView> find(AccountId owner, JobId jobId) {
        if (owner == null || jobId == null) {
            return Optional.empty();
        }
        return jdbc.query(
                        "select " + JOB_COLUMNS + " from generation_job where owner_account_id = ? and id = ?",
                        JOB_VIEW,
                        owner.value(),
                        jobId.value())
                .stream()
                .findFirst();
    }

    @Override
    public boolean requestCancellation(AccountId owner, JobId jobId) {
        if (owner == null || jobId == null) {
            return false;
        }
        return Boolean.TRUE.equals(transactions.execute(status -> cancelInTransaction(owner, jobId)));
    }

    @Override
    public Optional<JobLease> claim(WorkerId worker, Instant now, Duration duration) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(duration, "duration");
        return Objects.requireNonNull(transactions.execute(status -> claimInTransaction(worker, now, duration)));
    }

    @Override
    public boolean heartbeat(JobLease lease, Instant leaseUntil) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Instant now = clock.instant();
        return jdbc.update(
                        """
                        update generation_job
                           set lease_until = ?, updated_at = ?, version = version + 1
                         where id = ?
                           and status = 'RUNNING'
                           and lease_owner = ?
                           and fencing_token = ?
                           and lease_until >= ?
                           and ? > lease_until
                        """,
                        timestamp(leaseUntil),
                        timestamp(now),
                        lease.jobId().value(),
                        lease.worker().value(),
                        lease.fencingToken(),
                        timestamp(now),
                        timestamp(leaseUntil))
                == 1;
    }

    @Override
    public boolean cancellationRequested(JobLease lease) {
        Objects.requireNonNull(lease, "lease");
        List<Boolean> requested = jdbc.query(
                """
                select cancel_requested
                  from generation_job
                 where id = ?
                   and status = 'RUNNING'
                   and lease_owner = ?
                   and fencing_token = ?
                """,
                (resultSet, row) -> resultSet.getBoolean("cancel_requested"),
                lease.jobId().value(),
                lease.worker().value(),
                lease.fencingToken());
        return requested.size() == 1 && Boolean.TRUE.equals(requested.getFirst());
    }

    @Override
    public boolean complete(JobLease lease, JobCompletion completion) {
        return complete(lease, completion, List.of());
    }

    @Override
    public boolean complete(JobLease lease, JobCompletion completion, List<JobArtifact> artifacts) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(artifacts, "artifacts");
        List<JobArtifact> immutableArtifacts = List.copyOf(artifacts);
        if (immutableArtifacts.size() > 16
                || (!immutableArtifacts.isEmpty() && completion.status() != JobStatus.SUCCEEDED)) {
            throw new IllegalArgumentException("Hosted job artifact publication is invalid");
        }
        return Boolean.TRUE.equals(transactions.execute(
                status -> completeInTransaction(lease, completion, immutableArtifacts)));
    }

    @Override
    public int recoverExpired(Instant now, int maxAttempts) {
        Objects.requireNonNull(now, "now");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Hosted job recovery is invalid");
        }
        return Objects.requireNonNull(transactions.execute(status -> recoverInTransaction(now, maxAttempts)));
    }

    private CreateJobResult createInTransaction(CreateJob command) {
        jdbc.queryForObject(
                "select id from account where id = ? for update",
                UUID.class,
                command.owner().value());

        List<ExistingJob> existing = jdbc.query(
                """
                select request_hash, %s
                  from generation_job
                 where owner_account_id = ? and operation = ? and idempotency_key = ?
                """.formatted(JOB_COLUMNS),
                (resultSet, row) -> new ExistingJob(resultSet.getString("request_hash"), mapJobView(resultSet, row)),
                command.owner().value(),
                command.operation(),
                command.idempotencyKey());
        if (!existing.isEmpty()) {
            ExistingJob replay = existing.getFirst();
            if (!replay.requestHash().equals(command.requestHash())) {
                throw new CreateRejected(CreateRejection.IDEMPOTENCY_CONFLICT);
            }
            return new CreateJobResult(replay.job(), true);
        }

        Integer queued = jdbc.queryForObject(
                "select count(*) from generation_job where owner_account_id = ? and status = 'QUEUED'",
                Integer.class,
                command.owner().value());
        if (queued == null || queued >= command.quota().maxQueued()) {
            throw new CreateRejected(CreateRejection.CAPACITY_EXCEEDED);
        }

        JobId id = new JobId(UUID.randomUUID());
        Instant now = clock.instant();
        jdbc.update(
                """
                insert into generation_job(
                    id, owner_account_id, specification_id, kind, operation,
                    idempotency_key, request_hash, request_snapshot, status,
                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', ?, ?)
                """,
                id.value(),
                command.owner().value(),
                command.specificationId().map(SpecificationId::value).orElse(null),
                command.kind().name(),
                command.operation(),
                command.idempotencyKey(),
                command.requestHash(),
                command.requestSnapshot(),
                timestamp(now),
                timestamp(now));
        appendEvent(id, null, JobStatus.QUEUED, null, null, null, now);
        return new CreateJobResult(new JobView(
                id,
                command.owner(),
                command.kind(),
                JobStatus.QUEUED,
                command.specificationId(),
                0,
                false), false);
    }

    private boolean cancelInTransaction(AccountId owner, JobId jobId) {
        List<JobStatus> statuses = jdbc.query(
                """
                select status
                  from generation_job
                 where owner_account_id = ? and id = ?
                 for update
                """,
                (resultSet, row) -> JobStatus.valueOf(resultSet.getString("status")),
                owner.value(),
                jobId.value());
        if (statuses.isEmpty() || isTerminal(statuses.getFirst())) {
            return false;
        }
        JobStatus current = statuses.getFirst();
        Instant now = clock.instant();
        if (current == JobStatus.QUEUED) {
            JobTransitionPolicy.requireAllowed(current, JobStatus.CANCELLED);
            jdbc.update(
                    """
                    update generation_job
                       set status = 'CANCELLED', cancel_requested = true,
                           request_snapshot = case
                               when kind = 'SPEC_IMPORT' then '{}'::jsonb
                               else request_snapshot
                           end,
                           safe_error_code = 'CANCELLED', safe_error_summary = 'The hosted job was cancelled',
                           updated_at = ?, version = version + 1
                     where owner_account_id = ? and id = ? and status = 'QUEUED'
                    """,
                    timestamp(now),
                    owner.value(),
                    jobId.value());
            appendEvent(
                    jobId,
                    JobStatus.QUEUED,
                    JobStatus.CANCELLED,
                    null,
                    "CANCELLED",
                    "The hosted job was cancelled",
                    now);
            return true;
        }
        return jdbc.update(
                        """
                        update generation_job
                           set cancel_requested = true, updated_at = ?, version = version + 1
                         where owner_account_id = ? and id = ? and status = 'RUNNING'
                        """,
                        timestamp(now),
                        owner.value(),
                        jobId.value())
                == 1;
    }

    private Optional<JobLease> claimInTransaction(WorkerId worker, Instant now, Duration duration) {
        List<ClaimCandidate> candidates = jdbc.query(
                """
                select j.id, j.owner_account_id, j.kind, j.request_snapshot::text as request_snapshot
                  from generation_job j
                 where j.status = 'QUEUED' and j.cancel_requested = false
                   and (
                       select count(*)
                         from generation_job running
                        where running.owner_account_id = j.owner_account_id
                          and running.status = 'RUNNING'
                   ) < ?
                 order by created_at, id
                 for update of j skip locked
                 limit 1
                """,
                (resultSet, row) -> new ClaimCandidate(
                        new JobId(resultSet.getObject("id", UUID.class)),
                        new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                        JobKind.valueOf(resultSet.getString("kind")),
                        resultSet.getString("request_snapshot")),
                MAX_RUNNING_PER_OWNER);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ClaimCandidate candidate = candidates.getFirst();
        jdbc.queryForObject(
                "select id from account where id = ? for update",
                UUID.class,
                candidate.owner().value());
        Integer running = jdbc.queryForObject(
                "select count(*) from generation_job where owner_account_id = ? and status = 'RUNNING'",
                Integer.class,
                candidate.owner().value());
        if (running == null || running >= MAX_RUNNING_PER_OWNER) {
            return Optional.empty();
        }
        JobTransitionPolicy.requireAllowed(JobStatus.QUEUED, JobStatus.RUNNING);
        Instant leaseUntil = now.plus(duration);
        Long token = jdbc.queryForObject(
                """
                update generation_job
                   set status = 'RUNNING', attempt = attempt + 1,
                       lease_owner = ?, lease_until = ?, fencing_token = fencing_token + 1,
                       updated_at = ?, version = version + 1
                 where id = ? and status = 'QUEUED'
                returning fencing_token
                """,
                Long.class,
                worker.value(),
                timestamp(leaseUntil),
                timestamp(now),
                candidate.id().value());
        appendEvent(candidate.id(), JobStatus.QUEUED, JobStatus.RUNNING, null, null, null, now);
        return Optional.of(new JobLease(
                candidate.id(),
                worker,
                Objects.requireNonNull(token),
                leaseUntil,
                candidate.kind(),
                candidate.requestSnapshot()));
    }

    private boolean completeInTransaction(
            JobLease lease,
            JobCompletion completion,
            List<JobArtifact> artifacts) {
        JobTransitionPolicy.requireAllowed(JobStatus.RUNNING, completion.status());
        Instant now = clock.instant();
        Optional<CompletionTarget> candidate = jdbc.query(
                        """
                        select owner_account_id, kind, cancel_requested
                          from generation_job
                         where id = ? and status = 'RUNNING'
                           and lease_owner = ? and fencing_token = ? and lease_until >= ?
                         for update
                        """,
                        (resultSet, row) -> new CompletionTarget(
                                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                                JobKind.valueOf(resultSet.getString("kind")),
                                resultSet.getBoolean("cancel_requested")),
                        lease.jobId().value(),
                        lease.worker().value(),
                        lease.fencingToken(),
                        timestamp(now))
                .stream()
                .findFirst();
        if (candidate.isEmpty()) {
            return false;
        }
        CompletionTarget target = candidate.get();
        if (target.cancellationRequested() && completion.status() != JobStatus.CANCELLED) {
            return false;
        }
        for (JobArtifact artifact : artifacts) {
            if (!artifact.expiresAt().isAfter(now)) {
                throw new IllegalArgumentException("Hosted job artifact publication is invalid");
            }
            int inserted = jdbc.update(
                    """
                    insert into artifact(
                        id, job_id, owner_account_id, type, object_key, sha256,
                        byte_size, content_type, created_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    lease.jobId().value(),
                    target.owner().value(),
                    artifact.type(),
                    artifact.objectKey().value(),
                    artifact.sha256(),
                    artifact.byteSize(),
                    artifact.contentType(),
                    timestamp(now),
                    timestamp(artifact.expiresAt()));
            if (inserted != 1) {
                throw new IllegalStateException("Hosted job artifact publication failed");
            }
        }
        if (target.kind() == JobKind.SPEC_IMPORT && completion.status() == JobStatus.SUCCEEDED) {
            publishImportedSpecification(lease, target.owner(), artifacts, now);
        }
        int updated = jdbc.update(
                """
                update generation_job
                   set status = ?, lease_owner = null, lease_until = null,
                       specification_id = case
                           when kind = 'SPEC_IMPORT' and ? = 'SUCCEEDED' then id
                           else specification_id
                       end,
                       request_snapshot = case
                           when kind = 'SPEC_IMPORT' then '{}'::jsonb
                           else request_snapshot
                       end,
                       safe_error_code = ?, safe_error_summary = ?,
                       updated_at = ?, version = version + 1
                 where id = ? and status = 'RUNNING'
                   and lease_owner = ? and fencing_token = ? and lease_until >= ?
                """,
                completion.status().name(),
                completion.status().name(),
                completion.safeCode(),
                completion.safeSummary(),
                timestamp(now),
                lease.jobId().value(),
                lease.worker().value(),
                lease.fencingToken(),
                timestamp(now));
        if (updated != 1) {
            throw new IllegalStateException("Hosted job artifact publication failed");
        }
        appendEvent(
                lease.jobId(),
                JobStatus.RUNNING,
                completion.status(),
                null,
                completion.safeCode(),
                completion.safeSummary(),
                now);
        return true;
    }

    private void publishImportedSpecification(
            JobLease lease,
            AccountId owner,
            List<JobArtifact> artifacts,
            Instant now) {
        String expectedPrefix = "specifications/" + lease.jobId().value() + "/" + lease.fencingToken() + "-";
        if (artifacts.size() != 1
                || !"SOURCE".equals(artifacts.getFirst().type())
                || !artifacts.getFirst().objectKey().value().startsWith(expectedPrefix)) {
            throw new IllegalArgumentException("Hosted job artifact publication is invalid");
        }
        JobArtifact source = artifacts.getFirst();
        int inserted = jdbc.update(
                """
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, 'URL', ?, ?, ?, 'Imported OpenAPI', 'READY', ?, ?)
                """,
                lease.jobId().value(),
                owner.value(),
                source.objectKey().value(),
                source.sha256(),
                source.byteSize(),
                timestamp(now),
                timestamp(now));
        if (inserted != 1) {
            throw new IllegalStateException("Hosted job artifact publication failed");
        }
    }

    private int recoverInTransaction(Instant now, int maxAttempts) {
        List<ExpiredJob> expired = jdbc.query(
                """
                select id, attempt, cancel_requested
                  from generation_job
                 where status = 'RUNNING' and lease_until < ?
                 order by lease_until, id
                 for update skip locked
                """,
                (resultSet, row) -> new ExpiredJob(
                        new JobId(resultSet.getObject("id", UUID.class)),
                        resultSet.getInt("attempt"),
                        resultSet.getBoolean("cancel_requested")),
                timestamp(now));
        for (ExpiredJob job : expired) {
            boolean retry = !job.cancellationRequested() && job.attempt() < maxAttempts;
            JobStatus target = job.cancellationRequested()
                    ? JobStatus.CANCELLED
                    : retry ? JobStatus.QUEUED : JobStatus.FAILED;
            JobTransitionPolicy.requireAllowed(JobStatus.RUNNING, target);
            String code = retry ? null : target == JobStatus.CANCELLED ? "CANCELLED" : "LEASE_EXHAUSTED";
            String summary = retry
                    ? null
                    : target == JobStatus.CANCELLED
                            ? "The hosted job was cancelled"
                            : "The hosted job retry limit was exhausted";
            jdbc.update(
                    """
                    update generation_job
                       set status = ?, lease_owner = null, lease_until = null,
                           request_snapshot = case
                               when kind = 'SPEC_IMPORT' and ? then '{}'::jsonb
                               else request_snapshot
                           end,
                           safe_error_code = ?, safe_error_summary = ?,
                           updated_at = ?, version = version + 1
                     where id = ? and status = 'RUNNING'
                    """,
                    target.name(),
                    !retry,
                    code,
                    summary,
                    timestamp(now),
                    job.id().value());
            appendEvent(job.id(), JobStatus.RUNNING, target, null, code, summary, now);
        }
        return expired.size();
    }

    private void appendEvent(
            JobId jobId,
            JobStatus from,
            JobStatus to,
            String stage,
            String code,
            String summary,
            Instant createdAt) {
        jdbc.update(
                """
                insert into generation_job_event(
                    job_id, sequence, from_status, to_status, stage, safe_code, safe_summary, created_at)
                select ?, coalesce(max(sequence), 0) + 1, ?, ?, ?, ?, ?, ?
                  from generation_job_event
                 where job_id = ?
                """,
                jobId.value(),
                from == null ? null : from.name(),
                to.name(),
                stage,
                code,
                summary,
                timestamp(createdAt),
                jobId.value());
    }

    private static JobView mapJobView(ResultSet resultSet, int row) throws SQLException {
        UUID specification = resultSet.getObject("specification_id", UUID.class);
        return new JobView(
                new JobId(resultSet.getObject("id", UUID.class)),
                new AccountId(resultSet.getObject("owner_account_id", UUID.class)),
                JobKind.valueOf(resultSet.getString("kind")),
                JobStatus.valueOf(resultSet.getString("status")),
                Optional.ofNullable(specification).map(SpecificationId::new),
                resultSet.getInt("attempt"),
                resultSet.getBoolean("cancel_requested"));
    }

    private static boolean isTerminal(JobStatus status) {
        return status == JobStatus.SUCCEEDED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record ExistingJob(String requestHash, JobView job) {}

    private record CompletionTarget(AccountId owner, JobKind kind, boolean cancellationRequested) {}

    private record ClaimCandidate(JobId id, AccountId owner, JobKind kind, String requestSnapshot) {}

    private record ExpiredJob(JobId id, int attempt, boolean cancellationRequested) {}
}

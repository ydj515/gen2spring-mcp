package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresHostedResourceStore implements HostedResourceStore {
    private final JdbcTemplate jdbc;

    public PostgresHostedResourceStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<SpecificationView> specifications(AccountId owner, int limit) {
        return specifications(owner, limit, Optional.empty());
    }

    @Override
    public List<SpecificationView> specifications(AccountId owner, int limit, Optional<ResourceCursor> cursor) {
        require(owner, limit);
        Objects.requireNonNull(cursor, "cursor");
        if (cursor.isPresent()) {
            ResourceCursor value = cursor.get();
            return List.copyOf(jdbc.query(
                    """
                    select id, source_type, object_key, sha256, byte_size, display_label, created_at
                      from specification
                     where owner_account_id = ? and parse_state = 'READY'
                       and (created_at, id) < (?, ?)
                     order by created_at desc, id desc
                     limit ?
                    """,
                    PostgresHostedResourceStore::specification,
                    owner.value(), java.sql.Timestamp.from(value.createdAt()), value.id(), limit));
        }
        return List.copyOf(jdbc.query(
                """
                select id, source_type, object_key, sha256, byte_size, display_label, created_at
                  from specification
                 where owner_account_id = ? and parse_state = 'READY'
                 order by created_at desc, id desc
                 limit ?
                """,
                PostgresHostedResourceStore::specification,
                owner.value(), limit));
    }

    @Override
    public Optional<SpecificationView> specification(AccountId owner, SpecificationId id) {
        if (owner == null || id == null) return Optional.empty();
        return jdbc.query(
                """
                select id, source_type, object_key, sha256, byte_size, display_label, created_at
                  from specification where owner_account_id = ? and id = ? and parse_state = 'READY'
                """,
                PostgresHostedResourceStore::specification,
                owner.value(), id.value()).stream().findFirst();
    }

    @Override
    public List<JobDetails> jobs(AccountId owner, int limit) {
        return jobs(owner, limit, Optional.empty());
    }

    @Override
    public List<JobDetails> jobs(AccountId owner, int limit, Optional<ResourceCursor> cursor) {
        require(owner, limit);
        Objects.requireNonNull(cursor, "cursor");
        if (cursor.isPresent()) {
            ResourceCursor value = cursor.get();
            return List.copyOf(jdbc.query(
                    """
                    select id, kind, status, specification_id, attempt, cancel_requested,
                           safe_error_code, safe_error_summary, created_at, updated_at
                      from generation_job
                     where owner_account_id = ? and (created_at, id) < (?, ?)
                     order by created_at desc, id desc limit ?
                    """,
                    PostgresHostedResourceStore::job,
                    owner.value(), java.sql.Timestamp.from(value.createdAt()), value.id(), limit));
        }
        return List.copyOf(jdbc.query(
                """
                select id, kind, status, specification_id, attempt, cancel_requested,
                       safe_error_code, safe_error_summary, created_at, updated_at
                  from generation_job where owner_account_id = ?
                 order by created_at desc, id desc limit ?
                """,
                PostgresHostedResourceStore::job,
                owner.value(), limit));
    }

    @Override
    public Optional<JobDetails> job(AccountId owner, JobId id) {
        if (owner == null || id == null) return Optional.empty();
        return jdbc.query(
                """
                select id, kind, status, specification_id, attempt, cancel_requested,
                       safe_error_code, safe_error_summary, created_at, updated_at
                  from generation_job where owner_account_id = ? and id = ?
                """,
                PostgresHostedResourceStore::job,
                owner.value(), id.value()).stream().findFirst();
    }

    @Override
    public List<JobEvent> events(AccountId owner, JobId id, int limit) {
        require(owner, limit);
        if (id == null) return List.of();
        return List.copyOf(jdbc.query(
                """
                select e.sequence, e.to_status, e.stage, e.safe_code, e.safe_summary, e.created_at
                  from generation_job_event e
                  join generation_job j on j.id = e.job_id
                 where j.owner_account_id = ? and j.id = ?
                 order by e.sequence limit ?
                """,
                (rs, row) -> new JobEvent(
                        rs.getLong("sequence"),
                        JobStatus.valueOf(rs.getString("to_status")),
                        rs.getString("stage"), rs.getString("safe_code"), rs.getString("safe_summary"),
                        rs.getTimestamp("created_at").toInstant()),
                owner.value(), id.value(), limit));
    }

    @Override
    public List<ArtifactView> artifacts(AccountId owner, JobId id) {
        if (owner == null || id == null) return List.of();
        return List.copyOf(jdbc.query(
                """
                select id, job_id, type, object_key, sha256, byte_size, content_type, created_at, expires_at
                  from artifact where owner_account_id = ? and job_id = ? and expires_at > now()
                 order by created_at, id
                """,
                PostgresHostedResourceStore::artifact,
                owner.value(), id.value()));
    }

    @Override
    public Optional<ArtifactView> artifact(AccountId owner, UUID artifactId) {
        if (owner == null || artifactId == null) return Optional.empty();
        return jdbc.query(
                """
                select id, job_id, type, object_key, sha256, byte_size, content_type, created_at, expires_at
                  from artifact where owner_account_id = ? and id = ? and expires_at > now()
                """,
                PostgresHostedResourceStore::artifact,
                owner.value(), artifactId).stream().findFirst();
    }

    private static SpecificationView specification(ResultSet rs, int row) throws SQLException {
        return new SpecificationView(
                new SpecificationId(rs.getObject("id", UUID.class)),
                rs.getString("source_type"), ObjectKey.parse(rs.getString("object_key")),
                rs.getString("sha256"), rs.getLong("byte_size"), rs.getString("display_label"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static JobDetails job(ResultSet rs, int row) throws SQLException {
        UUID specification = rs.getObject("specification_id", UUID.class);
        return new JobDetails(
                new JobId(rs.getObject("id", UUID.class)),
                JobKind.valueOf(rs.getString("kind")), JobStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(specification).map(SpecificationId::new), rs.getInt("attempt"),
                rs.getBoolean("cancel_requested"), rs.getString("safe_error_code"),
                rs.getString("safe_error_summary"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static ArtifactView artifact(ResultSet rs, int row) throws SQLException {
        return new ArtifactView(
                rs.getObject("id", UUID.class), new JobId(rs.getObject("job_id", UUID.class)),
                rs.getString("type"), ObjectKey.parse(rs.getString("object_key")), rs.getString("sha256"),
                rs.getLong("byte_size"), rs.getString("content_type"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
    }

    private static void require(AccountId owner, int limit) {
        if (owner == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Hosted resource query is invalid");
        }
    }
}

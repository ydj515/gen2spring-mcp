package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.CreateJob;
import io.gen2spring.mcp.application.hosted.job.JobCompletion;
import io.gen2spring.mcp.application.hosted.job.JobLease;
import io.gen2spring.mcp.application.hosted.job.JobQuota;
import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Timeout(20)
class PostgresLeaseConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-08-13T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final JobQuota QUOTA = new JobQuota(2, 10);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private AccountId owner;

    @BeforeEach
    void resetDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        owner = new PostgresAccountStore(dataSource).findOrCreate(
                "https://issuer.example", "subject-1", NOW);
    }

    @Test
    void allowsExactlyOneConcurrentClaimForOneQueuedJob() throws Exception {
        PostgresJobQueue jobs = queueAt(NOW);
        jobs.create(importJob("job-1", hash(1)));

        List<Optional<JobLease>> claims = claimConcurrently(8, NOW);

        assertEquals(1, claims.stream().filter(Optional::isPresent).count());
        assertEquals(1, count("select count(*) from generation_job where status = 'RUNNING'"));
        assertEquals(2, count("select count(*) from generation_job_event"));
    }

    @Test
    void capsConcurrentRunningJobsPerAccountAtTwo() throws Exception {
        PostgresJobQueue jobs = queueAt(NOW);
        jobs.create(importJob("job-1", hash(1)));
        jobs.create(importJob("job-2", hash(2)));
        jobs.create(importJob("job-3", hash(3)));

        List<Optional<JobLease>> claims = claimConcurrently(3, NOW);

        assertEquals(2, claims.stream().filter(Optional::isPresent).count());
        assertEquals(2, count("select count(*) from generation_job where status = 'RUNNING'"));
        assertEquals(1, count("select count(*) from generation_job where status = 'QUEUED'"));
    }

    @Test
    void increasesFencingTokensAndRejectsStaleOrDuplicateWrites() {
        PostgresJobQueue jobs = queueAt(NOW);
        jobs.create(importJob("job-1", hash(1)));
        JobLease first = jobs.claim(new WorkerId("worker-1"), NOW, LEASE).orElseThrow();

        assertFalse(jobs.heartbeat(first, NOW.plusSeconds(20)));
        assertTrue(jobs.heartbeat(first, NOW.plusSeconds(60)));
        assertEquals(1, jobs.recoverExpired(NOW.plusSeconds(61), 2));

        PostgresJobQueue later = queueAt(NOW.plusSeconds(61));
        JobLease second = later.claim(
                new WorkerId("worker-2"), NOW.plusSeconds(61), LEASE).orElseThrow();

        assertTrue(second.fencingToken() > first.fencingToken());
        assertFalse(later.complete(first, JobCompletion.success()));
        assertTrue(later.complete(second, JobCompletion.success()));
        assertFalse(later.complete(second, JobCompletion.success()));
        assertEquals(JobStatus.SUCCEEDED, later.find(owner, second.jobId()).orElseThrow().status());
        assertEquals(5, eventCount(second));
    }

    @Test
    void removesEncryptedImportPayloadOnlyAfterTerminalCompletion() {
        PostgresJobQueue jobs = queueAt(NOW);
        jobs.create(importJob("job-1", hash(1)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, LEASE).orElseThrow();

        assertTrue(jdbc.queryForObject(
                "select request_snapshot::text <> '{}' from generation_job where id = ?",
                Boolean.class,
                lease.jobId().value()));
        assertTrue(jobs.complete(lease, JobCompletion.success()));
        assertEquals("{}", jdbc.queryForObject(
                "select request_snapshot::text from generation_job where id = ?",
                String.class,
                lease.jobId().value()));
        assertFalse(jobs.cancellationRequested(lease));
    }

    @Test
    void exposesCancellationOnlyToTheCurrentFencedLease() {
        PostgresJobQueue jobs = queueAt(NOW);
        var created = jobs.create(importJob("job-1", hash(1)));
        JobLease lease = jobs.claim(new WorkerId("worker-1"), NOW, LEASE).orElseThrow();

        assertFalse(jobs.cancellationRequested(lease));
        assertTrue(jobs.requestCancellation(owner, created.job().id()));
        assertTrue(jobs.cancellationRequested(lease));
    }

    @Test
    void failsTheJobOnceTheLeaseRetryLimitIsExhausted() {
        PostgresJobQueue firstQueue = queueAt(NOW);
        firstQueue.create(importJob("job-1", hash(1)));
        JobLease first = firstQueue.claim(new WorkerId("worker-1"), NOW, LEASE).orElseThrow();
        assertEquals(1, firstQueue.recoverExpired(NOW.plusSeconds(31), 2));

        PostgresJobQueue secondQueue = queueAt(NOW.plusSeconds(31));
        JobLease second = secondQueue.claim(
                new WorkerId("worker-2"), NOW.plusSeconds(31), LEASE).orElseThrow();
        assertEquals(1, secondQueue.recoverExpired(NOW.plusSeconds(62), 2));

        assertEquals(JobStatus.FAILED, secondQueue.find(owner, second.jobId()).orElseThrow().status());
        assertFalse(secondQueue.complete(first, JobCompletion.success()));
        assertFalse(secondQueue.complete(second, JobCompletion.success()));
        assertEquals(0, secondQueue.recoverExpired(NOW.plusSeconds(90), 2));
        assertEquals(5, eventCount(second));
        assertEquals("LEASE_EXHAUSTED", jdbc.queryForObject(
                "select safe_error_code from generation_job where id = ?",
                String.class,
                second.jobId().value()));
    }

    private List<Optional<JobLease>> claimConcurrently(int workers, Instant now) throws Exception {
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<Optional<JobLease>>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                WorkerId worker = new WorkerId("worker-" + index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return queueAt(now).claim(worker, now, LEASE);
                }));
            }
            ready.await();
            start.countDown();
            List<Optional<JobLease>> claims = new ArrayList<>();
            for (Future<Optional<JobLease>> future : futures) {
                claims.add(future.get());
            }
            return List.copyOf(claims);
        }
    }

    private PostgresJobQueue queueAt(Instant instant) {
        return new PostgresJobQueue(dataSource, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private CreateJob importJob(String key, String hash) {
        return new CreateJob(
                owner,
                JobKind.SPEC_IMPORT,
                "specification-import",
                key,
                hash,
                "{\"target\":\"encrypted\"}",
                Optional.empty(),
                QUOTA);
    }

    private int eventCount(JobLease lease) {
        return jdbc.queryForObject(
                "select count(*) from generation_job_event where job_id = ?",
                Integer.class,
                lease.jobId().value());
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String hash(int suffix) {
        return "a".repeat(63) + Integer.toHexString(suffix);
    }
}

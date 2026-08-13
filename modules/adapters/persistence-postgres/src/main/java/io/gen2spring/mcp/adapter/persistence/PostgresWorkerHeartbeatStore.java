package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.worker.WorkerHeartbeatStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresWorkerHeartbeatStore implements WorkerHeartbeatStore {
    private final JdbcTemplate jdbc;

    public PostgresWorkerHeartbeatStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void beat(WorkerId worker, Instant observedAt) {
        Objects.requireNonNull(worker, "worker");
        Objects.requireNonNull(observedAt, "observedAt");
        jdbc.update("""
                insert into worker_heartbeat(worker_id, observed_at)
                values (?, ?)
                on conflict (worker_id) do update set observed_at = excluded.observed_at
                where worker_heartbeat.observed_at < excluded.observed_at
                """, worker.value(), Timestamp.from(observedAt));
    }

    @Override
    public boolean hasRecentHeartbeat(Instant notBefore) {
        Objects.requireNonNull(notBefore, "notBefore");
        Integer count = jdbc.queryForObject(
                "select count(*) from worker_heartbeat where observed_at >= ?",
                Integer.class, Timestamp.from(notBefore));
        return count != null && count > 0;
    }
}

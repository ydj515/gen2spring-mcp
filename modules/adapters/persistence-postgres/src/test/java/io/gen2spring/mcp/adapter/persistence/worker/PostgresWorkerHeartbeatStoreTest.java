package io.gen2spring.mcp.adapter.persistence.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.job.WorkerId;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresWorkerHeartbeatStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private PostgresWorkerHeartbeatStore store;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        store = new PostgresWorkerHeartbeatStore(dataSource);
    }

    @Test
    void reportsOnlyRecentMonotonicWorkerHeartbeats() {
        Instant first = Instant.parse("2026-08-13T00:00:00Z");
        Instant second = first.plusSeconds(30);
        store.beat(new WorkerId("worker-1"), second);
        store.beat(new WorkerId("worker-1"), first);

        assertTrue(store.hasRecentHeartbeat(second));
        assertFalse(store.hasRecentHeartbeat(second.plusSeconds(1)));
    }
}

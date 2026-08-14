package io.gen2spring.mcp.adapter.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresSpecificationCatalogTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.9-alpine");

    private JdbcTemplate jdbc;
    private PostgresSpecificationCatalog catalog;
    private AccountId owner;

    @BeforeEach
    void resetDatabase() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        owner = new PostgresAccountStore(dataSource).findOrCreate(
                "https://issuer.example", "catalog-owner", Instant.EPOCH);
        catalog = new PostgresSpecificationCatalog(dataSource);
    }

    @Test
    void persistsAndMatchesTheBoundedDisplayLabelOnReplay() {
        SpecificationCatalog.Registration registration = registration("swagger-3.1.yml");

        assertEquals(SpecificationCatalog.RegistrationResult.CREATED, catalog.register(registration));
        assertEquals(SpecificationCatalog.RegistrationResult.REPLAYED, catalog.register(registration));
        assertEquals("swagger-3.1.yml", jdbc.queryForObject(
                "select display_label from specification where id = ?",
                String.class,
                registration.id().value()));
    }

    @Test
    void rejectsInvalidLabelsWithOneFixedNonLeakingMessage() {
        for (String label : List.of("", " ", "../private-marker.yml", "private\\marker.yml", "x".repeat(161))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> registration(label));

            assertEquals("Specification registration is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains("private-marker"));
        }
    }

    private SpecificationCatalog.Registration registration(String label) {
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        return new SpecificationCatalog.Registration(
                id,
                owner,
                ObjectKey.parse("specifications/" + id.value() + "/" + "a".repeat(64)),
                "a".repeat(64),
                10,
                "UPLOAD",
                label,
                "READY",
                Instant.EPOCH);
    }
}

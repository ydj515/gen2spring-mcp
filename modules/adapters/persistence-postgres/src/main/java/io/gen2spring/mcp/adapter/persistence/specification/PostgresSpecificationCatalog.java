package io.gen2spring.mcp.adapter.persistence.specification;

import io.gen2spring.mcp.application.hosted.specification.port.out.SpecificationCatalog;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresSpecificationCatalog implements SpecificationCatalog {
    private final JdbcTemplate jdbc;

    public PostgresSpecificationCatalog(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public boolean belongsTo(AccountId owner, SpecificationId specificationId) {
        if (owner == null || specificationId == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject(
                """
                select exists(
                    select 1
                      from specification
                     where owner_account_id = ?
                       and id = ?
                )
                """,
                Boolean.class,
                owner.value(),
                specificationId.value());
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public RegistrationResult register(Registration registration) {
        Objects.requireNonNull(registration, "registration");
        int inserted = jdbc.update(
                """
                insert into specification(
                    id, owner_account_id, source_type, object_key, sha256, byte_size,
                    display_label, parse_state, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (id) do nothing
                """,
                registration.id().value(),
                registration.owner().value(),
                registration.sourceType(),
                registration.objectKey().value(),
                registration.sha256(),
                registration.byteSize(),
                registration.displayLabel(),
                registration.parseState(),
                Timestamp.from(registration.observedAt()),
                Timestamp.from(registration.observedAt()));
        List<Boolean> matches = jdbc.query(
                """
                select owner_account_id = ?
                       and source_type = ?
                       and object_key = ?
                       and sha256 = ?
                       and byte_size = ?
                       and display_label = ?
                       and parse_state = ? as matches
                  from specification
                 where id = ?
                """,
                (resultSet, row) -> resultSet.getBoolean("matches"),
                registration.owner().value(),
                registration.sourceType(),
                registration.objectKey().value(),
                registration.sha256(),
                registration.byteSize(),
                registration.displayLabel(),
                registration.parseState(),
                registration.id().value());
        if (matches.size() != 1 || !matches.getFirst()) {
            throw new IllegalStateException("Specification registration failed");
        }
        return inserted == 1 ? RegistrationResult.CREATED : RegistrationResult.REPLAYED;
    }
}

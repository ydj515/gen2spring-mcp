package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.specification.SpecificationCatalog;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
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
}

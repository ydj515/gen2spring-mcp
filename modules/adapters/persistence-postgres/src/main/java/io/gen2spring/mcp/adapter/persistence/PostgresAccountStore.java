package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.account.AccountStore;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresAccountStore implements AccountStore {
    private static final String INVALID = "External account identity is invalid";

    private final JdbcTemplate jdbc;

    public PostgresAccountStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public AccountId findOrCreate(String issuer, String subject, Instant observedAt) {
        requireIdentityPart(issuer, 2048);
        requireIdentityPart(subject, 512);
        if (observedAt == null) {
            throw new IllegalArgumentException(INVALID);
        }
        UUID id = jdbc.queryForObject(
                """
                insert into account(id, issuer, subject, created_at, last_seen_at)
                values (?, ?, ?, ?, ?)
                on conflict (issuer, subject) do update
                    set last_seen_at = greatest(account.last_seen_at, excluded.last_seen_at)
                returning id
                """,
                UUID.class,
                UUID.randomUUID(),
                issuer,
                subject,
                Timestamp.from(observedAt),
                Timestamp.from(observedAt));
        return new AccountId(id);
    }

    private void requireIdentityPart(String value, int maxLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}

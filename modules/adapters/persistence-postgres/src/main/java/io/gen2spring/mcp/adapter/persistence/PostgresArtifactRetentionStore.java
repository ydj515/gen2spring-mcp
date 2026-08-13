package io.gen2spring.mcp.adapter.persistence;

import io.gen2spring.mcp.application.hosted.storage.ArtifactRetentionStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresArtifactRetentionStore implements ArtifactRetentionStore {
    private final JdbcTemplate jdbc;

    public PostgresArtifactRetentionStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<ExpiredArtifact> findExpired(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Artifact retention request is invalid");
        }
        return List.copyOf(jdbc.query(
                """
                select a.id, a.object_key,
                       exists(select 1 from specification s where s.object_key = a.object_key) as referenced
                  from artifact a
                 where a.expires_at <= ?
                 order by a.expires_at, a.id
                 limit ?
                """,
                (resultSet, row) -> new ExpiredArtifact(
                        resultSet.getObject("id", UUID.class),
                        ObjectKey.parse(resultSet.getString("object_key")),
                        resultSet.getBoolean("referenced")),
                Timestamp.from(now),
                limit));
    }

    @Override
    public boolean deleteExpired(UUID id, ObjectKey objectKey, Instant now, boolean objectDeleted) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(now, "now");
        if (objectDeleted) {
            return jdbc.update(
                    "delete from artifact where id = ? and object_key = ? and expires_at <= ?",
                    id, objectKey.value(), Timestamp.from(now)) == 1;
        }
        return jdbc.update(
                """
                delete from artifact a
                 where a.id = ? and a.object_key = ? and a.expires_at <= ?
                   and exists(select 1 from specification s where s.object_key = a.object_key)
                """,
                id, objectKey.value(), Timestamp.from(now)) == 1;
    }
}

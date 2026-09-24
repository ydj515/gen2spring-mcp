package io.gen2spring.mcp.adapter.persistence.catalog;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogVersion;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class PostgresToolCatalogStore implements ToolCatalogStore {
    private static final String SAFE_FAILURE = "Tool Catalog storage read failed";
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final String COLUMNS = """
            c.id, c.generation_job_id, c.metadata_version, c.specification_checksum,
            c.metadata_checksum, c.metadata_document, c.tool_count, c.created_at,
            c.family_id, c.revision, c.predecessor_catalog_id
            """;
    private final JdbcTemplate jdbc;
    private final CanonicalRuntimeMetadataCodec codec = new CanonicalRuntimeMetadataCodec();

    public PostgresToolCatalogStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public List<CatalogSummary> list(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor) {
        requireList(owner, fetchLimit, cursor);
        try {
            if (cursor.isPresent()) {
                CatalogCursor value = cursor.get();
                return List.copyOf(jdbc.query(
                        """
                        select c.id, c.generation_job_id, c.metadata_version,
                               c.metadata_checksum, c.tool_count, c.created_at,
                               c.family_id, c.revision, c.predecessor_catalog_id
                          from tool_catalog c
                         where c.owner_account_id = ?
                           and (c.created_at, c.id) < (?, ?)
                         order by c.created_at desc, c.id desc
                         limit ?
                        """,
                        (resultSet, row) -> summary(resultSet),
                        owner.value(), Timestamp.from(value.createdAt()), value.id(), fetchLimit));
            }
            return List.copyOf(jdbc.query(
                    """
                    select c.id, c.generation_job_id, c.metadata_version,
                           c.metadata_checksum, c.tool_count, c.created_at,
                           c.family_id, c.revision, c.predecessor_catalog_id
                      from tool_catalog c
                     where c.owner_account_id = ?
                     order by c.created_at desc, c.id desc
                     limit ?
                    """,
                    (resultSet, row) -> summary(resultSet),
                    owner.value(), fetchLimit));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failed();
        }
    }

    @Override
    public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
        requireIdentity(owner, catalogId);
        try {
            return jdbc.query(
                            "select " + COLUMNS + " from tool_catalog c where c.owner_account_id = ? and c.id = ?",
                            (resultSet, row) -> details(resultSet),
                            owner.value(), catalogId)
                    .stream()
                    .findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failed();
        }
    }

    @Override
    public Optional<ToolDetails> findTool(AccountId owner, UUID catalogId, String toolName) {
        requireIdentity(owner, catalogId);
        if (toolName == null || !TOOL_NAME.matcher(toolName).matches()) {
            throw invalid();
        }
        try {
            return jdbc.query(
                            """
                            select %s, e.metadata_document as tool_document
                              from tool_catalog c
                              join tool_catalog_entry e on e.catalog_id = c.id
                             where c.owner_account_id = ? and c.id = ? and e.tool_name = ?
                            """.formatted(COLUMNS),
                            (resultSet, row) -> toolDetails(resultSet, toolName),
                            owner.value(), catalogId, toolName)
                    .stream()
                    .findFirst();
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw failed();
        }
    }

    private CatalogDetails details(ResultSet resultSet) throws SQLException {
        CatalogSummary summary = summary(resultSet);
        String specificationChecksum = resultSet.getString("specification_checksum");
        RuntimeMetadataArtifact metadata = codec.decode(resultSet.getString("metadata_document").getBytes(UTF_8));
        if (!summary.metadataVersion().equals(metadata.document().metadataVersion())
                || !summary.metadataChecksum().equals(metadata.checksum())
                || !specificationChecksum.equals(metadata.document().specificationChecksum())
                || summary.toolCount() != metadata.document().tools().size()) {
            throw failed();
        }
        return new CatalogDetails(summary, specificationChecksum, metadata);
    }

    private ToolDetails toolDetails(ResultSet resultSet, String toolName) throws SQLException {
        CatalogDetails details = details(resultSet);
        RuntimeTool tool = details.metadata().document().tools().stream()
                .filter(candidate -> candidate.name().equals(toolName))
                .findFirst()
                .orElseThrow(PostgresToolCatalogStore::failed);
        if (!codec.encodeTool(tool).equals(resultSet.getString("tool_document"))) {
            throw failed();
        }
        return new ToolDetails(details.summary(), tool);
    }

    private static CatalogSummary summary(ResultSet resultSet) throws SQLException {
        return new CatalogSummary(
                resultSet.getObject("id", UUID.class),
                new JobId(resultSet.getObject("generation_job_id", UUID.class)),
                resultSet.getString("metadata_version"),
                resultSet.getString("metadata_checksum"),
                resultSet.getInt("tool_count"),
                resultSet.getTimestamp("created_at").toInstant(),
                new CatalogVersion(
                        resultSet.getObject("family_id", UUID.class),
                        resultSet.getLong("revision"),
                        Optional.ofNullable(resultSet.getObject("predecessor_catalog_id", UUID.class))));
    }

    private void requireList(AccountId owner, int fetchLimit, Optional<CatalogCursor> cursor) {
        if (owner == null || fetchLimit < 2 || fetchLimit > 101 || cursor == null
                || cursor.filter(value -> value.createdAt() == null || value.id() == null).isPresent()) {
            throw invalid();
        }
    }

    private void requireIdentity(AccountId owner, UUID catalogId) {
        if (owner == null || catalogId == null) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Tool Catalog storage query is invalid");
    }

    private static IllegalStateException failed() {
        return new IllegalStateException(SAFE_FAILURE);
    }
}

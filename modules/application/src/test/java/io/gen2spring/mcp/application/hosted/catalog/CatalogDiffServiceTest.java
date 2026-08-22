package io.gen2spring.mcp.application.hosted.catalog;

import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.CREDENTIAL_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.DESCRIPTION_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.HTTP_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.INPUT_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.OUTPUT_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.OUTPUT_OPTIONAL_PROPERTY_ADDED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.POLICY_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.TOOL_ADDED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.TOOL_REMOVED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility.BREAKING;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility.COMPATIBLE;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogVersion;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogDiffServiceTest {
    private static final AccountId OWNER = new AccountId(
            UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final AccountId OTHER = new AccountId(
            UUID.fromString("5d0c27ac-1c18-487d-b922-28653572fc4a"));
    private static final UUID SOURCE = UUID.fromString("6d65bd83-547b-4965-82f0-eb31af0dcd21");
    private static final UUID TARGET = UUID.fromString("8f5a48fd-f34b-4ba5-b749-eb79008370d5");

    @Test
    void classifiesToolAdditionsAsCompatibleAndRemovalsAsBreaking() {
        RuntimeTool alpha = tool("alpha");
        RuntimeTool beta = tool("beta");

        CatalogDiff addition = compare(List.of(alpha), List.of(beta, alpha));
        assertEquals(COMPATIBLE, addition.compatibility());
        assertEquals(List.of(TOOL_ADDED), kinds(addition));
        assertEquals("beta", addition.changes().getFirst().toolName());

        CatalogDiff removal = compare(List.of(alpha, beta), List.of(alpha));
        assertEquals(BREAKING, removal.compatibility());
        assertEquals(List.of(TOOL_REMOVED), kinds(removal));
    }

    @Test
    void keepsDescriptionOnlyChangesCompatibleAndInputChangesBreaking() {
        RuntimeTool source = tool("alpha");
        RuntimeTool described = copy(source, "New description", source.inputSchema(),
                source.outputKind(), source.outputSchema(), source.http(), source.retry(), source.credentials());
        CatalogDiff description = compare(List.of(source), List.of(described));
        assertEquals(COMPATIBLE, description.compatibility());
        assertEquals(List.of(DESCRIPTION_CHANGED), kinds(description));

        RuntimeTool input = copy(source, source.description(),
                objectSchema(Map.of("city", Map.of("type", "string")), List.of()),
                source.outputKind(), source.outputSchema(), source.http(), source.retry(), source.credentials());
        CatalogDiff inputDiff = compare(List.of(source), List.of(input));
        assertEquals(BREAKING, inputDiff.compatibility());
        assertEquals(List.of(INPUT_CHANGED), kinds(inputDiff));
    }

    @Test
    void acceptsOnlyRecursiveOptionalOutputPropertyAdditions() {
        Map<String, Object> sourceOutput = objectSchema(
                Map.of("data", objectSchema(
                        Map.of("id", Map.of("type", "string")), List.of("id"))),
                List.of("data"));
        Map<String, Object> compatibleOutput = objectSchema(
                Map.of("data", objectSchema(
                        Map.of(
                                "id", Map.of("type", "string"),
                                "label", Map.of("type", "string")),
                        List.of("id"))),
                List.of("data"));
        RuntimeTool source = withOutput(tool("alpha"), sourceOutput);
        RuntimeTool compatible = withOutput(tool("alpha"), compatibleOutput);

        CatalogDiff addition = compare(List.of(source), List.of(compatible));
        assertEquals(COMPATIBLE, addition.compatibility());
        assertEquals(List.of(OUTPUT_OPTIONAL_PROPERTY_ADDED), kinds(addition));

        Map<String, Object> requiredOutput = objectSchema(
                Map.of("data", objectSchema(
                        Map.of(
                                "id", Map.of("type", "string"),
                                "label", Map.of("type", "string")),
                        List.of("id", "label"))),
                List.of("data"));
        CatalogDiff required = compare(List.of(source), List.of(withOutput(tool("alpha"), requiredOutput)));
        assertEquals(BREAKING, required.compatibility());
        assertEquals(List.of(OUTPUT_CHANGED), kinds(required));
    }

    @Test
    void treatsHttpPolicyAndCredentialContractChangesAsBreaking() {
        RuntimeTool source = tool("alpha");
        RuntimeTool target = copy(
                source,
                source.description(),
                source.inputSchema(),
                source.outputKind(),
                source.outputSchema(),
                new RuntimeHttp(GET, "https://api.example.test", "/v2/items", List.of(), false, false),
                new RetryPolicy(List.of(503), false, 1, 10, 10, false),
                List.of(new RuntimeCredential("service_key", QUERY, "key", true)));

        CatalogDiff diff = compare(List.of(source), List.of(target));
        assertEquals(BREAKING, diff.compatibility());
        assertEquals(List.of(CREDENTIAL_CHANGED, HTTP_CHANGED, POLICY_CHANGED), kinds(diff));
    }

    @Test
    void comparesHeaderCredentialNamesCaseInsensitivelyButQueryNamesExactly() {
        RuntimeTool headerSource = withCredentials(
                tool("alpha"), List.of(new RuntimeCredential("service_key", HEADER, "X-Api-Key", true)));
        RuntimeTool headerTarget = withCredentials(
                tool("alpha"), List.of(new RuntimeCredential("service_key", HEADER, "x-api-key", true)));
        assertTrue(compare(List.of(headerSource), List.of(headerTarget)).changes().isEmpty());

        RuntimeTool querySource = withCredentials(
                tool("alpha"), List.of(new RuntimeCredential("service_key", QUERY, "ApiKey", true)));
        RuntimeTool queryTarget = withCredentials(
                tool("alpha"), List.of(new RuntimeCredential("service_key", QUERY, "apikey", true)));
        CatalogDiff query = compare(List.of(querySource), List.of(queryTarget));
        assertEquals(BREAKING, query.compatibility());
        assertEquals(List.of(CREDENTIAL_CHANGED), kinds(query));
    }

    @Test
    void ignoresInsertionOrderAndProducesOneStableSortedChecksum() {
        LinkedHashMap<String, Object> leftProperties = new LinkedHashMap<>();
        leftProperties.put("zeta", Map.of("type", "string"));
        leftProperties.put("alpha", Map.of("type", "integer"));
        LinkedHashMap<String, Object> rightProperties = new LinkedHashMap<>();
        rightProperties.put("alpha", Map.of("type", "integer"));
        rightProperties.put("zeta", Map.of("type", "string"));

        RuntimeTool left = copy(tool("zeta"), "Old", objectSchema(leftProperties, List.of()),
                "GENERIC_JSON", Map.of(), tool("zeta").http(), null, List.of());
        RuntimeTool right = copy(tool("zeta"), "New", objectSchema(rightProperties, List.of()),
                "GENERIC_JSON", Map.of(), tool("zeta").http(), null, List.of());
        CatalogDiff first = compare(List.of(tool("alpha"), left), List.of(right, tool("beta"), tool("alpha")));
        CatalogDiff second = compare(List.of(left, tool("alpha")), List.of(tool("alpha"), tool("beta"), right));

        assertEquals(first.changes(), second.changes());
        assertEquals(first.checksum(), second.checksum());
        assertTrue(first.checksum().matches("[a-f0-9]{64}"));
        assertEquals(List.of(TOOL_ADDED, DESCRIPTION_CHANGED), kinds(first));
        assertEquals(List.of("beta", "zeta"), first.changes().stream().map(CatalogDiff.ToolChange::toolName).toList());
    }

    @Test
    void failsClosedForInvalidMetadataAndMasksCrossFamilyOrOwnerLookups() {
        StubStore store = store(List.of(tool("alpha")), List.of(tool("alpha")));
        CatalogDetails valid = store.catalogs.get(TARGET);
        RuntimeMetadataArtifact invalid = new RuntimeMetadataArtifact(
                valid.metadata().document(), valid.metadata().checksum(), "{}\n".getBytes(StandardCharsets.UTF_8));
        store.catalogs.put(TARGET, new CatalogDetails(valid.summary(), valid.specificationChecksum(), invalid));
        CatalogDiffService service = new CatalogDiffService(store);
        assertThrows(CatalogDiffService.CatalogDiffUnavailable.class,
                () -> service.compare(OWNER, SOURCE, TARGET));

        StubStore crossFamily = store(List.of(tool("alpha")), List.of(tool("alpha")));
        CatalogDetails target = crossFamily.catalogs.get(TARGET);
        CatalogVersion isolated = new CatalogVersion(TARGET, 1, Optional.empty());
        crossFamily.catalogs.put(TARGET, new CatalogDetails(
                summary(TARGET, isolated, target.metadata()), target.specificationChecksum(), target.metadata()));
        CatalogDiffService crossFamilyService = new CatalogDiffService(crossFamily);
        assertThrows(CatalogDiffService.CatalogDiffNotFound.class,
                () -> crossFamilyService.compare(OWNER, SOURCE, TARGET));
        assertThrows(CatalogDiffService.CatalogDiffNotFound.class,
                () -> crossFamilyService.compare(OTHER, SOURCE, TARGET));
        assertFalse(crossFamilyService.toString().contains(SOURCE.toString()));
    }

    private CatalogDiff compare(List<RuntimeTool> source, List<RuntimeTool> target) {
        return new CatalogDiffService(store(source, target)).compare(OWNER, SOURCE, TARGET);
    }

    private StubStore store(List<RuntimeTool> source, List<RuntimeTool> target) {
        StubStore store = new StubStore();
        RuntimeMetadataArtifact sourceMetadata = metadata(source);
        RuntimeMetadataArtifact targetMetadata = metadata(target);
        store.catalogs.put(SOURCE, new CatalogDetails(
                summary(SOURCE, new CatalogVersion(SOURCE, 1, Optional.empty()), sourceMetadata),
                sourceMetadata.document().specificationChecksum(), sourceMetadata));
        store.catalogs.put(TARGET, new CatalogDetails(
                summary(TARGET, new CatalogVersion(SOURCE, 2, Optional.of(SOURCE)), targetMetadata),
                targetMetadata.document().specificationChecksum(), targetMetadata));
        return store;
    }

    private static CatalogSummary summary(
            UUID catalogId,
            CatalogVersion version,
            RuntimeMetadataArtifact metadata) {
        return new CatalogSummary(
                catalogId,
                new JobId(UUID.nameUUIDFromBytes(catalogId.toString().getBytes(StandardCharsets.UTF_8))),
                RuntimeMetadataDocument.VERSION,
                metadata.checksum(),
                metadata.document().tools().size(),
                Instant.parse("2026-08-22T00:00:00Z").plusSeconds(version.revision()),
                version);
    }

    private RuntimeMetadataArtifact metadata(List<RuntimeTool> tools) {
        return new CanonicalRuntimeMetadataCodec().encode(new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, "a".repeat(64), tools));
    }

    private List<CatalogDiff.ChangeKind> kinds(CatalogDiff diff) {
        return diff.changes().stream().map(CatalogDiff.ToolChange::kind).toList();
    }

    private RuntimeTool tool(String name) {
        return new RuntimeTool(
                name + "Operation", name, "Description",
                objectSchema(Map.of(), List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/items", List.of(), false, false),
                null, null, null, List.of());
    }

    private RuntimeTool withOutput(RuntimeTool source, Map<String, Object> output) {
        return copy(source, source.description(), source.inputSchema(),
                "TYPED_DTO", output, source.http(), source.retry(), source.credentials());
    }

    private RuntimeTool withCredentials(RuntimeTool source, List<RuntimeCredential> credentials) {
        return copy(source, source.description(), source.inputSchema(), source.outputKind(),
                source.outputSchema(), source.http(), source.retry(), credentials);
    }

    private RuntimeTool copy(
            RuntimeTool source,
            String description,
            Map<String, Object> input,
            String outputKind,
            Map<String, Object> output,
            RuntimeHttp http,
            RetryPolicy retry,
            List<RuntimeCredential> credentials) {
        return new RuntimeTool(
                source.operationId(), source.name(), description, input, outputKind, output,
                http, source.responseNormalization(), retry, source.pagination(), credentials);
    }

    private Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private static final class StubStore implements ToolCatalogStore {
        private final Map<UUID, CatalogDetails> catalogs = new LinkedHashMap<>();

        @Override
        public List<CatalogSummary> list(
                AccountId owner,
                int fetchLimit,
                Optional<CatalogCursor> cursor) {
            return new ArrayList<>();
        }

        @Override
        public Optional<CatalogDetails> find(AccountId owner, UUID catalogId) {
            return OWNER.equals(owner) ? Optional.ofNullable(catalogs.get(catalogId)) : Optional.empty();
        }

        @Override
        public Optional<ToolDetails> findTool(
                AccountId owner,
                UUID catalogId,
                String toolName) {
            return Optional.empty();
        }
    }
}

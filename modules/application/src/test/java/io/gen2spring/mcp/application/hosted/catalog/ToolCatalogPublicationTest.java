package io.gen2spring.mcp.application.hosted.catalog;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataArtifact;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCatalogPublicationTest {
    private static final String SPECIFICATION_CHECKSUM = "a".repeat(64);
    private final CanonicalRuntimeMetadataCodec codec = new CanonicalRuntimeMetadataCodec();

    @Test
    void retainsExactCanonicalUtf8AndAssignsDeterministicOrdinals() {
        RuntimeMetadataArtifact source = codec.encode(document(List.of(tool("zeta"), tool("alpha"))));

        ToolCatalogPublication publication = ToolCatalogPublication.from(source);

        assertArrayEquals(source.content(), publication.metadata().content());
        assertEquals(List.of("alpha", "zeta"), publication.entries().stream()
                .map(entry -> entry.tool().name()).toList());
        assertEquals(List.of(0, 1), publication.entries().stream()
                .map(ToolCatalogPublication.ToolEntry::ordinal).toList());
        byte[] exposed = publication.metadata().content();
        exposed[0] = 'X';
        assertEquals('{', publication.metadata().content()[0]);
        assertFalse(new String(publication.metadata().content(), UTF_8).contains("KMA_SERVICE_KEY"));
    }

    @Test
    void rejectsEmptyOversizedAndNonCanonicalPublicationsWithOneSafeFailure() {
        assertInvalid(() -> new ToolCatalogPublication(null));
        assertInvalid(() -> ToolCatalogPublication.from(codec.encode(document(List.of()))));

        List<RuntimeTool> tooMany = java.util.stream.IntStream.range(0, 1_001)
                .mapToObj(index -> tool("tool_" + index))
                .toList();
        assertInvalid(() -> ToolCatalogPublication.from(codec.encode(document(tooMany))));

        RuntimeMetadataArtifact canonical = codec.encode(document(List.of(tool("weather"))));
        byte[] reordered = new String(canonical.content(), UTF_8)
                .replaceFirst(
                        "\\{\\\"metadataVersion\\\":\\\"1.0\\\",\\\"specificationChecksum\\\":\\\""
                                + SPECIFICATION_CHECKSUM + "\\\"",
                        "{\\\"specificationChecksum\\\":\\\"" + SPECIFICATION_CHECKSUM
                                + "\\\",\\\"metadataVersion\\\":\\\"1.0\\\"")
                .getBytes(UTF_8);
        RuntimeMetadataArtifact malformed = new RuntimeMetadataArtifact(
                canonical.document(), canonical.checksum(), reordered);
        assertInvalid(() -> ToolCatalogPublication.from(malformed));

        assertEquals(CanonicalRuntimeMetadataCodec.MAX_BYTES, 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> new RuntimeMetadataArtifact(
                canonical.document(), canonical.checksum(),
                new byte[CanonicalRuntimeMetadataCodec.MAX_BYTES + 1]));
    }

    private void assertInvalid(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals("Hosted Tool Catalog publication is invalid", failure.getMessage());
        assertFalse(failure.toString().contains("tool_1000"));
    }

    private RuntimeMetadataDocument document(List<RuntimeTool> tools) {
        return new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, SPECIFICATION_CHECKSUM, tools);
    }

    private RuntimeTool tool(String name) {
        return new RuntimeTool(
                name + "Operation",
                name,
                "Catalog Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON",
                Map.of(),
                new RuntimeHttp(GET, "https://api.example.test", "/weather", List.of(), false, false),
                null,
                null,
                null,
                List.of());
    }
}

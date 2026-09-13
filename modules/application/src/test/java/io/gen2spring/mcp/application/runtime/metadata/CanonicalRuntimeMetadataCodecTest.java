package io.gen2spring.mcp.application.runtime.metadata;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalRuntimeMetadataCodecTest {
    private static final String SPECIFICATION_CHECKSUM = "a".repeat(64);
    private final CanonicalRuntimeMetadataCodec codec = new CanonicalRuntimeMetadataCodec();

    @Test
    void encodesAndDecodesOneCanonicalSecretSafeDocument() {
        RuntimeMetadataArtifact artifact = codec.encode(document());
        String json = new String(artifact.content(), UTF_8);

        assertEquals(expectedJson(artifact.checksum()), json);
        assertEquals("a69e76c527c90f837ba1794163959279d13d82da997e3ab23742d64c5e46cf81",
                artifact.checksum());
        assertFalse(json.contains("KMA_SERVICE_KEY"));
        assertFalse(json.contains("targetProfile"));

        RuntimeMetadataArtifact decoded = codec.decode(artifact.content());
        assertEquals(artifact.document(), decoded.document());
        assertEquals(artifact.checksum(), decoded.checksum());
        assertArrayEquals(artifact.content(), decoded.content());
        assertEquals(expectedToolJson(), codec.encodeTool(document().tools().getFirst()));
    }

    @Test
    void rejectsNonCanonicalMalformedAndChecksumMutatedDocuments() {
        byte[] canonical = codec.encode(document()).content();
        String json = new String(canonical, UTF_8);
        List<byte[]> rejected = List.of(
                json.replaceFirst("\\{", "{\"unknown\":true,").getBytes(UTF_8),
                json.replaceFirst(
                        "\"metadataVersion\":\"1.0\"",
                        "\"metadataVersion\":\"1.0\",\"metadataVersion\":\"1.0\"")
                        .getBytes(UTF_8),
                json.replaceFirst(
                        "\\{\"metadataVersion\":\"1.0\",\"specificationChecksum\":\"" + SPECIFICATION_CHECKSUM + "\"",
                        "{\"specificationChecksum\":\"" + SPECIFICATION_CHECKSUM
                                + "\",\"metadataVersion\":\"1.0\"").getBytes(UTF_8),
                json.substring(0, json.length() - 1).getBytes(UTF_8),
                (json + "\n").getBytes(UTF_8),
                json.replace("\"checksum\":\"", "\"checksum\":\"0").getBytes(UTF_8),
                (json + "{}").getBytes(UTF_8));

        for (byte[] content : rejected) {
            GeneratorException failure = assertThrows(GeneratorException.class, () -> codec.decode(content));
            assertEquals(GeneratorErrorCode.RUNTIME_METADATA_INVALID, failure.code());
            assertEquals("Runtime metadata could not be generated", failure.safeMessage());
        }
    }

    @Test
    void rejectsContentBeyondTheOneMibBoundaryAndDefensivelyCopiesBytes() {
        RuntimeMetadataArtifact artifact = codec.encode(document());
        byte[] exposed = artifact.content();
        exposed[0] = 'X';
        assertEquals('{', artifact.content()[0]);

        RuntimeMetadataArtifact base = codec.encode(document());
        int fillerLength = CanonicalRuntimeMetadataCodec.MAX_BYTES - base.content().length;
        RuntimeMetadataArtifact exact = codec.encode(withDescription("Get weather" + "x".repeat(fillerLength)));
        assertEquals(CanonicalRuntimeMetadataCodec.MAX_BYTES, exact.content().length);
        assertThrows(GeneratorException.class,
                () -> codec.encode(withDescription("Get weather" + "x".repeat(fillerLength + 1))));
        assertThrows(GeneratorException.class,
                () -> codec.decode(new byte[CanonicalRuntimeMetadataCodec.MAX_BYTES + 1]));
    }

    @Test
    void rejectsMalformedSchemaValuesBeforePublishingMetadata() {
        RuntimeTool source = document().tools().getFirst();
        List<Map<String, Object>> invalidSchemas = List.of(
                Map.of("type", true),
                Map.of("type", "array", "items", Map.of("type", "string"), "minItems", -1),
                Map.of("type", "array", "items", Map.of("type", "string"), "uniqueItems", true),
                Map.of("oneOf", List.of()),
                Map.of("type", "string", "oneOf", List.of(Map.of("type", "string"))),
                Map.of("type", "string", "unknown", true),
                Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("missing")));

        for (Map<String, Object> invalidSchema : invalidSchemas) {
            RuntimeTool malformed = new RuntimeTool(
                    source.operationId(), source.name(), source.description(),
                    invalidSchema, source.outputKind(), source.outputSchema(), source.http(),
                    source.responseNormalization(), source.retry(), source.pagination(), source.credentials());
            GeneratorException failure = assertThrows(GeneratorException.class,
                    () -> codec.encode(new RuntimeMetadataDocument(
                            RuntimeMetadataDocument.VERSION, SPECIFICATION_CHECKSUM, List.of(malformed))));

            assertEquals(GeneratorErrorCode.RUNTIME_METADATA_INVALID, failure.code());
            assertEquals("Runtime metadata could not be generated", failure.safeMessage());
        }
    }

    @Test
    void roundTripsBoundedArrayAndCompositionKeywordsCanonically() {
        RuntimeTool source = document().tools().getFirst();
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "values", Map.of(
                                "type", "array",
                                "items", Map.of("oneOf", List.of(
                                        Map.of("type", "integer"),
                                        Map.of("type", "string"))),
                                "minItems", 1,
                                "maxItems", 4,
                                "uniqueItems", true)),
                "required", List.of("values"));
        RuntimeTool expanded = new RuntimeTool(
                source.operationId(), source.name(), source.description(), inputSchema,
                source.outputKind(), source.outputSchema(), source.http(), source.responseNormalization(),
                source.retry(), source.pagination(), source.credentials());
        RuntimeMetadataDocument document = new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, SPECIFICATION_CHECKSUM, List.of(expanded));

        RuntimeMetadataArtifact encoded = codec.encode(document);
        RuntimeMetadataArtifact decoded = codec.decode(encoded.content());

        assertEquals(inputSchema, decoded.document().tools().getFirst().inputSchema());
        String json = new String(encoded.content(), UTF_8);
        assertTrue(json.contains("\"oneOf\""));
        assertTrue(json.contains("\"maxItems\":4,\"uniqueItems\":true"));
    }

    private RuntimeMetadataDocument document() {
        return withDescription("Get weather");
    }

    private RuntimeMetadataDocument withDescription(String description) {
        RuntimeTool tool = new RuntimeTool(
                "getWeather", "weather_get", description,
                Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of(
                                "type", "string", "description", "City name")),
                        "required", List.of("city")),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(
                        GET, "https://api.example.test", "/weather",
                        List.of(new ParameterBinding("city", QUERY, "q")), false, false),
                new ResponseNormalizationPolicy(
                        "/data", "/code", List.of("00", BigInteger.ZERO), "/message", "/total"),
                new RetryPolicy(List.of(429, 503), true, 2, 100, 1_000, true),
                new PaginationPolicy("cursor", "start", "/items", "/next", 3, 100),
                List.of(new RuntimeCredential("service-key", HEADER, "X-API-Key", true)));
        return new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, SPECIFICATION_CHECKSUM, List.of(tool));
    }

    private String expectedJson(String checksum) {
        return "{\"metadataVersion\":\"1.0\",\"specificationChecksum\":\"" + SPECIFICATION_CHECKSUM
                + "\",\"checksum\":\"" + checksum + "\",\"tools\":[" + expectedToolJson() + "]}\n";
    }

    private String expectedToolJson() {
        return "{\"operationId\":\"getWeather\",\"name\":\"weather_get\",\"description\":\"Get weather\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\","
                + "\"description\":\"City name\"}},\"required\":[\"city\"]},\"outputKind\":\"GENERIC_JSON\","
                + "\"outputSchema\":{},\"http\":{\"method\":\"GET\",\"baseUrl\":\"https://api.example.test\","
                + "\"path\":\"/weather\",\"bindings\":[{\"sourceName\":\"city\",\"targetLocation\":\"QUERY\","
                + "\"targetName\":\"q\"}],\"objectRequestBody\":false,\"requestBodyRequired\":false},"
                + "\"responseNormalization\":{\"dataPointer\":\"/data\",\"successCodePointer\":\"/code\","
                + "\"successValues\":[\"00\",0],\"errorMessagePointer\":\"/message\",\"totalCountPointer\":\"/total\"},"
                + "\"retry\":{\"statusCodes\":[429,503],\"networkErrors\":true,\"maxRetries\":2,"
                + "\"initialBackoffMillis\":100,\"maxBackoffMillis\":1000,\"respectRetryAfter\":true},"
                + "\"pagination\":{\"requestParameter\":\"cursor\",\"initialValue\":\"start\","
                + "\"itemsPointer\":\"/items\",\"nextValuePointer\":\"/next\",\"maxPages\":3,\"maxItems\":100},"
                + "\"credentials\":[{\"credentialSlot\":\"service-key\",\"targetLocation\":\"HEADER\","
                + "\"targetName\":\"X-API-Key\",\"required\":true}]}";
    }
}

package io.gen2spring.mcp.domain.runtime;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeMetadataDocumentTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void rejectsInvalidIdentityUriCredentialAndDuplicateToolContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeMetadataDocument("2.0", HASH, List.of(tool("weather", "https://api.test"))));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, "not-a-hash",
                        List.of(tool("weather", "https://api.test"))));
        assertThrows(IllegalArgumentException.class,
                () -> tool(" ", "https://api.test"));
        assertThrows(IllegalArgumentException.class,
                () -> tool("weather", "https://user@api.test"));
        assertThrows(IllegalArgumentException.class,
                () -> tool("weather", "https://api.test#private"));
        assertThrows(IllegalArgumentException.class,
                () -> credential("Service.Key", HEADER, "X-API-Key"));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, HASH,
                        List.of(tool("weather", "https://api.test"), tool("weather", "https://api.test"))));
    }

    @Test
    void rejectsCredentialSlotsThatResolveToDifferentNormalizedTargets() {
        RuntimeTool first = tool("weather", "https://api.test");
        RuntimeTool conflicting = new RuntimeTool(
                "getForecast", "forecast", "Forecast", Map.of(), "GENERIC_JSON", Map.of(),
                http("https://api.test"), null, null, null,
                List.of(credential("service-key", HEADER, "X-Other-Key")));

        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeMetadataDocument(RuntimeMetadataDocument.VERSION, HASH,
                        List.of(first, conflicting)));
    }

    @Test
    void normalizesOrderingAndDefensivelyCopiesNestedCollections() {
        Map<String, Object> nested = new LinkedHashMap<>();
        List<String> mutableValues = new ArrayList<>(List.of("first"));
        nested.put("value", mutableValues);
        RuntimeTool later = new RuntimeTool(
                "zOperation", "zeta", "Zeta", nested, "GENERIC_JSON", Map.of(),
                http("https://api.test"), null, null, null,
                List.of(credential("service-key", HEADER, "x-api-key")));
        RuntimeTool earlier = tool("alpha", "https://api.test");
        List<RuntimeTool> source = new ArrayList<>(List.of(later, earlier));

        RuntimeMetadataDocument document = new RuntimeMetadataDocument(
                RuntimeMetadataDocument.VERSION, HASH, source);
        source.clear();
        mutableValues.add("second");
        nested.put("other", true);

        assertEquals(List.of("alpha", "zeta"), document.tools().stream().map(RuntimeTool::name).toList());
        assertEquals(Map.of("value", List.of("first")), document.tools().get(1).inputSchema());
        assertThrows(UnsupportedOperationException.class,
                () -> document.tools().get(1).inputSchema().put("other", false));
    }

    private RuntimeTool tool(String name, String baseUrl) {
        return new RuntimeTool(
                "getWeather", name, "Get weather", Map.of(), "GENERIC_JSON", Map.of(),
                http(baseUrl), null, null, null,
                List.of(credential("service-key", HEADER, "X-API-Key")));
    }

    private RuntimeHttp http(String baseUrl) {
        return new RuntimeHttp(
                GET, baseUrl, "/weather",
                List.of(new ParameterBinding("city", io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY, "q")),
                false, false);
    }

    private RuntimeCredential credential(
            String slot,
            io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation location,
            String target) {
        return new RuntimeCredential(slot, location, target, true);
    }
}

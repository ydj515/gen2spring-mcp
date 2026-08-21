package io.gen2spring.mcp.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpJavaSdkEmitterTest {
    @Test
    void emitsExactOrderedToolsAndMapsSuccessAndProviderErrors() {
        RuntimeTool later = tool("zeta", "Zeta\n\ndetail");
        RuntimeTool earlier = tool("alpha", "Alpha description");
        McpJavaSdkEmitter emitter = new McpJavaSdkEmitter();

        var specs = emitter.emit(List.of(later, earlier), (name, arguments) -> {
            if (name.equals("alpha")) {
                assertEquals(Map.of("city", "Seoul"), arguments);
                return ManagedToolResult.success("{\"data\":{\"temperature\":12.50}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
            return ManagedToolResult.providerError(
                    "{\"error\":{\"category\":\"UPSTREAM_SERVER\"}}".getBytes(StandardCharsets.UTF_8),
                    ManagedToolResult.ErrorCategory.UPSTREAM_SERVER, 503);
        });

        assertEquals(List.of("alpha", "zeta"), specs.stream().map(spec -> spec.tool().name()).toList());
        assertEquals("Alpha description", specs.getFirst().tool().description());
        assertEquals("object", specs.getFirst().tool().inputSchema().type());
        assertEquals(List.of("city"), specs.getFirst().tool().inputSchema().required());
        assertEquals(Map.of(
                "city", Map.of("description", "City name", "type", "string")),
                specs.getFirst().tool().inputSchema().properties());

        McpSchema.CallToolResult success = specs.getFirst().callHandler().apply(
                null, new McpSchema.CallToolRequest("alpha", Map.of("city", "Seoul")));
        assertFalse(success.isError());
        assertEquals("{\"data\":{\"temperature\":12.50}}",
                ((McpSchema.TextContent) success.content().getFirst()).text());

        McpSchema.CallToolResult provider = specs.get(1).callHandler().apply(
                null, new McpSchema.CallToolRequest("zeta", Map.of("city", "Seoul")));
        assertTrue(provider.isError());
        assertEquals("{\"error\":{\"category\":\"UPSTREAM_SERVER\"}}",
                ((McpSchema.TextContent) provider.content().getFirst()).text());
    }

    @Test
    void convertsUnexpectedFailuresToFixedErrorsAndPreservesFatalIdentity() {
        RuntimeTool tool = tool("weather", "Weather");
        RuntimeException privateFailure = new RuntimeException("private arguments");
        var failureSpec = new McpJavaSdkEmitter().emit(List.of(tool), (name, arguments) -> {
            throw privateFailure;
        }).getFirst();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> failureSpec.callHandler().apply(null,
                        new McpSchema.CallToolRequest("weather", Map.of())));
        assertEquals("Managed Tool execution failed", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("private"));

        AssertionError fatal = new AssertionError("fatal marker");
        var fatalSpec = new McpJavaSdkEmitter().emit(List.of(tool), (name, arguments) -> {
            throw fatal;
        }).getFirst();
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> fatalSpec.callHandler().apply(null,
                        new McpSchema.CallToolRequest("weather", Map.of()))));
    }

    @Test
    void preservesExplicitNullArgumentsForSchemaValidatedHandlers() {
        RuntimeTool tool = tool("weather", "Weather");
        LinkedHashMap<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("city", null);
        var specification = new McpJavaSdkEmitter().emit(List.of(tool), (name, observed) -> {
            assertTrue(observed.containsKey("city"));
            assertEquals(null, observed.get("city"));
            assertThrows(UnsupportedOperationException.class, () -> observed.put("other", "value"));
            return ManagedToolResult.success("null".getBytes(StandardCharsets.UTF_8));
        }).getFirst();

        McpSchema.CallToolResult result = specification.callHandler().apply(
                null, new McpSchema.CallToolRequest("weather", arguments));

        assertFalse(result.isError());
    }

    private RuntimeTool tool(String name, String description) {
        Map<String, Object> city = new LinkedHashMap<>();
        city.put("description", "City name");
        city.put("type", "string");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("city", city));
        schema.put("required", List.of("city"));
        schema.put("additionalProperties", false);
        return new RuntimeTool(
                "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1), name, description,
                schema, "GENERIC_JSON", Map.of(),
                new RuntimeHttp(HttpMethod.GET, "https://api.example.com", "/weather",
                        List.of(), false, false), null, null, null, List.of());
    }
}

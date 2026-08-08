package io.gen2spring.mcp.domain.generation;

import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.ARRAY;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.BOOLEAN;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.INTEGER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.NUMBER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpectedToolSchemaFactoryTest {
    @Test
    void recursivelyPreservesApplicableSchemaConstraintsAndExcludesSecrets() {
        ApiSchema id = schema(INTEGER, "int64", BigDecimal.ONE, BigDecimal.valueOf(999), 2, 4, "[0-9]+",
                Map.of(), List.of(), null, List.of());
        ApiSchema filter = schema(STRING, "email", BigDecimal.ZERO, BigDecimal.TEN, 3, 8, "[a-z]+",
                Map.of(), List.of(), null, List.of());
        ApiSchema mode = schema(STRING, "uuid", null, null, 4, 36, "[a-z-]+",
                Map.of(), List.of(), null, List.of("fast", "slow-mode"));
        ApiSchema score = schema(NUMBER, "double", BigDecimal.valueOf(-1.5), BigDecimal.valueOf(10.25), 1, 2, "ignored",
                Map.of(), List.of(), null, List.of());
        ApiSchema enabled = schema(BOOLEAN, "ignored", BigDecimal.ZERO, BigDecimal.ONE, 1, 2, "ignored",
                Map.of(), List.of(), null, List.of());
        ApiSchema tags = schema(ARRAY, "ignored", BigDecimal.ZERO, BigDecimal.ONE, 1, 2, "ignored",
                Map.of(), List.of(), filter, List.of());
        ApiSchema criteria = schema(OBJECT, "ignored", BigDecimal.ZERO, BigDecimal.ONE, 1, 2, "ignored",
                Map.of("id", id, "filter", filter, "mode", mode, "score", score,
                        "enabled", enabled, "tags", tags),
                List.of("id"), null, List.of());
        var tool = new McpToolDefinition(
                "search", "catalog_search", "Search the catalog",
                List.of(new McpInputDefinition(
                        "criteria", "criteria", "Search criteria", true, criteria)),
                null,
                List.of(new SecretBinding("CATALOG_KEY", "catalog-key", null, "apiKey", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);

        var expected = new ExpectedToolSchemaFactory().create(List.of(tool)).get("catalog_search");

        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("criteria", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "id", Map.of(
                                        "type", "integer",
                                        "format", "int64",
                                        "minimum", BigDecimal.ONE,
                                        "maximum", BigDecimal.valueOf(999),
                                        "description", "id"),
                                "filter", Map.of(
                                        "type", "string",
                                        "format", "email",
                                        "minLength", 3,
                                        "maxLength", 8,
                                        "pattern", "[a-z]+",
                                        "description", "filter"),
                                "mode", Map.of(
                                        "type", "string",
                                        "format", "uuid",
                                        "enum", List.of("fast", "slow-mode"),
                                        "minLength", 4,
                                        "maxLength", 36,
                                        "pattern", "[a-z-]+",
                                        "description", "mode"),
                                "score", Map.of(
                                        "type", "number",
                                        "format", "double",
                                        "minimum", BigDecimal.valueOf(-1.5),
                                        "maximum", BigDecimal.valueOf(10.25),
                                        "description", "score"),
                                "enabled", Map.of(
                                        "type", "boolean",
                                        "description", "enabled"),
                                "tags", Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "string",
                                                "format", "email",
                                                "minLength", 3,
                                                "maxLength", 8,
                                                "pattern", "[a-z]+"),
                                        "description", "tags")),
                        "required", List.of("id"),
                        "description", "Search criteria")),
                "required", List.of("criteria")), expected.inputSchema());
        assertFalse(containsSchemaKey(expected.inputSchema(), "apiKey"));
    }

    @Test
    void rejectsNonStringEnumsEvenWhenUpstreamMetadataMarksTheSchemaSupported() {
        ApiSchema numericEnum = schema(
                INTEGER, "int32", null, null, null, null, null,
                Map.of(), List.of(), null, List.of("1", "2"));
        var tool = new McpToolDefinition(
                "setLevel", "catalog_set_level", "Set the catalog level",
                List.of(new McpInputDefinition("level", "level", "Level", true, numericEnum)),
                null, List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);

        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedToolSchemaFactory().create(List.of(tool)));
    }

    private ApiSchema schema(
            io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType type,
            String format,
            BigDecimal minimum,
            BigDecimal maximum,
            Integer minLength,
            Integer maxLength,
            String pattern,
            Map<String, ApiSchema> properties,
            List<String> required,
            ApiSchema items,
            List<String> enumValues) {
        return new ApiSchema(
                type, format, false, enumValues, minimum, maximum, minLength, maxLength, pattern,
                null, properties, required, items, true, List.of());
    }

    private boolean containsSchemaKey(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            return map.containsKey(key) || map.values().stream().anyMatch(item -> containsSchemaKey(item, key));
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> containsSchemaKey(item, key));
        }
        return false;
    }
}

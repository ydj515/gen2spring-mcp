package io.gen2spring.mcp.policy;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind.GENERIC_JSON;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind.TYPED_DTO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.config.GenerationRequest.OutputSelection;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.OutputDefinition;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutputSchemaResolverTest {
    private final OutputSchemaResolver resolver = new OutputSchemaResolver();

    @Test
    void resolvesAWholeResponseTypedObject() {
        ApiSchema provider = object(Map.of("city", string()), List.of("city"));

        OutputDefinition output = resolver.resolve(new OutputSelection(TYPED_DTO), provider, null);

        assertEquals(TYPED_DTO, output.kind());
        assertSame(provider, output.providerSchema());
        assertSame(provider, output.resultSchema());
    }

    @Test
    void resolvesEscapedPointersIntoTheNormalizedEnvelopeShape() {
        ApiSchema items = array(string());
        ApiSchema provider = object(Map.of(
                "response", object(Map.of(
                        "body", object(Map.of(
                                "items", items,
                                "total/count", integer()), List.of("items", "total/count")),
                        "header", object(Map.of(
                                "code~value", string(),
                                "message", string()), List.of("code~value", "message"))),
                        List.of("body", "header"))), List.of("response"));
        ResponseNormalizationPolicy policy = new ResponseNormalizationPolicy(
                "/response/body/items", "/response/header/code~0value", List.of("00"),
                "/response/header/message", "/response/body/total~1count");

        OutputDefinition output = resolver.resolve(new OutputSelection(TYPED_DTO), provider, policy);

        ApiSchema expectedPage = object(Map.of("totalCount", nonNegativeInteger()), List.of("totalCount"));
        ApiSchema expectedProvider = object(
                Map.of("code", string(), "message", string()), List.of("code", "message"));
        ApiSchema expected = object(
                Map.of("data", items, "page", expectedPage, "provider", expectedProvider),
                List.of("data", "page", "provider"));
        assertEquals(expected, output.resultSchema());
    }

    @Test
    void treatsTheRootPointerAsAnEmptyObjectPropertyToken() {
        ApiSchema emptyProperty = string();
        ApiSchema provider = object(Map.of("", emptyProperty), List.of(""));

        OutputDefinition output = resolver.resolve(
                new OutputSelection(TYPED_DTO), provider,
                new ResponseNormalizationPolicy("/", null, List.of(), null, null));

        assertEquals(emptyProperty, output.resultSchema().properties().get("data"));
    }

    @Test
    void resolvesArrayIndexPointersAgainstTheItemSchema() {
        ApiSchema item = object(Map.of("name", string()), List.of("name"));
        ApiSchema provider = object(Map.of("items", array(item)), List.of("items"));

        OutputDefinition output = resolver.resolve(
                new OutputSelection(TYPED_DTO), provider,
                new ResponseNormalizationPolicy("/items/0", null, List.of(), null, null));

        assertEquals(item, output.resultSchema().properties().get("data"));
    }

    @Test
    void failsClosedForMissingOrIncompatibleTypedSchemaContracts() {
        ApiSchema provider = object(Map.of(
                "items", array(string()),
                "count", string(),
                "code", object(Map.of("value", string()), List.of("value"))),
                List.of("items", "count", "code"));
        List<ResponseNormalizationPolicy> invalidPolicies = List.of(
                new ResponseNormalizationPolicy("/missing-private-marker", null, List.of(), null, null),
                new ResponseNormalizationPolicy("/items", null, List.of(), null, "/count"),
                new ResponseNormalizationPolicy("/items", "/code", List.of("00"), null, null));

        assertTypedUnsupported(null, null);
        for (ResponseNormalizationPolicy policy : invalidPolicies) {
            assertTypedUnsupported(provider, policy);
        }
    }

    @Test
    void keepsGenericOutputSchemaResolutionOptional() {
        ApiSchema provider = object(Map.of("city", string()), List.of("city"));

        OutputDefinition output = resolver.resolve(
                new OutputSelection(GENERIC_JSON), provider,
                new ResponseNormalizationPolicy("/city", null, List.of(), null, null));

        assertEquals(GENERIC_JSON, output.kind());
        assertSame(provider, output.providerSchema());
        assertNull(output.resultSchema());
    }

    private void assertTypedUnsupported(ApiSchema provider, ResponseNormalizationPolicy policy) {
        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> resolver.resolve(new OutputSelection(TYPED_DTO), provider, policy));
        assertEquals(OPERATION_UNSUPPORTED, failure.code());
        assertEquals("tool-policy", failure.stage());
        assertEquals("Typed Tool output is unsupported", failure.safeMessage());
        assertFalse(failure.safeMessage().contains("private-marker"));
    }

    private ApiSchema object(Map<String, ApiSchema> properties, List<String> required) {
        return schema(SchemaType.OBJECT, null, null, properties, required, null);
    }

    private ApiSchema array(ApiSchema items) {
        return schema(SchemaType.ARRAY, null, null, Map.of(), List.of(), items);
    }

    private ApiSchema string() {
        return schema(SchemaType.STRING, null, null, Map.of(), List.of(), null);
    }

    private ApiSchema integer() {
        return schema(SchemaType.INTEGER, "int64", null, Map.of(), List.of(), null);
    }

    private ApiSchema nonNegativeInteger() {
        return schema(SchemaType.INTEGER, "int64", BigDecimal.ZERO, Map.of(), List.of(), null);
    }

    private ApiSchema schema(
            SchemaType type,
            String format,
            BigDecimal minimum,
            Map<String, ApiSchema> properties,
            List<String> required,
            ApiSchema items) {
        return new ApiSchema(type, format, false, List.of(), minimum, null, null, null, null, null,
                sorted(properties), List.copyOf(required), items, true, List.of());
    }

    private Map<String, ApiSchema> sorted(Map<String, ApiSchema> properties) {
        Map<String, ApiSchema> sorted = new LinkedHashMap<>();
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return java.util.Collections.unmodifiableMap(sorted);
    }
}

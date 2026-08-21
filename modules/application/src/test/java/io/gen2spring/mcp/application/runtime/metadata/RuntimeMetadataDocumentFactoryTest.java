package io.gen2spring.mcp.application.runtime.metadata;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.NUMBER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING;
import static io.gen2spring.mcp.domain.tool.OutputKind.GENERIC_JSON;
import static io.gen2spring.mcp.domain.tool.OutputKind.TYPED_DTO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeMetadataDocumentFactoryTest {
    private static final String SPECIFICATION_CHECKSUM = "a".repeat(64);
    private final RuntimeMetadataDocumentFactory factory = new RuntimeMetadataDocumentFactory();

    @Test
    void projectsFrameworkNeutralToolExecutionAndOutputContracts() {
        ApiSchema result = object(Map.of("temperature", number()), List.of("temperature"));
        ToolDefinition typed = new ToolDefinition(
                "getForecast", "forecast_get", "Get forecast",
                List.of(new ToolInput("city", "city", "City name", true, string(null, true))),
                execution(),
                List.of(new SecretBinding("KMA_SERVICE_KEY", "Service-Key", HEADER, "X-API-Key", true)),
                new ToolOutput(TYPED_DTO, result, result));
        ToolDefinition raw = new ToolDefinition(
                "getRaw", "raw_get", "Get raw response", List.of(), execution(), List.of(),
                new ToolOutput(GENERIC_JSON, result, null));

        var document = factory.create(SPECIFICATION_CHECKSUM, List.of(raw, typed));

        assertEquals(List.of("forecast_get", "raw_get"),
                document.tools().stream().map(tool -> tool.name()).toList());
        var forecast = document.tools().getFirst();
        assertEquals("TYPED_DTO", forecast.outputKind());
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("city", Map.of(
                        "anyOf", List.of(Map.of("type", "string"), Map.of("type", "null")),
                        "description", "City name")),
                "required", List.of("city")), forecast.inputSchema());
        assertEquals(Map.of(
                "type", "object",
                "properties", Map.of("temperature", Map.of(
                        "type", "number",
                        "format", "double",
                        "minimum", new BigDecimal("0.0000000000000000001"),
                        "description", "temperature")),
                "required", List.of("temperature")), forecast.outputSchema());
        assertEquals("service-key", forecast.credentials().getFirst().credentialSlot());
        assertEquals(HEADER, forecast.credentials().getFirst().targetLocation());
        assertEquals("X-API-Key", forecast.credentials().getFirst().targetName());
        assertEquals(Map.of(), document.tools().get(1).outputSchema());
        assertEquals(List.of(429, 503), forecast.retry().statusCodes());
        assertEquals(new BigInteger("1"), forecast.pagination().initialValue());
        assertFalse(forecast.toString().contains("KMA_SERVICE_KEY"));
    }

    @Test
    void allowsOneCredentialSlotForCaseEquivalentHeadersAndRejectsConflictsSafely() {
        ToolDefinition first = toolWithSecret("first", HEADER, "X-API-Key");
        ToolDefinition sameTarget = toolWithSecret("second", HEADER, "x-api-key");
        factory.create(SPECIFICATION_CHECKSUM, List.of(first, sameTarget));

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> factory.create(SPECIFICATION_CHECKSUM,
                        List.of(first, toolWithSecret("third", QUERY, "api_key"))));

        assertEquals(GeneratorErrorCode.RUNTIME_METADATA_INVALID, failure.code());
        assertEquals("RUNTIME_METADATA", failure.stage());
        assertEquals("Runtime metadata could not be generated", failure.safeMessage());
        assertFalse(failure.safeMessage().contains("Service-Key"));
        assertFalse(failure.safeMessage().contains("api_key"));
    }

    @Test
    void representsAnAbsentOpenApiServerAsTheProviderRelativeBaseUrl() {
        ToolDefinition tool = new ToolDefinition(
                "getForecast",
                "forecast",
                "Get forecast",
                List.of(),
                new HttpExecution(GET, null, "/forecast", List.of()),
                List.of(),
                GENERIC_JSON);

        var document = factory.create(SPECIFICATION_CHECKSUM, List.of(tool));

        assertEquals("/", document.tools().getFirst().http().baseUrl());
    }

    private ToolDefinition toolWithSecret(
            String name,
            io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation location,
            String target) {
        return new ToolDefinition(
                name, name, "Credential Tool", List.of(), execution(),
                List.of(new SecretBinding("PRIVATE_ENV", "Service-Key", location, target, true)),
                GENERIC_JSON);
    }

    private HttpExecution execution() {
        return new HttpExecution(
                GET, URI.create("https://api.example.test"), "/forecast",
                List.of(new ParameterBinding("city", QUERY, "q")), false, false,
                new ResponseNormalizationPolicy(
                        "/data", "/code", List.of("00", BigInteger.ZERO), "/message", "/total"),
                new RetryPolicy(List.of(503, 429), true, 2, 100, 1_000, true),
                new PaginationPolicy("cursor", BigInteger.ONE, "/items", "/next", 3, 100));
    }

    private ApiSchema string(String format, boolean nullable) {
        return new ApiSchema(
                STRING, format, nullable, List.of(), null, null, null, null, null,
                null, Map.of(), List.of(), null, true, List.of());
    }

    private ApiSchema object(Map<String, ApiSchema> properties, List<String> required) {
        return new ApiSchema(
                OBJECT, null, false, List.of(), null, null,
                null, null, null, null, properties, required, null, true, List.of());
    }

    private ApiSchema number() {
        return new ApiSchema(
                NUMBER, "double", false, List.of(), new BigDecimal("0.0000000000000000001"), null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
    }
}

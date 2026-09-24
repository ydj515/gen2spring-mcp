package io.gen2spring.mcp.application.generation.validation;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.generation.validation.ExpectedToolResponseFactory.ExpectedToolResponse;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import io.gen2spring.mcp.domain.tool.OutputKind;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpectedToolResponseFactoryTest {
    private final ExpectedToolResponseFactory factory = new ExpectedToolResponseFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void derivesRawProviderEnvelopeAndNormalizedExpectedResult() {
        ExpectedToolResponse result = factory.create(tool(normalization()));

        assertEquals(200, result.upstreamResponse().status());
        assertEquals("application/json", result.upstreamResponse().contentType());
        assertEquals(
                objectMapper.valueToTree(Map.of("validated", true, "operationId", "getForecast")),
                objectMapper.valueToTree(result.upstreamResponse().body()).at("/response/body/items/0"));
        assertEquals("00", objectMapper.valueToTree(result.upstreamResponse().body())
                .at("/response/header/resultCode").textValue());
        assertEquals(1, objectMapper.valueToTree(result.upstreamResponse().body())
                .at("/response/body/totalCount").intValue());
        assertEquals(Map.of(
                "data", Map.of("validated", true, "operationId", "getForecast"),
                "page", Map.of("totalCount", 1),
                "provider", Map.of("code", "00", "message", "NORMAL_SERVICE")),
                result.expectedResult());
        assertEquals(List.of("data", "page", "provider"),
                ((Map<?, ?>) result.expectedResult()).keySet().stream().toList());
    }

    @Test
    void supportsArrayPointersAndDataAncestorsDeterministically() {
        ExpectedToolResponse first = factory.create(tool(policy(
                "/items/2", "/code", List.of("00"), null, null)));
        ExpectedToolResponse second = factory.create(tool(policy(
                "/items/2", "/code", List.of("00"), null, null)));

        assertEquals(
                objectMapper.valueToTree(Map.of("validated", true, "operationId", "getForecast")),
                objectMapper.valueToTree(first.upstreamResponse().body()).at("/items/2"));
        assertEquals(
                java.util.Arrays.asList(
                        null, null, Map.of("validated", true, "operationId", "getForecast")),
                ((Map<?, ?>) first.upstreamResponse().body()).get("items"));
        assertEquals(Map.of("validated", true, "operationId", "getForecast"),
                ((Map<?, ?>) first.expectedResult()).get("data"));
        assertEquals(first, second);

        ExpectedToolResponse ancestor = factory.create(tool(policy(
                "/payload", "/payload/meta/code", List.of("00"),
                "/payload/meta/message", "/payload/count")));
        assertEquals(Map.of(
                "validated", true,
                "operationId", "getForecast",
                "meta", Map.of("code", "00", "message", "NORMAL_SERVICE"),
                "count", 1), ((Map<?, ?>) ancestor.expectedResult()).get("data"));
    }

    @Test
    void decodesEscapedObjectTokensAndTreatsLeadingZeroTokensAsProperties() {
        ExpectedToolResponse result = factory.create(tool(policy(
                "/a~1b/~0value", "/items/01/code", List.of("00"),
                "/unicode/١/message", null)));

        assertEquals(
                objectMapper.valueToTree(Map.of("validated", true, "operationId", "getForecast")),
                objectMapper.valueToTree(result.upstreamResponse().body()).at("/a~1b/~0value"));
        assertEquals("00", objectMapper.valueToTree(result.upstreamResponse().body())
                .at("/items/01/code").textValue());
        assertEquals("NORMAL_SERVICE", ((Map<?, ?>) ((Map<?, ?>)
                ((Map<?, ?>) result.upstreamResponse().body()).get("unicode")).get("١")).get("message"));
    }

    @Test
    void preservesEmptyAndBlankRfc6901ObjectTokens() {
        ExpectedToolResponse rootEmpty = factory.create(tool(policy(
                "/", null, List.of(), null, null)));
        ExpectedToolResponse nestedEmpty = factory.create(tool(policy(
                "/items/", null, List.of(), null, null)));
        ExpectedToolResponse nestedBlank = factory.create(tool(policy(
                "/items/ ", null, List.of(), null, null)));
        Map<String, Object> marker = Map.of("validated", true, "operationId", "getForecast");

        assertEquals(marker, ((Map<?, ?>) rootEmpty.upstreamResponse().body()).get(""));
        assertEquals(Map.of("data", marker), rootEmpty.expectedResult());
        assertEquals(marker, ((Map<?, ?>) ((Map<?, ?>)
                nestedEmpty.upstreamResponse().body()).get("items")).get(""));
        assertEquals(Map.of("data", marker), nestedEmpty.expectedResult());
        assertEquals(marker, ((Map<?, ?>) ((Map<?, ?>)
                nestedBlank.upstreamResponse().body()).get("items")).get(" "));
        assertEquals(Map.of("data", marker), nestedBlank.expectedResult());
    }

    @Test
    void letsConfiguredMetadataReplaceAnArtificialMarkerProperty() {
        ExpectedToolResponse result = factory.create(tool(policy(
                "/payload", "/payload/validated", List.of("00"), null, null)));

        assertEquals(Map.of("validated", "00", "operationId", "getForecast"),
                ((Map<?, ?>) result.expectedResult()).get("data"));
        assertEquals(Map.of("code", "00"),
                ((Map<?, ?>) result.expectedResult()).get("provider"));
    }

    @Test
    void rejectsContainerShapeCollisionsWithTheFixedSafeMessage() {
        var failure = assertThrows(IllegalArgumentException.class, () -> factory.create(tool(policy(
                "/items/0", "/items/code", List.of("00"), null, null))));

        assertEquals("Expected response fixture cannot be derived", failure.getMessage());
    }

    @Test
    void rejectsSparseArraysThatCannotFitTheResponseFixtureBound() {
        var failure = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> factory.create(tool(policy(
                                "/items/209716", null, List.of(), null, null)))));

        assertEquals("Expected response fixture cannot be derived", failure.getMessage());
    }

    @Test
    void preservesTheLegacyRawResultWhenNormalizationIsAbsent() {
        ExpectedToolResponse result = factory.create(tool(null));
        Map<String, Object> marker = Map.of("validated", true, "operationId", "getForecast");

        assertEquals(200, result.upstreamResponse().status());
        assertEquals("application/json", result.upstreamResponse().contentType());
        assertEquals(marker, result.upstreamResponse().body());
        assertEquals(marker, result.expectedResult());
    }

    @Test
    void derivesSchemaValidFixturesForTypedOutputWithoutPagination() {
        var city = new io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema(
                io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.STRING,
                null, false, List.of(), null, null, 1, 10, null, null,
                Map.of(), List.of(), null, true, List.of());
        var provider = new io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema(
                io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType.OBJECT,
                null, false, List.of(), null, null, null, null, null, null,
                Map.of("city", city), List.of("city"), null, true, List.of());
        ToolDefinition typed = new ToolDefinition(
                "getForecast", "weather_get_forecast", "Get a forecast.", List.of(),
                new HttpExecution(GET, URI.create("https://api.example.test"), "/forecast", List.of()),
                List.of(), new ToolOutput(OutputKind.TYPED_DTO, provider, provider));

        ExpectedToolResponse result = factory.create(typed);

        assertEquals(Map.of("city", "a"), result.upstreamResponse().body());
        assertEquals(Map.of("city", "a"), result.expectedResult());
    }

    @Test
    void rejectsPaginationWithAnEmptySuccessCodeListUsingTheSafeFailure() {
        ApiSchema text = new ApiSchema(SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema items = new ApiSchema(SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), text, true, List.of());
        ApiSchema provider = new ApiSchema(SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, Map.of("items", items, "next", text),
                List.of("items", "next"), null, true, List.of());
        var policy = policy(null, "/code", List.of(), null, null);
        var pagination = new PaginationPolicy("cursor", "start", "/items", "/next", 2, 2);
        var execution = new HttpExecution(GET, URI.create("https://api.example.test"), "/forecast",
                List.of(), false, false, policy, null, pagination);
        var tool = new ToolDefinition("getForecast", "weather_get_forecast", "Get a forecast.",
                List.of(), execution, List.of(), new ToolOutput(OutputKind.TYPED_DTO, provider, provider));

        var failure = assertThrows(IllegalArgumentException.class, () -> factory.create(tool));

        assertEquals("Expected response fixture cannot be derived", failure.getMessage());
    }

    private ResponseNormalizationPolicy normalization() {
        return policy(
                "/response/body/items/0",
                "/response/header/resultCode",
                List.of("00"),
                "/response/header/resultMsg",
                "/response/body/totalCount");
    }

    private ResponseNormalizationPolicy policy(
            String dataPointer,
            String successCodePointer,
            List<Object> successValues,
            String errorMessagePointer,
            String totalCountPointer) {
        return new ResponseNormalizationPolicy(
                dataPointer,
                successCodePointer,
                successValues,
                errorMessagePointer,
                totalCountPointer);
    }

    private ToolDefinition tool(ResponseNormalizationPolicy policy) {
        return new ToolDefinition(
                "getForecast",
                "weather_get_forecast",
                "Get a forecast.",
                List.of(),
                new HttpExecution(
                        GET,
                        URI.create("https://api.example.test"),
                        "/forecast",
                        List.of(),
                        false,
                        false,
                        policy),
                List.of(),
                OutputKind.GENERIC_JSON);
    }
}

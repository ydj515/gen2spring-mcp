package io.gen2spring.mcp.validation;

import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod.POST;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.BODY;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.PATH;
import static io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamCallExpectationTest {
    @Test
    void derivesTheExactWeatherWireContractFromSourceArgumentsAndWireTargets() {
        var call = call(
                POST,
                "/stations/{stationId}/forecast",
                List.of(
                        binding("station", PATH, "stationId"),
                        binding("days", QUERY, "days"),
                        binding("mode", QUERY, "mode"),
                        binding("tags", QUERY, "tags"),
                        binding("clientVersion", HEADER, "ClientVersion"),
                        binding("temperature", BODY, "temperature"),
                        binding("active", BODY, "active")),
                true,
                true,
                List.of(
                        secret("Z_WEATHER_KEY", HEADER, "X-Weather-Key", true),
                        secret("A_SERVICE_KEY", QUERY, "serviceKey", true)),
                linkedArguments(
                        "station", "STN01",
                        "days", 3,
                        "mode", "brief",
                        "tags", List.of("public", "forecast"),
                        "clientVersion", "validator",
                        "temperature", 20.0,
                        "active", true));

        var expectation = UpstreamCallExpectation.from(call);
        var expectedBody = linkedArguments("temperature", 20.0, "active", true);

        assertEquals("POST", expectation.method());
        assertEquals("/stations/STN01/forecast", expectation.rawPath());
        assertEquals(Map.of(
                "days", List.of("3"),
                "mode", List.of("brief"),
                "tags", List.of("public", "forecast"),
                "serviceKey", List.of("mcp-validation-secret-1")), expectation.query());
        assertEquals(List.of("validator"), expectation.headers().get("clientversion"));
        assertEquals(List.of("mcp-validation-secret-2"), expectation.headers().get("x-weather-key"));
        assertEquals(expectedBody, expectation.body());
        assertEquals(List.of("temperature", "active"), ((Map<?, ?>) expectation.body()).keySet().stream().toList());
        assertEquals(Map.of(
                "A_SERVICE_KEY", "mcp-validation-secret-1",
                "Z_WEATHER_KEY", "mcp-validation-secret-2"), expectation.environmentOverrides());
        assertThrows(UnsupportedOperationException.class,
                () -> expectation.query().put("extra", List.of("value")));
        assertThrows(UnsupportedOperationException.class,
                () -> expectation.environmentOverrides().put("EXTRA", "value"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) expectation.body();
        assertThrows(UnsupportedOperationException.class, () -> body.put("extra", "value"));
    }

    @Test
    void percentEncodesAPathArgumentAsOneUtf8RouteSegment() {
        var call = call(
                GET,
                "/resources/{resourceId}",
                List.of(binding("resource", PATH, "resourceId")),
                false,
                false,
                List.of(),
                Map.of("resource", "a/b c?\uD55C"));

        assertEquals("/resources/a%2Fb%20c%3F%ED%95%9C", UpstreamCallExpectation.from(call).rawPath());
    }

    @Test
    void preservesRepeatedQueryAndHeaderValueOrder() {
        var call = call(
                GET,
                "/items",
                List.of(
                        binding("tags", QUERY, "tag"),
                        binding("versions", HEADER, "X-Version")),
                false,
                false,
                List.of(),
                linkedArguments("tags", List.of("second", "first"), "versions", List.of(2, 1)));

        var expectation = UpstreamCallExpectation.from(call);

        assertEquals(List.of("second", "first"), expectation.query().get("tag"));
        assertEquals(List.of("2", "1"), expectation.headers().get("x-version"));
    }

    @Test
    void omitsAbsentOptionalBindingsAndSendsARequiredEmptyObjectBody() {
        var call = call(
                POST,
                "/events",
                List.of(
                        binding("mode", QUERY, "mode"),
                        binding("trace", HEADER, "X-Trace"),
                        binding("note", BODY, "note")),
                true,
                true,
                List.of(),
                Map.of());

        var expectation = UpstreamCallExpectation.from(call);

        assertEquals(Map.of(), expectation.query());
        assertEquals(Map.of(), expectation.headers());
        assertEquals(Map.of(), expectation.body());
    }

    @Test
    void omitsAnAbsentOptionalObjectBody() {
        var call = call(POST, "/events", List.of(), true, false, List.of(), Map.of());

        assertEquals(null, UpstreamCallExpectation.from(call).body());
    }

    @Test
    void assignsOneDeterministicSecretPerEnvironmentVariableAndAppliesEveryTarget() {
        var call = call(
                GET,
                "/events",
                List.of(),
                false,
                false,
                List.of(
                        secret("SHARED_KEY", HEADER, "X-Secondary-Key", false),
                        secret("ANOTHER_KEY", QUERY, "accessKey", true),
                        secret("SHARED_KEY", HEADER, "X-Primary-Key", true)),
                Map.of());

        var expectation = UpstreamCallExpectation.from(call);

        assertEquals(Map.of(
                "ANOTHER_KEY", "mcp-validation-secret-1",
                "SHARED_KEY", "mcp-validation-secret-2"), expectation.environmentOverrides());
        assertEquals(List.of("mcp-validation-secret-1"), expectation.query().get("accessKey"));
        assertEquals(List.of("mcp-validation-secret-2"), expectation.headers().get("x-primary-key"));
        assertEquals(List.of("mcp-validation-secret-2"), expectation.headers().get("x-secondary-key"));
    }

    @Test
    void rejectsDuplicateCaseFoldedWireTargetsAcrossArgumentAndSecretBindings() {
        var call = call(
                GET,
                "/events",
                List.of(binding("key", HEADER, "X-Api-Key")),
                false,
                false,
                List.of(secret("API_KEY", HEADER, "x-api-key", true)),
                Map.of("key", "explicit"));

        assertThrows(IllegalArgumentException.class, () -> UpstreamCallExpectation.from(call));
    }

    @Test
    void rejectsMissingPathArgumentsAndUnsupportedCollectionPathValues() {
        var missing = call(
                GET,
                "/items/{itemId}",
                List.of(binding("item", PATH, "itemId")),
                false,
                false,
                List.of(),
                Map.of());
        var collection = call(
                GET,
                "/items/{itemId}",
                List.of(binding("item", PATH, "itemId")),
                false,
                false,
                List.of(),
                Map.of("item", List.of("one", "two")));

        assertThrows(IllegalArgumentException.class, () -> UpstreamCallExpectation.from(missing));
        assertThrows(IllegalArgumentException.class, () -> UpstreamCallExpectation.from(collection));
    }

    @Test
    void rejectsAnIncompleteSecretBindingWithAContractException() {
        var call = call(
                GET,
                "/items",
                List.of(),
                false,
                false,
                java.util.Collections.singletonList(null),
                Map.of());

        assertThrows(IllegalArgumentException.class, () -> UpstreamCallExpectation.from(call));
    }

    @Test
    void carriesTheConfiguredResponseWithoutChangingTheDerivedRequest() {
        ExpectedToolCall legacy = call(
                GET, "/items", List.of(), false, false, List.of(), Map.of());
        ExpectedToolCall configured = new ExpectedToolCall(
                legacy.tool(),
                legacy.arguments(),
                new ExpectedUpstreamResponse(
                        429, "application/problem+json", Map.of("code", "LIMIT")),
                Map.of("error", Map.of("category", "UPSTREAM_CLIENT")));

        UpstreamCallExpectation expectation = UpstreamCallExpectation.from(configured);

        assertEquals("GET", expectation.method());
        assertEquals("/items", expectation.rawPath());
        assertEquals(Map.of(), expectation.query());
        assertEquals(429, expectation.responseStatus());
        assertEquals("application/problem+json", expectation.responseContentType());
        assertEquals(Map.of("code", "LIMIT"), expectation.responseBody());
    }

    @Test
    void rejectsInvalidResponseStatusAndContentTypeWithoutEchoingValues() {
        for (ExpectedUpstreamResponse response : List.of(
                new ExpectedUpstreamResponse(99, "application/json", Map.of()),
                new ExpectedUpstreamResponse(600, "application/json", Map.of()),
                new ExpectedUpstreamResponse(200, null, Map.of()),
                new ExpectedUpstreamResponse(200, "not-a-media-type", Map.of()),
                new ExpectedUpstreamResponse(200, "application/json;", Map.of()),
                new ExpectedUpstreamResponse(200, "application/json\r\nprivate", Map.of()))) {
            ExpectedToolCall legacy = call(
                    GET, "/items", List.of(), false, false, List.of(), Map.of());
            ExpectedToolCall configured = new ExpectedToolCall(
                    legacy.tool(), legacy.arguments(), response, Map.of());

            var failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> UpstreamCallExpectation.from(configured));

            assertEquals("Expected Tool call response fixture is invalid", failure.getMessage());
            org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("private"));
        }
    }

    private ExpectedToolCall call(
            io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod method,
            String path,
            List<ParameterBinding> bindings,
            boolean objectBody,
            boolean bodyRequired,
            List<SecretBinding> secrets,
            Map<String, Object> arguments) {
        var tool = new McpToolDefinition(
                "getForecast",
                "weather_get_forecast",
                "Get a forecast.",
                List.of(),
                new HttpExecutionDefinition(
                        method,
                        URI.create("https://api.example.test"),
                        path,
                        bindings,
                        objectBody,
                        bodyRequired),
                secrets,
                McpToolDefinition.OutputKind.GENERIC_JSON);
        return new ExpectedToolCall(tool, arguments);
    }

    private ParameterBinding binding(
            String source,
            io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation location,
            String target) {
        return new ParameterBinding(source, location, target);
    }

    private SecretBinding secret(
            String environment,
            io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation location,
            String target,
            boolean required) {
        return new SecretBinding(environment, environment.toLowerCase(), location, target, required);
    }

    private Map<String, Object> linkedArguments(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}

package io.gen2spring.mcp.adapter.validation;

import io.gen2spring.mcp.domain.tool.OutputKind;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.POST;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.BODY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.PATH;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.validation.ExpectedToolCall;
import io.gen2spring.mcp.application.validation.ExpectedUpstreamInteraction;
import io.gen2spring.mcp.application.validation.ExpectedUpstreamOutcome;
import io.gen2spring.mcp.application.validation.ExpectedUpstreamResponse;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.math.BigDecimal;
import java.net.URI;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class UpstreamCallExpectationTest {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    @Test
    void derivesOrderedPaginationAndRetryInteractionsWithInternalQueryValues() {
        ExpectedToolCall legacy = call(
                GET,
                "/items",
                List.of(binding("city", QUERY, "city")),
                false,
                false,
                List.of(),
                Map.of("city", "seoul"));
        HttpExecution execution = legacy.tool().execution();
        var tool = new ToolDefinition(
                legacy.tool().operationId(),
                legacy.tool().name(),
                legacy.tool().description(),
                legacy.tool().inputs(),
                new HttpExecution(
                        execution.method(), execution.baseUrl(), execution.path(), execution.bindings(),
                        false, false, null, null,
                        new PaginationPolicy("cursor", "first", "/items", "/next", 2, 10)),
                legacy.tool().secretBindings(),
                legacy.tool().output());
        var call = new ExpectedToolCall(
                tool,
                legacy.arguments(),
                List.of(
                        new ExpectedUpstreamInteraction(
                                Map.of("cursor", "first"),
                                ExpectedUpstreamOutcome.RESPONSE,
                                new ExpectedUpstreamResponse(503, "application/json", Map.of())),
                        new ExpectedUpstreamInteraction(
                                Map.of("cursor", "first"),
                                ExpectedUpstreamOutcome.RESPONSE,
                                new ExpectedUpstreamResponse(
                                        200, "application/json", Map.of("items", List.of(1), "next", "second"))),
                        new ExpectedUpstreamInteraction(
                                Map.of("cursor", "second"),
                                ExpectedUpstreamOutcome.RESPONSE,
                                new ExpectedUpstreamResponse(
                                        200, "application/json", linkedArguments("items", List.of(2), "next", null)))),
                linkedArguments("items", List.of(1, 2), "next", null));

        List<UpstreamCallExpectation> expectations = UpstreamCallExpectation.allFrom(call);

        assertEquals(3, expectations.size());
        assertEquals(Map.of("city", List.of("seoul"), "cursor", List.of("first")),
                expectations.get(0).query());
        assertEquals(503, expectations.get(0).responseStatus());
        assertEquals(Map.of("city", List.of("seoul"), "cursor", List.of("first")),
                expectations.get(1).query());
        assertEquals(Map.of("city", List.of("seoul"), "cursor", List.of("second")),
                expectations.get(2).query());
        assertEquals(ExpectedUpstreamOutcome.RESPONSE, expectations.get(2).outcome());
        assertThrows(UnsupportedOperationException.class, () -> expectations.add(expectations.getFirst()));
    }

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
    void canonicalizesDecimalWireValuesAfterTheMcpJsonRoundTrip() {
        var call = call(
                GET,
                "/coordinates/{longitude}",
                List.of(
                        binding("longitude", PATH, "longitude"),
                        binding("latitude", QUERY, "latitude"),
                        binding("distance", QUERY, "distance"),
                        binding("altitude", HEADER, "X-Altitude")),
                false,
                false,
                List.of(),
                linkedArguments(
                        "longitude", new BigDecimal("127.0"),
                        "latitude", new BigDecimal("37.500"),
                        "distance", new BigDecimal("1E+1000000"),
                        "altitude", new BigDecimal("1000.0")));

        var expectation = UpstreamCallExpectation.from(call);

        assertEquals("/coordinates/127", expectation.rawPath());
        assertEquals(List.of("37.5"), expectation.query().get("latitude"));
        assertEquals(List.of("1E+1000000"), expectation.query().get("distance"));
        assertEquals(List.of("1000"), expectation.headers().get("x-altitude"));
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

    @ParameterizedTest
    @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void rejectsBodiesForEveryNonBodyHttpMethod(HttpMethod method) {
        var call = call(
                method,
                "/events",
                List.of(binding("payload", BODY, "payload")),
                false,
                false,
                List.of(),
                Map.of("payload", "value"));

        var failure = assertThrows(IllegalArgumentException.class, () -> UpstreamCallExpectation.from(call));

        assertEquals("Expected Tool call body is unsupported for the HTTP method", failure.getMessage());
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
    void preservesEmptyAndBlankResponseKeysAcrossExpectationDerivation() {
        ExpectedToolCall legacy = call(
                GET, "/items", List.of(), false, false, List.of(), Map.of());
        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("", true);
        responseBody.put("items", Map.of("", Map.of(" ", true)));
        ExpectedToolCall configured = new ExpectedToolCall(
                legacy.tool(),
                legacy.arguments(),
                new ExpectedUpstreamResponse(200, "application/json", responseBody),
                Map.of(" ", Map.of("", true)));

        UpstreamCallExpectation expectation = UpstreamCallExpectation.from(configured);

        assertEquals(
                Map.of("", true, "items", Map.of("", Map.of(" ", true))),
                expectation.responseBody());
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) expectation.responseBody()).put("extra", true));
        var unsafeKeyFailure = assertThrows(
                IllegalArgumentException.class,
                () -> expectationWithResponse(Map.of("unsafe\nkey", true)));
        assertEquals("Expected Tool call response fixture is invalid", unsafeKeyFailure.getMessage());
    }

    @Test
    void enforcesTheSerializedUtf8ResponseLimitDuringExpectationConstruction() throws Exception {
        String escapedAndNonAsciiPrefix = "\"\n가";
        int serializedPrefixBytes = 2 + 2 + 2 + 3;
        String justUnder = escapedAndNonAsciiPrefix
                + "x".repeat(MAX_RESPONSE_BYTES - serializedPrefixBytes - 1);
        String exact = escapedAndNonAsciiPrefix
                + "x".repeat(MAX_RESPONSE_BYTES - serializedPrefixBytes);
        String over = escapedAndNonAsciiPrefix
                + "x".repeat(MAX_RESPONSE_BYTES - serializedPrefixBytes + 1);
        ObjectMapper mapper = new ObjectMapper();

        assertEquals(MAX_RESPONSE_BYTES - 1, mapper.writeValueAsBytes(justUnder).length);
        assertEquals(MAX_RESPONSE_BYTES, mapper.writeValueAsBytes(exact).length);
        assertEquals(MAX_RESPONSE_BYTES + 1, mapper.writeValueAsBytes(over).length);
        assertDoesNotThrow(() -> expectationWithResponse(justUnder));
        assertDoesNotThrow(() -> expectationWithResponse(exact));
        var directFailure = assertThrows(
                IllegalArgumentException.class,
                () -> expectationWithResponse(over));
        assertEquals("Expected Tool call response fixture exceeded the size limit", directFailure.getMessage());
        assertFalse(directFailure.getMessage().contains("가"));

        Map<String, List<String>> queryThatMustNotBeCopied = new AbstractMap<>() {
            @Override
            public Set<Entry<String, List<String>>> entrySet() {
                throw new AssertionError("request fields must not be copied after an oversized response");
            }
        };
        var earlyFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new UpstreamCallExpectation(
                        "getForecast",
                        "GET",
                        "/items",
                        queryThatMustNotBeCopied,
                        Map.of(),
                        null,
                        Map.of(),
                        200,
                        "application/json",
                        over));
        assertEquals("Expected Tool call response fixture exceeded the size limit", earlyFailure.getMessage());

        ExpectedToolCall legacy = call(
                GET, "/items", List.of(), false, false, List.of(), Map.of());
        ExpectedToolCall configured = new ExpectedToolCall(
                legacy.tool(),
                legacy.arguments(),
                new ExpectedUpstreamResponse(200, "application/json", over),
                Map.of("data", true));
        var factoryFailure = assertThrows(
                IllegalArgumentException.class,
                () -> UpstreamCallExpectation.from(configured));
        assertEquals("Expected Tool call response fixture exceeded the size limit", factoryFailure.getMessage());
        assertFalse(factoryFailure.getMessage().contains("가"));
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
            assertFalse(failure.getMessage().contains("private"));
        }
    }

    private UpstreamCallExpectation expectationWithResponse(Object responseBody) {
        return new UpstreamCallExpectation(
                "getForecast",
                "GET",
                "/items",
                Map.of(),
                Map.of(),
                null,
                Map.of(),
                200,
                "application/json",
                responseBody);
    }

    private ExpectedToolCall call(
            io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod method,
            String path,
            List<ParameterBinding> bindings,
            boolean objectBody,
            boolean bodyRequired,
            List<SecretBinding> secrets,
            Map<String, Object> arguments) {
        var tool = new ToolDefinition(
                "getForecast",
                "weather_get_forecast",
                "Get a forecast.",
                List.of(),
                new HttpExecution(
                        method,
                        URI.create("https://api.example.test"),
                        path,
                        bindings,
                        objectBody,
                        bodyRequired),
                secrets,
                OutputKind.GENERIC_JSON);
        return new ExpectedToolCall(tool, arguments);
    }

    private ParameterBinding binding(
            String source,
            io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation location,
            String target) {
        return new ParameterBinding(source, location, target);
    }

    private SecretBinding secret(
            String environment,
            io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation location,
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

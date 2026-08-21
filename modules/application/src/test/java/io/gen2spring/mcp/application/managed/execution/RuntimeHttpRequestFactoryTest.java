package io.gen2spring.mcp.application.managed.execution;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.POST;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.BODY;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.HEADER;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.PATH;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeHttpRequestFactoryTest {
    private final RuntimeHttpRequestFactory factory = new RuntimeHttpRequestFactory();

    @Test
    void bindsExactPathRepeatedQueryHeadersAndRawObjectBodyNames() {
        RuntimeTool tool = tool(
                "https://api.example/v1",
                "/users/{userId}",
                List.of(
                        new ParameterBinding("user", PATH, "userId"),
                        new ParameterBinding("tags", QUERY, "tag"),
                        new ParameterBinding("requestId", HEADER, "X-Request-Id"),
                        new ParameterBinding("displayName", BODY, "display name"),
                        new ParameterBinding("score", BODY, "score")),
                true,
                true,
                Map.of("required", List.of("user", "requestId", "displayName", "score")));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("user", "a/b c");
        arguments.put("tags", new ArrayList<>(List.of("alpha beta", "x/y")));
        arguments.put("requestId", "request-1");
        arguments.put("displayName", "Seoul");
        arguments.put("score", new BigDecimal("0.10000000000000001"));

        ProviderCallRequest request = factory.create(tool, Optional.empty(), arguments);

        assertEquals(POST, request.method());
        assertEquals(
                "https://api.example/v1/users/a%2Fb%20c?tag=alpha%20beta&tag=x%2Fy",
                request.uri().toASCIIString());
        assertEquals(Map.of("X-Request-Id", List.of("request-1")), request.headers());
        assertArrayEquals(
                "{\"display name\":\"Seoul\",\"score\":0.10000000000000001}"
                        .getBytes(StandardCharsets.UTF_8),
                request.body());

        arguments.clear();
        byte[] body = request.body();
        body[0] = 0;
        assertFalse(request.body()[0] == 0);
        assertThrows(UnsupportedOperationException.class,
                () -> request.headers().put("X-New", List.of("value")));
    }

    @Test
    void combinesRelativeMetadataBaseWithTheActivationProviderTarget() {
        RuntimeTool tool = tool(
                "/v2",
                "/weather/{city}",
                List.of(new ParameterBinding("city", PATH, "city")),
                false,
                false,
                Map.of("required", List.of("city")));

        ProviderCallRequest request = factory.create(
                tool,
                Optional.of(ProviderTarget.parse("https://api.example/base/")),
                Map.of("city", "서울"));

        assertEquals(
                "https://api.example/base/v2/weather/%EC%84%9C%EC%9A%B8",
                request.uri().toASCIIString());
        assertArrayEquals(new byte[0], request.body());
    }

    @Test
    void distinguishesAbsentAndRequiredEmptyObjectBodies() {
        RuntimeTool optional = tool("https://api.example", "/items", List.of(), true, false, Map.of());
        RuntimeTool required = tool("https://api.example", "/items", List.of(), true, true, Map.of());

        assertArrayEquals(new byte[0], factory.create(optional, Optional.empty(), Map.of()).body());
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8),
                factory.create(required, Optional.empty(), Map.of()).body());
    }

    @Test
    void rejectsReservedOrDuplicateTargetsAndUnboundArgumentsWithOneSafeFailure() {
        for (RuntimeTool invalid : List.of(
                tool("https://api.example", "/items", List.of(
                        new ParameterBinding("token", HEADER, "Authorization")), false, false, Map.of()),
                tool("https://api.example", "/items", List.of(
                        new ParameterBinding("first", HEADER, "X-Request"),
                        new ParameterBinding("second", HEADER, "x-request")), false, false, Map.of()))) {
            assertInvalid(() -> factory.create(invalid, Optional.empty(), Map.of()));
        }

        RuntimeTool normal = tool(
                "https://api.example", "/items/{id}",
                List.of(new ParameterBinding("id", PATH, "id")), false, false,
                Map.of("required", List.of("id")));
        assertInvalid(() -> factory.create(normal, Optional.empty(), Map.of("other", "private-marker")));
        Map<String, Object> nullArgument = new LinkedHashMap<>();
        nullArgument.put("id", null);
        assertInvalid(() -> factory.create(normal, Optional.empty(), nullArgument));
        assertInvalid(() -> factory.create(normal, Optional.empty(), Map.of()));
        assertInvalid(() -> factory.create(normal,
                Optional.of(ProviderTarget.parse("https://override.example/")), Map.of("id", "1")));
    }

    private void assertInvalid(Runnable action) {
        RuntimeHttpRequestFactory.RuntimeRequestInvalid failure = assertThrows(
                RuntimeHttpRequestFactory.RuntimeRequestInvalid.class, action::run);
        assertEquals("Managed provider request is invalid", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains("Authorization"));
    }

    private RuntimeTool tool(
            String baseUrl,
            String path,
            List<ParameterBinding> bindings,
            boolean objectBody,
            boolean requiredBody,
            Map<String, Object> schemaExtras) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.putAll(schemaExtras);
        return new RuntimeTool(
                "operation", "managed_tool", "Managed Tool", schema,
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(POST, baseUrl, path, bindings, objectBody, requiredBody),
                null, null, null, List.of());
    }
}

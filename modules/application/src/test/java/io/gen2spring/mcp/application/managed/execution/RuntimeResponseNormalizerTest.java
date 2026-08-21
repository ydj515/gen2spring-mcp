package io.gen2spring.mcp.application.managed.execution;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeResponseNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final RuntimeResponseNormalizer normalizer = new RuntimeResponseNormalizer();

    @Test
    void preservesExactDecimalsAndAcceptsStructuredJsonMedia() throws Exception {
        ManagedToolResult result = normalizer.normalize(
                tool(null), response(200, "application/problem+json; charset=utf-8",
                        "{\"value\":0.10000000000000001}"));

        assertFalse(result.error());
        assertEquals(new BigDecimal("0.10000000000000001"), json(result).path("value").decimalValue());
        assertNull(result.category());
    }

    @Test
    void distinguishesRawEmptySuccessFromAnEmptyNormalizationPolicy() throws Exception {
        ManagedToolResult raw = normalizer.normalize(tool(null), response(204, null, ""));
        ManagedToolResult normalized = normalizer.normalize(
                tool(new ResponseNormalizationPolicy(null, null, List.of(), null, null)),
                response(204, null, ""));

        assertTrue(json(raw).isNull());
        assertEquals("{\"data\":null}", new String(normalized.json(), StandardCharsets.UTF_8));
    }

    @Test
    void createsTheTypedEnvelopeAndComparesNumericProviderCodesCanonically() throws Exception {
        var policy = new ResponseNormalizationPolicy(
                "/response/body/items", "/response/header/code", List.of(new BigDecimal("90")),
                "/response/header/message", "/response/body/total");
        ManagedToolResult result = normalizer.normalize(
                tool(policy), response(200, "application/json",
                        "{\"response\":{\"header\":{\"code\":9E+1,\"message\":\"ok\"},"
                                + "\"body\":{\"items\":[{\"id\":1}],\"total\":1}}}"));

        assertFalse(result.error());
        assertEquals("1", json(result).at("/data/0/id").asText());
        assertEquals(1, json(result).at("/page/totalCount").asInt());
        assertEquals(0, json(result).at("/provider/code").decimalValue().compareTo(new BigDecimal("90")));
    }

    @Test
    void classifiesStatusBeforeMalformedMediaAndReturnsSafeProviderErrors() throws Exception {
        ManagedToolResult server = normalizer.normalize(
                tool(null), response(503, "not a media type", "private upstream payload"));
        ManagedToolResult protocol = normalizer.normalize(
                tool(null), response(200, "text/plain", "{}"));

        assertTrue(server.error());
        assertEquals(ManagedToolResult.ErrorCategory.UPSTREAM_SERVER, server.category());
        assertEquals(503, server.httpStatus());
        assertEquals(ManagedToolResult.ErrorCategory.UPSTREAM_PROTOCOL, protocol.category());
        String errors = server + json(server).toString() + json(protocol);
        assertFalse(errors.contains("private upstream payload"));
        assertEquals("operation", json(server).at("/error/operationId").asText());
        assertTrue(json(server).at("/error/traceId").asText().matches("[a-f0-9]{32}"));
    }

    @Test
    void convertsProviderBusinessFailuresWithoutEchoingUnsafeMessages() throws Exception {
        var policy = new ResponseNormalizationPolicy(
                "/data", "/code", List.of("OK"), "/message", null);
        ManagedToolResult result = normalizer.normalize(
                tool(policy), response(200, "application/json",
                        "{\"code\":\"FAILED\",\"message\":\"Authorization token rejected\",\"data\":null}"));

        assertTrue(result.error());
        assertEquals(ManagedToolResult.ErrorCategory.PROVIDER_BUSINESS, result.category());
        assertFalse(json(result).at("/error/providerMessage").asText().contains("Authorization"));
        assertFalse(json(result).at("/error/providerMessage").asText().contains("token rejected"));
    }

    private JsonNode json(ManagedToolResult result) throws Exception {
        return JSON.readTree(result.json());
    }

    private ProviderCallResponse response(int status, String contentType, String body) {
        return new ProviderCallResponse(
                status,
                contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType)),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private RuntimeTool tool(ResponseNormalizationPolicy policy) {
        return new RuntimeTool(
                "operation", "managed_tool", "Managed Tool",
                Map.of("type", "object", "properties", Map.of(), "required", List.of()),
                "GENERIC_JSON", Map.of(),
                new RuntimeHttp(GET, "https://api.example", "/items", List.of(), false, false),
                policy, null, null, List.of());
    }
}

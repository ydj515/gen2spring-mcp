package io.gen2spring.mcp.domain.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResponseNormalizationPolicyTest {
    @Test
    void defensivelyCopiesTypedSuccessValues() {
        List<Object> values = new ArrayList<>(List.of("00", new BigDecimal("1.50"), true));
        var policy = new ResponseNormalizationPolicy(
                "/response/body/items", "/response/header/code", values,
                "/response/header/message", "/response/body/totalCount");

        values.clear();

        assertEquals(List.of("00", new BigDecimal("1.50"), true), policy.successValues());
        assertThrows(UnsupportedOperationException.class, () -> policy.successValues().add("01"));
    }

    @Test
    void canonicalizesSupportedNumericInputsAndRejectsMutableNumbers() {
        var policy = new ResponseNormalizationPolicy(null, "/code", List.of(7, 0.1d), null, null);

        assertEquals(List.of(BigInteger.valueOf(7), new BigDecimal("0.1")), policy.successValues());
        assertThrows(IllegalArgumentException.class, () -> new ResponseNormalizationPolicy(
                null, "/code", List.of(new AtomicInteger(7)), null, null));
    }

    @Test
    void compatibilityConstructorLeavesNormalizationAbsent() {
        var execution = new HttpExecution(
                HttpMethod.GET, URI.create("https://example.test"), "/weather", List.of());

        assertNull(execution.responseNormalization());
    }
}

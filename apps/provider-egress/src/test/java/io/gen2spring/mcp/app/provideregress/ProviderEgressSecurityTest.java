package io.gen2spring.mcp.app.provideregress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderEgressSecurityTest {
    @Test
    void stripsHopByHopAndCallerOwnedHostHeaders() {
        ProviderCallRequest sanitized = ProviderRequestPolicy.requireAllowed(new ProviderCallRequest(
                HttpMethod.GET,
                URI.create("https://api.example.com/resource"),
                Map.of(
                        "Accept", List.of("application/json"),
                        "Host", List.of("private.internal"),
                        "Connection", List.of("keep-alive"),
                        "Transfer-Encoding", List.of("chunked")),
                new byte[0]));

        assertEquals(Map.of("Accept", List.of("application/json")), sanitized.headers());
    }

    @Test
    void rejectsRedirectResponsesAndNonHttpTargetsWithoutLeakingValues() {
        ProviderEgressFailure redirect = assertThrows(ProviderEgressFailure.class,
                () -> ProviderResponsePolicy.requireAllowed(302, Map.of("Location", List.of("https://private.example")),
                        new byte[0]));
        assertEquals("Provider egress request failed", redirect.getMessage());
        assertFalse(redirect.toString().contains("private.example"));

        ProviderEgressFailure scheme = assertThrows(ProviderEgressFailure.class,
                () -> ProviderRequestPolicy.requireAllowed(new ProviderCallRequest(
                        HttpMethod.GET, URI.create("file://localhost/private"),
                        Map.of("Accept", List.of("application/json")), new byte[0])));
        assertEquals(null, scheme.getCause());
    }

    @Test
    void rejectsInvalidTimeoutsBeforeTransportExecution() {
        ProviderEgressController controller = new ProviderEgressController((request, timeout) -> {
            throw new AssertionError("must not execute");
        });
        assertThrows(IllegalArgumentException.class,
                () -> new io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec()
                        .encodeRequest(new ProviderCallRequest(
                                HttpMethod.GET, URI.create("https://api.example.com/resource"),
                                Map.of(), new byte[0]), Duration.ofSeconds(61)));
    }
}

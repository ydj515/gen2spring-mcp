package io.gen2spring.mcp.app.provideregress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.URI;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.hc.core5.http.ContentType;
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

        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class,
                () -> new ProviderCallRequest(
                        HttpMethod.GET, URI.create("file://localhost/private"),
                        Map.of("Accept", List.of("application/json")), new byte[0]));
        assertEquals("Provider call request is invalid", scheme.getMessage());
        assertEquals(null, scheme.getCause());

        assertThrows(ProviderEgressFailure.class,
                () -> ProviderResponsePolicy.requireAllowed(
                        200, Map.of("Content-Encoding", List.of("gzip")), new byte[] {1, 2, 3}));
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

        try (ApacheProviderTransport transport = new ApacheProviderTransport(
                new ValidatedProviderResolver(host -> new InetAddress[] {
                        InetAddress.getByName("93.184.216.34")
                }), Duration.ofSeconds(1))) {
            ProviderCallRequest request = new ProviderCallRequest(
                    HttpMethod.GET, URI.create("https://api.example.com/resource"), Map.of(), new byte[0]);
            assertThrows(ProviderEgressFailure.class, () -> transport.execute(request, null));
            assertThrows(ProviderEgressFailure.class, () -> transport.execute(request, Duration.ZERO));
            assertThrows(ProviderEgressFailure.class, () -> transport.execute(request, Duration.ofSeconds(61)));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void labelsManagedRequestBodiesAsJson() throws Exception {
        try (var entity = ApacheProviderTransport.jsonEntity("{\"city\":\"Seoul\"}"
                .getBytes(StandardCharsets.UTF_8))) {
            assertEquals(ContentType.APPLICATION_JSON.toString(), entity.getContentType());
            assertEquals("{\"city\":\"Seoul\"}", new String(entity.getContent().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void pinsMutualTlsStoreTypesAndProtocol() throws Exception {
        String yaml;
        try (var input = ProviderEgressSecurityTest.class.getResourceAsStream("/application.yml")) {
            yaml = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }

        assertFalse(yaml.contains("key-store-password:"));
        assertFalse(yaml.contains("trust-store-password:"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("key-store-type: PKCS12"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("trust-store-type: PKCS12"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("enabled-protocols: TLSv1.3"));
        org.junit.jupiter.api.Assertions.assertTrue(yaml.contains("protocol: TLS"));
    }
}

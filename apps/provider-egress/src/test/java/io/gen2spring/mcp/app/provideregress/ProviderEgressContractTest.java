package io.gen2spring.mcp.app.provideregress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ProviderEgressContractTest {
    private final ProviderEgressCodec codec = new ProviderEgressCodec();

    @Test
    void validatesEveryConnectionTimeDnsAnswer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ValidatedProviderResolver resolver = new ValidatedProviderResolver(host -> calls.getAndIncrement() == 0
                ? new InetAddress[] {InetAddress.getByName("93.184.216.34")}
                : new InetAddress[] {InetAddress.getByName("127.0.0.1")});

        assertEquals("93.184.216.34", resolver.resolve("api.example.com")[0].getHostAddress());
        assertEquals("Provider destination is not public",
                assertThrows(java.net.UnknownHostException.class,
                        () -> resolver.resolve("api.example.com")).getMessage());

        ValidatedProviderResolver mixed = new ValidatedProviderResolver(host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.1")
        });
        assertThrows(java.net.UnknownHostException.class, () -> mixed.resolve("api.example.com"));
    }

    @Test
    void executesOnlyCertificateAuthenticatedBoundedProviderCalls() {
        ProviderTransport transport = (request, timeout) -> {
            assertEquals(HttpMethod.POST, request.method());
            assertEquals(URI.create("https://api.example.com:443/v1/weather?q=seoul"), request.uri());
            assertEquals(List.of("application/json"), request.headers().get("Accept"));
            assertArrayEquals("{\"city\":\"Seoul\"}".getBytes(StandardCharsets.UTF_8), request.body());
            assertEquals(Duration.ofSeconds(2), timeout);
            return new ProviderCallResponse(201, Map.of("Content-Type", List.of("application/json")),
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        };
        ProviderEgressController controller = new ProviderEgressController(transport);
        byte[] wire = codec.encodeRequest(new ProviderCallRequest(
                HttpMethod.POST,
                URI.create("https://api.example.com:443/v1/weather?q=seoul"),
                Map.of("Accept", List.of("application/json")),
                "{\"city\":\"Seoul\"}".getBytes(StandardCharsets.UTF_8)), Duration.ofSeconds(2));

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        unauthenticated.setContent(wire);
        assertThrows(ProviderEgressFailure.class, () -> controller.call(unauthenticated));

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setContent(wire);
        authenticated.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {org.mockito.Mockito.mock(X509Certificate.class)});
        byte[] response = controller.call(authenticated).getBody();

        ProviderCallResponse decoded = codec.decodeResponse(response);
        assertEquals(201, decoded.status());
        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), decoded.body());
    }

    @Test
    void rejectsInvalidPortsRedirectsAndOversizedMessagesWithFixedFailures() {
        ProviderEgressController controller = new ProviderEgressController((request, timeout) -> {
            throw new AssertionError("transport must not execute");
        });
        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {org.mockito.Mockito.mock(X509Certificate.class)});

        byte[] invalidPort = codec.encodeRequest(new ProviderCallRequest(
                HttpMethod.GET, URI.create("https://api.example.com:8443/private"),
                Map.of("Accept", List.of("application/json")), new byte[0]), Duration.ofSeconds(1));
        ProviderEgressFailure portFailure = assertThrows(
                ProviderEgressFailure.class, () -> {
                    authenticated.setContent(invalidPort);
                    controller.call(authenticated);
                });
        assertEquals("Provider egress request failed", portFailure.getMessage());
        assertEquals(null, portFailure.getCause());

        assertThrows(ProviderEgressFailure.class, () -> {
            authenticated.setContent(new byte[ProviderEgressCodec.MAX_WIRE_BYTES + 1]);
            controller.call(authenticated);
        });
    }
}

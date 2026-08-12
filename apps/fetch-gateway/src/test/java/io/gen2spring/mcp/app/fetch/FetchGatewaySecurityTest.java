package io.gen2spring.mcp.app.fetch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class FetchGatewaySecurityTest {
    @Test
    void validatesEveryDnsAnswerAndReturnsOnlyTheValidatedSnapshot() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        InetAddress[] first = {InetAddress.getByName("93.184.216.34")};
        ValidatedDnsResolver resolver = new ValidatedDnsResolver(host -> {
            if (resolutions.getAndIncrement() == 0) {
                return first;
            }
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        });

        InetAddress[] validated = resolver.resolve("api.example.com");
        first[0] = InetAddress.getByName("127.0.0.1");

        assertEquals("93.184.216.34", validated[0].getHostAddress());
        UnknownHostException rebound = assertThrows(
                UnknownHostException.class,
                () -> resolver.resolve("api.example.com"));
        assertEquals("Import destination is not public", rebound.getMessage());

        ValidatedDnsResolver mixed = new ValidatedDnsResolver(host -> new InetAddress[] {
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("10.0.0.1")
        });
        assertThrows(UnknownHostException.class, () -> mixed.resolve("mixed.example.com"));

        assertEquals(
                "93.184.216.34",
                new ValidatedDnsResolver(host -> new InetAddress[] {InetAddress.getByName(host)})
                        .resolve("93.184.216.34")[0]
                        .getHostAddress());
        assertThrows(
                UnknownHostException.class,
                () -> new ValidatedDnsResolver(host -> new InetAddress[] {InetAddress.getByName(host)})
                        .resolve("127.0.0.1"));
    }

    @Test
    void followsOnlyThreeManuallyValidatedRedirects() {
        ScriptedTransport transport = new ScriptedTransport(
                redirect("https://second.example.com/openapi.json"),
                redirect("https://third.example.com/openapi.json"),
                redirect("https://fourth.example.com/openapi.json"),
                success("openapi: 3.1.0\n"));
        BoundedFetcher fetcher = new BoundedFetcher(transport, 64, 64, 3, Duration.ofSeconds(30));

        BoundedFetcher.FetchResult result = fetcher.fetch(
                ImportTarget.parse("https://first.example.com/openapi.json"));

        assertEquals(4, transport.requests.size());
        assertEquals("https://fourth.example.com/openapi.json", transport.requests.getLast());
        assertArrayEquals("openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8), result.body());

        ScriptedTransport tooMany = new ScriptedTransport(
                redirect("https://second.example.com/openapi.json"),
                redirect("https://third.example.com/openapi.json"),
                redirect("https://fourth.example.com/openapi.json"),
                redirect("https://fifth.example.com/openapi.json"));
        assertFetchFailure(() -> new BoundedFetcher(
                        tooMany, 64, 64, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://first.example.com/openapi.json")));

        ScriptedTransport privateRedirect = new ScriptedTransport(
                redirect("https://metadata.google.internal/openapi.json"));
        assertFetchFailure(() -> new BoundedFetcher(
                        privateRedirect, 64, 64, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://first.example.com/openapi.json")));
    }

    @Test
    void boundsWireDecodedAndHeaderBytesIndependently() throws Exception {
        byte[] expanded = "x".repeat(65).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(expanded);

        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of("content-type", List.of("application/yaml")),
                new ByteArrayInputStream(new byte[65])), 64, 128));
        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of(
                        "content-type", List.of("application/yaml"),
                        "content-encoding", List.of("gzip")),
                new ByteArrayInputStream(compressed)), 128, 64));
        assertFetchFailure(() -> fetch(new FetchTransport.Response(
                200,
                Map.of("x-private", List.of("x".repeat(16_385))),
                new ByteArrayInputStream(new byte[0])), 128, 128));
    }

    @Test
    void returnsFixedFailuresWithoutRetainingTargetsOrBodies() {
        ScriptedTransport transport = new ScriptedTransport(new FetchTransport.Response(
                500,
                Map.of("content-type", List.of("text/plain")),
                new ByteArrayInputStream("private response body".getBytes(StandardCharsets.UTF_8))));

        FetchFailure failure = assertThrows(FetchFailure.class, () -> new BoundedFetcher(
                        transport, 128, 128, 3, Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://private-marker.example.com/openapi.json")));

        assertEquals("URL import fetch failed", failure.getMessage());
        assertEquals(null, failure.getCause());
        assertFalse(failure.toString().contains("private-marker"));
        assertFalse(failure.toString().contains("private response body"));
    }

    @Test
    void enforcesTheTotalTimeoutBeforeTransportExecution() {
        AtomicInteger executions = new AtomicInteger();
        BoundedFetcher fetcher = new BoundedFetcher(
                (target, timeout) -> {
                    executions.incrementAndGet();
                    return success("openapi: 3.0.3\n");
                },
                128,
                128,
                3,
                Duration.ofNanos(1));

        assertFetchFailure(() -> fetcher.fetch(
                ImportTarget.parse("https://api.example.com/openapi.yaml")));
        assertEquals(0, executions.get());
    }

    @Test
    void usesStrictTlsHostnameVerification() throws Exception {
        X509Certificate certificate = org.mockito.Mockito.mock(X509Certificate.class);
        org.mockito.Mockito.when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "other.example.com")));

        assertThrows(
                SSLPeerUnverifiedException.class,
                () -> ApacheFetchTransport.productionHostnameVerifier()
                        .verify("api.example.com", certificate));
    }

    @Test
    @SuppressWarnings("deprecation")
    void closesTheResponseWhenEntityStreamingCannotStart() throws Exception {
        CloseableHttpClient client = org.mockito.Mockito.mock(CloseableHttpClient.class);
        CloseableHttpResponse response = org.mockito.Mockito.mock(CloseableHttpResponse.class);
        HttpEntity entity = org.mockito.Mockito.mock(HttpEntity.class);
        org.mockito.Mockito.when(client.execute(org.mockito.ArgumentMatchers.any(HttpGet.class)))
                .thenReturn(response);
        org.mockito.Mockito.when(response.getEntity()).thenReturn(entity);
        org.mockito.Mockito.when(entity.getContent()).thenThrow(new IOException("private marker"));
        ApacheFetchTransport transport = new ApacheFetchTransport(client);

        assertFetchFailure(() -> transport.execute(
                ImportTarget.parse("https://api.example.com/openapi.yaml"),
                Duration.ofSeconds(1)));

        org.mockito.Mockito.verify(response).close();
    }

    @Test
    void controllerRequiresAContainerVerifiedClientCertificate() {
        FetchController controller = new FetchController(new BoundedFetcher(
                new ScriptedTransport(success("openapi: 3.1.0\n")),
                128,
                128,
                3,
                Duration.ofSeconds(30)));
        MockHttpServletRequest missingCertificate = new MockHttpServletRequest();

        assertThrows(FetchFailure.class, () -> controller.fetch(null, missingCertificate));
        assertThrows(FetchFailure.class, () -> controller.fetch(
                new FetchController.FetchRequest("https://api.example.com/openapi.yaml"),
                missingCertificate));

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {org.mockito.Mockito.mock(X509Certificate.class)});
        var response = controller.fetch(
                new FetchController.FetchRequest("https://api.example.com/openapi.yaml"),
                authenticated);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/yaml", response.getHeaders().getContentType().toString());
    }

    private BoundedFetcher.FetchResult fetch(
            FetchTransport.Response response,
            int maxWireBytes,
            int maxDecodedBytes) {
        return new BoundedFetcher(
                        new ScriptedTransport(response),
                        maxWireBytes,
                        maxDecodedBytes,
                        3,
                        Duration.ofSeconds(30))
                .fetch(ImportTarget.parse("https://api.example.com/openapi.json"));
    }

    private FetchTransport.Response redirect(String location) {
        return new FetchTransport.Response(
                302,
                Map.of("location", List.of(location)),
                new ByteArrayInputStream(new byte[0]));
    }

    private FetchTransport.Response success(String value) {
        return new FetchTransport.Response(
                200,
                Map.of("content-type", List.of("application/yaml")),
                new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value);
        }
        return output.toByteArray();
    }

    private void assertFetchFailure(Runnable action) {
        FetchFailure failure = assertThrows(FetchFailure.class, action::run);
        assertEquals("URL import fetch failed", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    private static final class ScriptedTransport implements FetchTransport {
        private final Queue<Response> responses;
        private final List<String> requests = new java.util.ArrayList<>();

        private ScriptedTransport(Response... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response execute(ImportTarget target, Duration timeout) {
            requests.add(target.uri().toString());
            if (timeout.isZero() || timeout.isNegative() || responses.isEmpty()) {
                throw new IllegalStateException("scripted transport failed");
            }
            return responses.remove();
        }
    }
}

package io.gen2spring.mcp.app.fetch.infrastructure.client.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.fetch.application.fetch.FetchFailure;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;

final class FetchClientSecurityTest {
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
    void usesStrictTlsHostnameVerification() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, "other.example.com")));

        assertThrows(
                SSLPeerUnverifiedException.class,
                () -> ApacheFetchTransport.productionHostnameVerifier()
                        .verify("api.example.com", certificate));
    }

    @Test
    @SuppressWarnings("deprecation")
    void closesTheResponseWhenEntityStreamingCannotStart() throws Exception {
        CloseableHttpClient client = mock(CloseableHttpClient.class);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        HttpEntity entity = mock(HttpEntity.class);
        when(client.execute(any(HttpGet.class)))
                .thenReturn(response);
        when(response.getEntity()).thenReturn(entity);
        when(entity.getContent()).thenThrow(new IOException("private marker"));
        ApacheFetchTransport transport = new ApacheFetchTransport(client);

        assertFetchFailure(() -> transport.execute(
                ImportTarget.parse("https://api.example.com/openapi.yaml"),
                Duration.ofSeconds(1)));

        verify(response).close();
    }

    private void assertFetchFailure(Runnable action) {
        FetchFailure failure = assertThrows(FetchFailure.class, action::run);
        assertEquals("URL import fetch failed", failure.getMessage());
        assertEquals(null, failure.getCause());
    }
}

package io.gen2spring.mcp.app.fetch.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.fetch.fetching.BoundedFetcher;
import io.gen2spring.mcp.app.fetch.fetching.FetchFailure;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

final class FetchControllerTest {
    @Test
    void requiresAContainerVerifiedClientCertificate() {
        BoundedFetcher fetcher = mock(BoundedFetcher.class);
        when(fetcher.fetch(any())).thenReturn(new BoundedFetcher.FetchResult(
                200,
                "application/yaml",
                "openapi: 3.1.0\n".getBytes(StandardCharsets.UTF_8)));
        FetchController controller = new FetchController(fetcher);
        MockHttpServletRequest missingCertificate = new MockHttpServletRequest();

        assertThrows(FetchFailure.class, () -> controller.fetch(null, missingCertificate));
        assertThrows(FetchFailure.class, () -> controller.fetch(
                new FetchController.FetchRequest("https://api.example.com/openapi.yaml"),
                missingCertificate));

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {mock(X509Certificate.class)});
        var response = controller.fetch(
                new FetchController.FetchRequest("https://api.example.com/openapi.yaml"),
                authenticated);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/yaml", response.getHeaders().getContentType().toString());
    }
}

package io.gen2spring.mcp.app.fetch.presentation.fetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.app.fetch.application.fetch.BoundedFetcher;
import io.gen2spring.mcp.app.fetch.application.fetch.FetchFailure;
import io.gen2spring.mcp.app.fetch.presentation.fetch.request.FetchRequest;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class FetchControllerTest {
    @Test
    void mapsAMissingRequestBodyToTheFixedRejectedResponse() throws Exception {
        FetchController controller = new FetchController(mock(BoundedFetcher.class));

        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new FetchExceptionHandler())
                .build()
                .perform(post("/internal/fetch").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FETCH_REJECTED"));
    }

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
                new FetchRequest("https://api.example.com/openapi.yaml"),
                missingCertificate));

        MockHttpServletRequest authenticated = new MockHttpServletRequest();
        authenticated.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {mock(X509Certificate.class)});
        var response = controller.fetch(
                new FetchRequest("https://api.example.com/openapi.yaml"),
                authenticated);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("application/yaml", response.getHeaders().getContentType().toString());
    }
}

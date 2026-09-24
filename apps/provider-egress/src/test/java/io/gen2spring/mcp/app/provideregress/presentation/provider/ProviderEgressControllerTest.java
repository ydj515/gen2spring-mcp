package io.gen2spring.mcp.app.provideregress.presentation.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressFailure;
import io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressService;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

final class ProviderEgressControllerTest {
    @Test
    void mapsRejectedRequestsToTheFixedErrorContract() throws Exception {
        MockMvcBuilders.standaloneSetup(new ProviderEgressController(mock(ProviderEgressService.class)))
                .setControllerAdvice(new ProviderEgressExceptionHandler())
                .build()
                .perform(post("/internal/provider-call").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROVIDER_EGRESS_REJECTED"));
    }

    @Test
    void requiresACertificateAndBoundsTheWirePayload() {
        ProviderEgressService service = mock(ProviderEgressService.class);
        byte[] wire = {1, 2, 3};
        byte[] response = {4, 5, 6};
        when(service.execute(wire)).thenReturn(response);
        ProviderEgressController controller = new ProviderEgressController(service);

        MockHttpServletRequest unauthenticated = new MockHttpServletRequest();
        unauthenticated.setContent(wire);
        assertThrows(ProviderEgressFailure.class, () -> controller.call(unauthenticated));

        MockHttpServletRequest authenticated = authenticated(wire);
        assertArrayEquals(response, controller.call(authenticated).getBody());
        verify(service).execute(wire);

        MockHttpServletRequest oversized = authenticated(new byte[ProviderEgressService.MAX_REQUEST_BYTES + 1]);
        assertThrows(ProviderEgressFailure.class, () -> controller.call(oversized));
        verifyNoMoreInteractions(service);
    }

    private MockHttpServletRequest authenticated(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent(content);
        request.setAttribute(
                "jakarta.servlet.request.X509Certificate",
                new X509Certificate[] {mock(X509Certificate.class)});
        return request;
    }
}

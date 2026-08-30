package io.gen2spring.mcp.app.provideregress.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import io.gen2spring.mcp.app.provideregress.egress.ProviderEgressFailure;
import io.gen2spring.mcp.app.provideregress.egress.ProviderEgressService;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

final class ProviderEgressControllerTest {
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

        MockHttpServletRequest oversized = authenticated(new byte[ProviderEgressCodec.MAX_WIRE_BYTES + 1]);
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

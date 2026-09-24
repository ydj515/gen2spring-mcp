package io.gen2spring.mcp.app.provideregress.presentation.provider;

import io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressFailure;
import io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.cert.X509Certificate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProviderEgressController {
    private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";
    private final ProviderEgressService service;

    ProviderEgressController(ProviderEgressService service) {
        this.service = service;
    }

    @PostMapping(path = "/internal/provider-call", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> call(HttpServletRequest servletRequest) {
        requireCertificate(servletRequest);
        try {
            if (servletRequest.getContentLengthLong() > ProviderEgressService.MAX_REQUEST_BYTES) {
                throw new ProviderEgressFailure();
            }
            byte[] wire = servletRequest.getInputStream().readNBytes(ProviderEgressService.MAX_REQUEST_BYTES + 1);
            if (wire.length > ProviderEgressService.MAX_REQUEST_BYTES) throw new ProviderEgressFailure();
            byte[] response = service.execute(wire);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
        } catch (ProviderEgressFailure failure) {
            throw failure;
        } catch (Error fatal) {
            throw fatal;
        } catch (IOException | RuntimeException failure) {
            throw new ProviderEgressFailure();
        }
    }

    private void requireCertificate(HttpServletRequest request) {
        Object value = request.getAttribute(CERTIFICATES);
        if (!(value instanceof X509Certificate[] certificates) || certificates.length == 0) {
            throw new ProviderEgressFailure();
        }
    }

}

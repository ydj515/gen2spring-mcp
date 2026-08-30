package io.gen2spring.mcp.app.provideregress.api;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import io.gen2spring.mcp.app.provideregress.egress.ProviderEgressFailure;
import io.gen2spring.mcp.app.provideregress.egress.ProviderEgressService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
            if (servletRequest.getContentLengthLong() > ProviderEgressCodec.MAX_WIRE_BYTES) {
                throw new ProviderEgressFailure();
            }
            byte[] wire = servletRequest.getInputStream().readNBytes(ProviderEgressCodec.MAX_WIRE_BYTES + 1);
            if (wire.length > ProviderEgressCodec.MAX_WIRE_BYTES) throw new ProviderEgressFailure();
            byte[] response = service.execute(wire);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
        } catch (ProviderEgressFailure failure) {
            throw failure;
        } catch (Error fatal) {
            throw fatal;
        } catch (java.io.IOException | RuntimeException failure) {
            throw new ProviderEgressFailure();
        }
    }

    @ExceptionHandler({ProviderEgressFailure.class, IllegalArgumentException.class})
    ResponseEntity<FailureBody> failure() {
        return ResponseEntity.unprocessableEntity().contentType(MediaType.APPLICATION_JSON)
                .body(new FailureBody("PROVIDER_EGRESS_REJECTED", "Provider egress request failed"));
    }

    private void requireCertificate(HttpServletRequest request) {
        Object value = request.getAttribute(CERTIFICATES);
        if (!(value instanceof X509Certificate[] certificates) || certificates.length == 0) {
            throw new ProviderEgressFailure();
        }
    }

    record FailureBody(String code, String message) {}
}

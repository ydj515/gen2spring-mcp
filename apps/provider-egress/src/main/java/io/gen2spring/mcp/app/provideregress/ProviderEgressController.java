package io.gen2spring.mcp.app.provideregress;

import io.gen2spring.mcp.adapter.provideregress.ProviderEgressCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ProviderEgressController {
    private static final String CERTIFICATES = "jakarta.servlet.request.X509Certificate";
    private final ProviderTransport transport;
    private final ProviderEgressCodec codec = new ProviderEgressCodec();

    ProviderEgressController(ProviderTransport transport) {
        this.transport = transport;
    }

    @PostMapping(path = "/internal/provider-call", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> call(@RequestBody byte[] wire, HttpServletRequest servletRequest) {
        requireCertificate(servletRequest);
        try {
            ProviderEgressCodec.DecodedProviderCall decoded = codec.decodeRequest(wire);
            byte[] response = codec.encodeResponse(transport.execute(
                    ProviderRequestPolicy.requireAllowed(decoded.request()), decoded.timeout()));
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
        } catch (ProviderEgressFailure failure) {
            throw failure;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
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

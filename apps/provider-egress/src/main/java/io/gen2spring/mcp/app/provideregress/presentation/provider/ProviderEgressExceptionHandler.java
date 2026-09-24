package io.gen2spring.mcp.app.provideregress.presentation.provider;

import io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressFailure;
import io.gen2spring.mcp.app.provideregress.presentation.provider.response.ProviderEgressFailureResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ProviderEgressController.class)
final class ProviderEgressExceptionHandler {
    @ExceptionHandler({ProviderEgressFailure.class, IllegalArgumentException.class})
    ResponseEntity<ProviderEgressFailureResponse> failure() {
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ProviderEgressFailureResponse(
                        "PROVIDER_EGRESS_REJECTED", "Provider egress request failed"));
    }
}

package io.gen2spring.mcp.app.fetch.presentation.fetch;

import io.gen2spring.mcp.app.fetch.application.fetch.FetchFailure;
import io.gen2spring.mcp.app.fetch.presentation.fetch.response.FetchFailureResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = FetchController.class)
final class FetchExceptionHandler {
    @ExceptionHandler({FetchFailure.class, IllegalArgumentException.class})
    ResponseEntity<FetchFailureResponse> failure() {
        return ResponseEntity.unprocessableEntity()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FetchFailureResponse("FETCH_REJECTED", "URL import fetch failed"));
    }
}

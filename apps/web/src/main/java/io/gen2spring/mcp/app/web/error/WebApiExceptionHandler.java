package io.gen2spring.mcp.app.web.error;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class WebApiExceptionHandler {
    private final WebErrorMapper errors;
    private final WebErrorResponseWriter writer;

    WebApiExceptionHandler(WebErrorMapper errors, WebErrorResponseWriter writer) {
        this.errors = Objects.requireNonNull(errors, "errors");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ObjectNode> failure(Exception failure) {
        WebErrorMapper.WebFailure mapped = errors.map(failure);
        return ResponseEntity.status(mapped.status()).body(writer.body(mapped));
    }
}

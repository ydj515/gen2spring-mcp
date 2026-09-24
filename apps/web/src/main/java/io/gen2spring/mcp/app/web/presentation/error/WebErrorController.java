package io.gen2spring.mcp.app.web.presentation.error;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class WebErrorController implements ErrorController {
    private final WebErrorMapper errors;
    private final WebErrorResponseWriter writer;

    WebErrorController(WebErrorMapper errors, WebErrorResponseWriter writer) {
        this.errors = Objects.requireNonNull(errors, "errors");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @RequestMapping("/error")
    ResponseEntity<ObjectNode> error(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = value instanceof Number number
                && number.intValue() >= 400 && number.intValue() <= 599
                ? number.intValue()
                : 500;
        WebErrorMapper.WebFailure failure = errors.httpStatus(status);
        return ResponseEntity.status(failure.status()).body(writer.body(failure));
    }
}

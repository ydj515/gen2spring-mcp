package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.application.validation.ValidationReport;
import io.gen2spring.mcp.application.validation.ValidationRequest;
import java.util.Objects;

public interface GeneratedProjectValidator {
    ValidationReport validate(ValidationRequest request);

    default ValidationReport validate(ValidationRequest request, GenerationProgressListener listener) {
        Objects.requireNonNull(listener, "listener");
        return validate(request);
    }
}

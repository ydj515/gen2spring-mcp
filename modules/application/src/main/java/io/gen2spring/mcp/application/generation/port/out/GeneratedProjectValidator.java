package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.validation.ValidationReport;
import io.gen2spring.mcp.application.generation.validation.ValidationRequest;
import java.util.Objects;

public interface GeneratedProjectValidator {
    ValidationReport validate(ValidationRequest request);

    default ValidationReport validate(ValidationRequest request, GenerationProgressListener listener) {
        Objects.requireNonNull(listener, "listener");
        return validate(request);
    }
}

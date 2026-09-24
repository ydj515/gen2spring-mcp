package io.gen2spring.mcp.adapter.validation;

import io.gen2spring.mcp.application.generation.port.out.GeneratedProjectValidator;
import io.gen2spring.mcp.application.generation.port.out.GenerationProgressListener;
import io.gen2spring.mcp.application.generation.validation.ValidationReport;
import io.gen2spring.mcp.application.generation.validation.ValidationRequest;
import java.util.Objects;

public final class McpProjectValidator implements GeneratedProjectValidator {
    private final GeneratedProjectValidator delegate;

    public McpProjectValidator() {
        this(new GradleMcpProjectValidator());
    }

    McpProjectValidator(GeneratedProjectValidator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ValidationReport validate(ValidationRequest request) {
        return delegate.validate(request);
    }

    @Override
    public ValidationReport validate(
            ValidationRequest request,
            GenerationProgressListener listener) {
        return delegate.validate(request, listener);
    }
}

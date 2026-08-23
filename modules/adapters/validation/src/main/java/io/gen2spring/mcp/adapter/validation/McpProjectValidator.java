package io.gen2spring.mcp.adapter.validation;

import io.gen2spring.mcp.application.port.outbound.GeneratedProjectValidator;
import io.gen2spring.mcp.application.usecase.GenerationProgressListener;
import io.gen2spring.mcp.application.validation.ValidationReport;
import io.gen2spring.mcp.application.validation.ValidationRequest;
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

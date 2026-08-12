package io.gen2spring.mcp.application.usecase;

import io.gen2spring.mcp.application.validation.ValidationStatus;
import java.nio.file.Path;

public record GenerationOutcome(
        Path projectRoot,
        Path archive,
        ValidationStatus validationStatus,
        String sourceChecksum) {}

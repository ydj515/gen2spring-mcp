package io.gen2spring.mcp.application.generation.usecase;

import io.gen2spring.mcp.application.generation.validation.ValidationStatus;
import java.nio.file.Path;

public record GenerationOutcome(
        Path projectRoot,
        Path archive,
        ValidationStatus validationStatus,
        String sourceChecksum) {}

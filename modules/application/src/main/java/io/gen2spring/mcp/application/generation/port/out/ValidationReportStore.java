package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.validation.ValidationReport;
import java.nio.file.Path;
import java.util.Collection;

public interface ValidationReportStore {
    String REPORT_FILE = "VALIDATION_REPORT.json";

    Path write(Path projectRoot, ValidationReport report, Collection<String> configuredSecretNames);

    Path replaceAfterPipelineFailure(
            Path projectRoot,
            ValidationReport report,
            Collection<String> configuredSecretNames);
}

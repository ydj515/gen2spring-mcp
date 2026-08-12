package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.application.validation.ValidationReport;
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

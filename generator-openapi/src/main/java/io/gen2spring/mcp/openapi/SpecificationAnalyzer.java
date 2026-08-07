package io.gen2spring.mcp.openapi;

import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import java.nio.file.Path;

public interface SpecificationAnalyzer {
    AnalysisResult analyze(Path specification, long maxBytes);

    record AnalysisResult(OpenApiDocument document, byte[] originalSpecification) {}
}

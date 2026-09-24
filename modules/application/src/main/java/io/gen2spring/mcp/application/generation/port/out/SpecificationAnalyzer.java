package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import java.nio.file.Path;

public interface SpecificationAnalyzer {
    AnalysisResult analyze(Path specification, long maxBytes);

    record AnalysisResult(OpenApiDocument document, byte[] originalSpecification) {}
}

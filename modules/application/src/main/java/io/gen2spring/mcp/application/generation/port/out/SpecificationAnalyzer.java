package io.gen2spring.mcp.application.generation.port.out;

import io.gen2spring.mcp.application.generation.analysis.AnalysisResult;
import java.nio.file.Path;

public interface SpecificationAnalyzer {
    AnalysisResult analyze(Path specification, long maxBytes);

}

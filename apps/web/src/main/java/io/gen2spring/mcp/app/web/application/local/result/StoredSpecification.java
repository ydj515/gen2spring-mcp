package io.gen2spring.mcp.app.web.application.local.result;

import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import java.nio.file.Path;

public record StoredSpecification(String id, String displayName, Path path, long size,
        SpecificationAnalyzer.AnalysisResult analysis) {}

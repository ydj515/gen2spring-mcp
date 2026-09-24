package io.gen2spring.mcp.app.web.application.local.result;

import io.gen2spring.mcp.application.generation.analysis.AnalysisResult;
import java.nio.file.Path;

public record StoredSpecification(String id, String displayName, Path path, long size,
        AnalysisResult analysis) {}

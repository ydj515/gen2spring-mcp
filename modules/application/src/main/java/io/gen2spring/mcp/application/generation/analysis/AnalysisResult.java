package io.gen2spring.mcp.application.generation.analysis;

import io.gen2spring.mcp.domain.specification.OpenApiDocument;

public record AnalysisResult(OpenApiDocument document, byte[] originalSpecification) {}

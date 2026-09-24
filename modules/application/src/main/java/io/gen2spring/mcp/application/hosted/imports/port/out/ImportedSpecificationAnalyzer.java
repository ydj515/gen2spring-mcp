package io.gen2spring.mcp.application.hosted.imports.port.out;

/** Analyzes fetched bytes without exposing the parser's filesystem requirements. */
@FunctionalInterface
public interface ImportedSpecificationAnalyzer {
    byte[] analyze(byte[] source, String mediaType, int maxBytes);
}

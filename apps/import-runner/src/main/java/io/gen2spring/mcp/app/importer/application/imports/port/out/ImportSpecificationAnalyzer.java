package io.gen2spring.mcp.app.importer.application.imports.port.out;

public interface ImportSpecificationAnalyzer {
    byte[] analyze(byte[] source, String mediaType);
}

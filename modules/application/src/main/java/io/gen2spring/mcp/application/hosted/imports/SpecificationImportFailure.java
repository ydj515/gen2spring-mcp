package io.gen2spring.mcp.application.hosted.imports;

public final class SpecificationImportFailure extends RuntimeException {
    public SpecificationImportFailure() {
        super("Hosted specification import failed", null, false, false);
    }
}

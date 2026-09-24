package io.gen2spring.mcp.app.importer.application.imports;

public final class ImportRunnerFailure extends RuntimeException {
    public ImportRunnerFailure() {
        super("Specification import runner failed", null, false, false);
    }
}

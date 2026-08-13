package io.gen2spring.mcp.application.hosted.imports;

public final class ImportTargetProtectionFailure extends RuntimeException {
    public ImportTargetProtectionFailure(Throwable cause) {
        super("Import target protection failed", null, false, false);
    }
}

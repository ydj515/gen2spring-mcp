package io.gen2spring.mcp.app.web.application.local.exception;

public final class LocalConfigurationFailure extends RuntimeException {
    public LocalConfigurationFailure(String safeMessage, Throwable cause) {
        super(safeMessage, cause);
    }
}

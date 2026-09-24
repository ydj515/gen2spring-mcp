package io.gen2spring.mcp.app.cli.application.exception;

public final class CliConfigurationException extends RuntimeException {
    public CliConfigurationException(String message) {
        super(message);
    }

    public CliConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

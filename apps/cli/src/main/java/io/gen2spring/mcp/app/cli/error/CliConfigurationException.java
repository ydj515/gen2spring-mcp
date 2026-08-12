package io.gen2spring.mcp.app.cli.error;

public final class CliConfigurationException extends RuntimeException {
    public CliConfigurationException(String message) {
        super(message);
    }

    public CliConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.gen2spring.mcp.cli;

final class CliConfigurationException extends RuntimeException {
    CliConfigurationException(String message) {
        super(message);
    }

    CliConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

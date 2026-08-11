package io.gen2spring.mcp.application;

public final class GenerationConfigurationException extends RuntimeException {
    public GenerationConfigurationException(String message) {
        super(message);
    }

    public GenerationConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

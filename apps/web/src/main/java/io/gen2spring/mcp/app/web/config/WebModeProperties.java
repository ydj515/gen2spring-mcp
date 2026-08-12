package io.gen2spring.mcp.app.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gen2spring")
public record WebModeProperties(Mode mode) {
    public WebModeProperties {
        if (mode == null) {
            throw new IllegalArgumentException("Web runtime mode is invalid");
        }
    }

    public enum Mode {
        LOCAL,
        HOSTED
    }
}

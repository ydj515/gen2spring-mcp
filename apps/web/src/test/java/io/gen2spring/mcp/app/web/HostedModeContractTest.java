package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.app.web.config.WebModeProperties;
import org.junit.jupiter.api.Test;

class HostedModeContractTest {
    @Test
    void supportsOnlyExplicitLocalAndHostedModes() {
        assertEquals(WebModeProperties.Mode.LOCAL, new WebModeProperties(WebModeProperties.Mode.LOCAL).mode());
        assertEquals(WebModeProperties.Mode.HOSTED, new WebModeProperties(WebModeProperties.Mode.HOSTED).mode());
        assertEquals("Web runtime mode is invalid", assertThrows(
                IllegalArgumentException.class, () -> new WebModeProperties(null)).getMessage());
    }
}

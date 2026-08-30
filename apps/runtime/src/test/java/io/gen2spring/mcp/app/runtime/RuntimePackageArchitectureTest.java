package io.gen2spring.mcp.app.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RuntimePackageArchitectureTest {
    @Test
    void separatesConfigurationSecurityAndServerResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeConfiguration");
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeProperties");
        assertLoadable("io.gen2spring.mcp.app.runtime.security.RuntimeBearerFilter");
        assertLoadable("io.gen2spring.mcp.app.runtime.server.ManagedMcpRouter");
        assertLoadable("io.gen2spring.mcp.app.runtime.server.RuntimeServerHandleRegistry");

        assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeConfiguration");
        assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeBearerFilter");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

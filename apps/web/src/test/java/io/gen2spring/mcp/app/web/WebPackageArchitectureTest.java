package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class WebPackageArchitectureTest {
    @Test
    void separatesPageApiJobSecurityConfigurationAndErrorResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.web.page.EditorController");
        assertLoadable("io.gen2spring.mcp.app.web.api.SpecificationController");
        assertLoadable("io.gen2spring.mcp.app.web.job.GenerationJobManager");
        assertLoadable("io.gen2spring.mcp.app.web.job.JobWorkspace");
        assertLoadable("io.gen2spring.mcp.app.web.security.LocalRequestSecurityFilter");
        assertLoadable("io.gen2spring.mcp.app.web.config.WebRuntimeConfiguration");
        assertLoadable("io.gen2spring.mcp.app.web.error.WebErrorMapper");

        assertNotLoadable("io.gen2spring.mcp.app.web.GenerationJobManager");
        assertNotLoadable("io.gen2spring.mcp.app.web.WebErrorMapper");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

package io.gen2spring.mcp.app.worker;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class WorkerPackageArchitectureTest {
    @Test
    void separatesConfigurationAndExecutionResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerConfiguration");
        assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerProperties");
        assertLoadable("io.gen2spring.mcp.app.worker.execution.WorkerLoop");
        assertLoadable("io.gen2spring.mcp.app.worker.execution.WorkerReadiness");

        assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerConfiguration");
        assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerLoop");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

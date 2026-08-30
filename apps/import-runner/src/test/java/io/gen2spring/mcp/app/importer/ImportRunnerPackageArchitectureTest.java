package io.gen2spring.mcp.app.importer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ImportRunnerPackageArchitectureTest {
    @Test
    void separatesImportJobResponsibilitiesFromTheEntrypoint() {
        assertLoadable("io.gen2spring.mcp.app.importer.job.ImportRunner");
        assertLoadable("io.gen2spring.mcp.app.importer.job.ImportJobProtocol");
        assertLoadable("io.gen2spring.mcp.app.importer.job.ImportGatewayClient");
        assertLoadable("io.gen2spring.mcp.app.importer.job.ImportRunnerFailure");

        assertNotLoadable("io.gen2spring.mcp.app.importer.ImportRunner");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

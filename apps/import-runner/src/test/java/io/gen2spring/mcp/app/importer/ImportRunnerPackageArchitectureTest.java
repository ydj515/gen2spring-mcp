package io.gen2spring.mcp.app.importer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ImportRunnerPackageArchitectureTest {
    @Test
    void separatesImportJobResponsibilitiesFromTheEntrypoint() {
        assertLoadable("io.gen2spring.mcp.app.importer.application.imports.ImportRunner");
        assertLoadable("io.gen2spring.mcp.app.importer.application.imports.port.out.ImportGatewayClient");
        assertLoadable("io.gen2spring.mcp.app.importer.presentation.job.ImportJobProtocol");
        assertLoadable("io.gen2spring.mcp.app.importer.infrastructure.client.fetch.GatewayImportClientAdapter");
        assertLoadable("io.gen2spring.mcp.app.importer.config.ImportRunnerConfiguration");
        assertLoadable("io.gen2spring.mcp.app.importer.application.imports.ImportRunnerFailure");

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

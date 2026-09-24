package io.gen2spring.mcp.app.fetch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class FetchGatewayPackageArchitectureTest {
    @Test
    void separatesPresentationApplicationInfrastructureAndConfigurationResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.fetch.presentation.fetch.FetchController");
        assertLoadable("io.gen2spring.mcp.app.fetch.presentation.fetch.FetchExceptionHandler");
        assertLoadable("io.gen2spring.mcp.app.fetch.application.fetch.BoundedFetcher");
        assertLoadable("io.gen2spring.mcp.app.fetch.application.fetch.port.out.FetchTransport");
        assertLoadable("io.gen2spring.mcp.app.fetch.infrastructure.client.fetch.ApacheFetchTransport");
        assertLoadable("io.gen2spring.mcp.app.fetch.config.FetchGatewayConfiguration");
        assertLoadable("io.gen2spring.mcp.app.fetch.config.FetchGatewaySecurityConfiguration");

        assertNotLoadable("io.gen2spring.mcp.app.fetch.FetchController");
        assertNotLoadable("io.gen2spring.mcp.app.fetch.BoundedFetcher");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

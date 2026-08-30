package io.gen2spring.mcp.app.fetch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class FetchGatewayPackageArchitectureTest {
    @Test
    void separatesApiFetchingAndConfigurationResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.fetch.api.FetchController");
        assertLoadable("io.gen2spring.mcp.app.fetch.fetching.BoundedFetcher");
        assertLoadable("io.gen2spring.mcp.app.fetch.fetching.FetchGatewayConfiguration");
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

package io.gen2spring.mcp.app.provideregress;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ProviderEgressPackageArchitectureTest {
    @Test
    void separatesPresentationApplicationInfrastructureAndConfigurationResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.provideregress.presentation.provider.ProviderEgressController");
        assertLoadable("io.gen2spring.mcp.app.provideregress.presentation.provider.ProviderEgressExceptionHandler");
        assertLoadable("io.gen2spring.mcp.app.provideregress.application.provider.ProviderEgressService");
        assertLoadable("io.gen2spring.mcp.app.provideregress.application.provider.port.out.ProviderCallCodec");
        assertLoadable("io.gen2spring.mcp.app.provideregress.infrastructure.client.provider.ApacheProviderTransport");
        assertLoadable("io.gen2spring.mcp.app.provideregress.config.ProviderEgressConfiguration");
        assertLoadable("io.gen2spring.mcp.app.provideregress.config.ProviderEgressSecurityConfiguration");

        assertNotLoadable("io.gen2spring.mcp.app.provideregress.ProviderEgressController");
        assertNotLoadable("io.gen2spring.mcp.app.provideregress.ApacheProviderTransport");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

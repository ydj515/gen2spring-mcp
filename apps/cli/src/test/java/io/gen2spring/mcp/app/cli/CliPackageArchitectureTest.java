package io.gen2spring.mcp.app.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class CliPackageArchitectureTest {
    @Test
    void separatesCommandOutputAndErrorResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.cli.command.CliApplication");
        assertLoadable("io.gen2spring.mcp.app.cli.command.CommandLine");
        assertLoadable("io.gen2spring.mcp.app.cli.command.GenerationConfigurationReader");
        assertLoadable("io.gen2spring.mcp.app.cli.command.LocalPathBoundary");
        assertLoadable("io.gen2spring.mcp.app.cli.output.CliOutput");
        assertLoadable("io.gen2spring.mcp.app.cli.output.CliNopServiceProvider");
        assertLoadable("io.gen2spring.mcp.app.cli.error.CliConfigurationException");
        assertLoadable("io.gen2spring.mcp.app.cli.error.CliUsageException");

        assertNotLoadable("io.gen2spring.mcp.app.cli.CliApplication");
        assertNotLoadable("io.gen2spring.mcp.app.cli.LocalPathBoundary");
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

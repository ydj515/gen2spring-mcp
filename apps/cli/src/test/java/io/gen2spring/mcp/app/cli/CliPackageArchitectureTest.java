package io.gen2spring.mcp.app.cli;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CliPackageArchitectureTest {
    @Test
    void placesCliResponsibilitiesInTheirOwningLayers() {
        assertLoadable("io.gen2spring.mcp.app.cli.presentation.CliApplication");
        assertLoadable("io.gen2spring.mcp.app.cli.presentation.CommandLine");
        assertLoadable("io.gen2spring.mcp.app.cli.application.CliUseCases");
        assertLoadable("io.gen2spring.mcp.app.cli.infrastructure.file.GenerationConfigurationReader");
        assertLoadable("io.gen2spring.mcp.app.cli.infrastructure.file.LocalPathBoundary");
        assertLoadable("io.gen2spring.mcp.app.cli.infrastructure.file.LocalCliFiles");
        assertLoadable("io.gen2spring.mcp.app.cli.infrastructure.logging.CliNopServiceProvider");
        assertLoadable("io.gen2spring.mcp.app.cli.config.ApplicationFactory");

        assertNotLoadable("io.gen2spring.mcp.app.cli.command.CliApplication");
        assertNotLoadable("io.gen2spring.mcp.app.cli.command.LocalPathBoundary");
    }

    @Test
    void applicationAndPresentationKeepAdapterDependenciesOut() throws URISyntaxException {
        JavaClasses production = productionClasses();
        noClasses().that().resideInAPackage("io.gen2spring.mcp.app.cli.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.gen2spring.mcp.app.cli.presentation..",
                        "io.gen2spring.mcp.app.cli.infrastructure..",
                        "io.gen2spring.mcp.app.cli.config..",
                        "io.gen2spring.mcp.adapter..",
                        "org.springframework..")
                .check(production);
        noClasses().that().resideInAPackage("io.gen2spring.mcp.app.cli.presentation..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.gen2spring.mcp.app.cli.infrastructure..",
                        "io.gen2spring.mcp.app.cli.config..",
                        "io.gen2spring.mcp.adapter..")
                .check(production);
        noClasses().that().resideInAPackage("io.gen2spring.mcp.app.cli.infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("io.gen2spring.mcp.app.cli.config..")
                .check(production);
    }

    private JavaClasses productionClasses() throws URISyntaxException {
        Path path = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ClassFileImporter().importPath(path);
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

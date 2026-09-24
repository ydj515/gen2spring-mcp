package io.gen2spring.mcp.app.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

final class WorkerPackageArchitectureTest {
    @Test
    void separatesConfigurationAndExecutionResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerConfiguration");
        assertLoadable("io.gen2spring.mcp.app.worker.config.WorkerProperties");
        assertLoadable("io.gen2spring.mcp.app.worker.application.worker.port.in.WorkerTasks");
        assertLoadable("io.gen2spring.mcp.app.worker.application.worker.service.WorkerTaskService");
        assertLoadable("io.gen2spring.mcp.app.worker.infrastructure.scheduling.WorkerLoop");
        assertLoadable("io.gen2spring.mcp.app.worker.infrastructure.readiness.WorkerReadiness");

        assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerConfiguration");
        assertNotLoadable("io.gen2spring.mcp.app.worker.WorkerLoop");
    }

    @Test
    void applicationDoesNotDependOnAdaptersConfigurationOrSpring() {
        noClasses().that().resideInAPackage("io.gen2spring.mcp.app.worker.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.gen2spring.mcp.app.worker.infrastructure..",
                        "io.gen2spring.mcp.app.worker.config..",
                        "io.gen2spring.mcp.adapter..",
                        "org.springframework..")
                .check(new ClassFileImporter().importPackages("io.gen2spring.mcp.app.worker"));
    }

    @Test
    void infrastructureDoesNotDependOnCompositionRoot() {
        noClasses().that().resideInAPackage("io.gen2spring.mcp.app.worker.infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("io.gen2spring.mcp.app.worker.config..")
                .check(new ClassFileImporter().importPackages("io.gen2spring.mcp.app.worker"));
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

package io.gen2spring.mcp.app.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

final class RuntimePackageArchitectureTest {
    @Test
    void separatesCompositionAndMcpDeliveryResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.runtime.infrastructure.execution.BoundedManagedExecutionTasks");
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeConfiguration");
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeProperties");
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeServerConfiguration");
        assertLoadable("io.gen2spring.mcp.app.runtime.config.RuntimeSecurityConfiguration");
        assertLoadable("io.gen2spring.mcp.app.runtime.presentation.security.RuntimeBearerFilter");
        assertLoadable("io.gen2spring.mcp.app.runtime.presentation.mcp.ManagedMcpRouter");
        assertLoadable("io.gen2spring.mcp.app.runtime.presentation.mcp.RuntimeServerHandleRegistry");

        assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeConfiguration");
        assertNotLoadable("io.gen2spring.mcp.app.runtime.RuntimeBearerFilter");
        assertNotLoadable("io.gen2spring.mcp.app.runtime.server.ManagedMcpRouter");
    }

    @Test
    void deliveryDoesNotDependOnCompositionOrConcretePersistence() {
        var classes = new ClassFileImporter().importPackages("io.gen2spring.mcp.app.runtime");
        noClasses().that().resideInAPackage("..presentation..")
                .should().dependOnClassesThat().resideInAnyPackage("..app.runtime.config..", "..adapter.persistence..")
                .check(classes);
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

package io.gen2spring.mcp.app.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.gen2spring.mcp.app.web.presentation.hosted.HostedArtifactController;
import io.gen2spring.mcp.app.web.presentation.hosted.HostedJobController;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import org.junit.jupiter.api.Test;

final class WebPackageArchitectureTest {
    @Test
    void separatesPageApiJobSecurityConfigurationAndErrorResponsibilities() {
        assertLoadable("io.gen2spring.mcp.app.web.presentation.page.EditorController");
        assertLoadable("io.gen2spring.mcp.app.web.presentation.local.SpecificationController");
        assertLoadable("io.gen2spring.mcp.app.web.infrastructure.local.job.GenerationJobManager");
        assertLoadable("io.gen2spring.mcp.app.web.infrastructure.local.job.JobWorkspace");
        assertLoadable("io.gen2spring.mcp.app.web.application.hosted.service.HostedSubmissionService");
        assertLoadable("io.gen2spring.mcp.app.web.infrastructure.hosted.submission.GeneratorHostedSpecificationProcessor");
        assertLoadable("io.gen2spring.mcp.app.web.infrastructure.hosted.submission.JacksonHostedSubmissionSnapshotCodec");
        assertLoadable("io.gen2spring.mcp.app.web.presentation.security.LocalRequestSecurityFilter");
        assertLoadable("io.gen2spring.mcp.app.web.config.WebRuntimeConfiguration");
        assertLoadable("io.gen2spring.mcp.app.web.presentation.error.WebErrorMapper");

        assertNotLoadable("io.gen2spring.mcp.app.web.GenerationJobManager");
        assertNotLoadable("io.gen2spring.mcp.app.web.WebErrorMapper");
        assertNotLoadable("io.gen2spring.mcp.app.web.infrastructure.hosted.submission.HostedSubmissionService");
    }

    @Test
    void applicationAndInfrastructureDependOnlyInward() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.gen2spring.mcp.app.web");
        noClasses().that().resideInAPackage("..app.web.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..app.web.presentation..", "..app.web.infrastructure..", "..app.web.config..",
                        "org.springframework..", "jakarta.servlet..", "com.fasterxml.jackson..")
                .check(classes);
        noClasses().that().resideInAPackage("..app.web.infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..app.web.presentation..", "..app.web.config..")
                .check(classes);
    }

    @Test
    void deliveryUsesApplicationContractsInsteadOfConcreteAdapters() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.gen2spring.mcp.app.web");
        noClasses().that().resideInAnyPackage(
                        "..app.web.presentation.local..", "..app.web.presentation.hosted..",
                        "..app.web.presentation.page..", "..app.web.presentation.stream..",
                        "..app.web.presentation.security..", "..app.web.presentation.error..")
                .should().dependOnClassesThat().resideInAPackage("..app.web.infrastructure..")
                .check(classes);
    }

    @Test
    void hostedDownloadAccountAndResourceDeliveryDoNotUseOutputPorts() throws ClassNotFoundException {
        var classes = new ClassFileImporter().importClasses(
                HostedArtifactController.class, HostedJobController.class,
                Class.forName("io.gen2spring.mcp.app.web.presentation.hosted.HostedSpecificationController"),
                Class.forName("io.gen2spring.mcp.app.web.presentation.page.DashboardController"),
                HostedAccountResolver.class);
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "..application.hosted..port.out..", "..app.web.application.hosted.port.out..").check(classes);
    }

    private void assertLoadable(String name) {
        assertDoesNotThrow(() -> Class.forName(name, false, getClass().getClassLoader()));
    }

    private void assertNotLoadable(String name) {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, getClass().getClassLoader()));
    }
}

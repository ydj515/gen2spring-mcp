package io.gen2spring.mcp.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.gen2spring.mcp.app.architecturefixture.application.ForbiddenApplication;
import io.gen2spring.mcp.app.architecturefixture.infrastructure.ForbiddenInfrastructure;
import io.gen2spring.mcp.app.architecturefixture.presentation.ForbiddenPresentation;
import io.gen2spring.mcp.application.architecturefixture.ApplicationTarget;
import io.gen2spring.mcp.application.architecturefixture.ForbiddenThreadPool;
import io.gen2spring.mcp.application.architecturefixture.port.out.ForbiddenPort;
import io.gen2spring.mcp.application.architecturefixture.cycle.first.First;
import io.gen2spring.mcp.application.architecturefixture.cycle.second.Second;
import io.gen2spring.mcp.domain.architecturefixture.AllowedDomain;
import io.gen2spring.mcp.domain.architecturefixture.ForbiddenDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ArchitectureTest {
    private static JavaClasses production;
    private static Map<String, Set<String>> graph;
    private static Map<String, Set<String>> externalDependencies;

    @BeforeAll
    static void importEveryProductionModule() throws Exception {
        String directories = System.getProperty("architecture.productionDirectories");
        assertTrue(directories != null && !directories.isBlank(), "Production class directories must be supplied");
        var paths = directories.lines().map(Path::of).toList();
        for (Path path : paths) {
            assertTrue(Files.isDirectory(path), "Missing compiled module: " + path);
            try (var files = Files.walk(path)) {
                assertTrue(files.anyMatch(file -> file.toString().endsWith(".class")), "Empty module: " + path);
            }
        }
        production = new ClassFileImporter().importPaths(paths);
        assertFalse(production.isEmpty());
        assertTrue(production.stream().noneMatch(type -> type.getName().contains(".architecturefixture.")));
        graph = dependencyGraph("architecture.moduleGraph");
        externalDependencies = dependencyGraph("architecture.externalDependencies");
        assertEquals(graph.keySet(), externalDependencies.keySet());
        assertEquals(graph.size(), paths.size(), "Every production module must contribute bytecode");
    }

    private static Map<String, Set<String>> dependencyGraph(String property) {
        String moduleGraph = System.getProperty(property);
        assertTrue(moduleGraph != null && !moduleGraph.isBlank(), "Production module graph must be supplied");
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String line : moduleGraph.lines().toList()) {
            String[] pair = line.split("=", -1);
            assertEquals(2, pair.length);
            result.put(pair[0], pair[1].isEmpty() ? Set.of() : Set.copyOf(Arrays.asList(pair[1].split(","))));
        }
        return result;
    }

    @Test void domainUsesOnlyDomainAndJdk() { ArchitectureRules.DOMAIN.check(production); }
    @Test void domainDoesNotUseSql() { ArchitectureRules.DOMAIN_HAS_NO_SQL.check(production); }
    @Test void controllersDoNotUsePersistenceImplementations() { ArchitectureRules.CONTROLLERS_USE_APPLICATION_PORTS.check(production); }
    @Test void applicationUsesDomainAndApprovedJsonModel() { ArchitectureRules.APPLICATION.check(production); }
    @Test void applicationPortsExposeOnlyInnerContracts() { ArchitectureRules.APPLICATION_PORTS.check(production); }
    @Test void contractsDoNotDependOnUseCaseImplementations() { ArchitectureRules.CONTRACTS_DO_NOT_USE_IMPLEMENTATIONS.check(production); }
    @Test void everyProductionPackageHasAcyclicDependencies() { ArchitectureRules.PACKAGES_ARE_ACYCLIC.check(production); }
    @Test void allAppApplicationsPointInward() { ArchitectureRules.APP_APPLICATIONS_POINT_INWARD.check(production); }
    @Test void allPresentationUsesInputContracts() { ArchitectureRules.PRESENTATION_USES_INPUT_CONTRACTS.check(production); }
    @Test void infrastructureDoesNotUseComposition() { ArchitectureRules.INFRASTRUCTURE_DOES_NOT_USE_COMPOSITION.check(production); }
    @Test void applicationDoesNotAccessFilesystem() { ArchitectureRules.APPLICATION_DOES_NOT_ACCESS_FILESYSTEM.check(production); }
    @Test void applicationDelegatesSchedulingToOutputPorts() { ArchitectureRules.APPLICATION_DOES_NOT_CREATE_EXECUTORS.check(production); }
    @Test void renderersDoNotDependOnEmitterFacades() { ArchitectureRules.RENDERERS_DO_NOT_USE_EMITTER_FACADES.check(production); }
    @Test void validationPackagesHaveOneWayDependencies() {
        ArchitectureRules.VALIDATION_PACKAGES_ARE_ACYCLIC.check(production);
        ArchitectureRules.VALIDATION_HELPERS_DO_NOT_USE_PROJECT_ORCHESTRATION.check(production);
    }
    @Test void persistenceAdaptersAreGroupedByFeature() {
        assertTrue(production.stream().noneMatch(type -> type.getPackageName()
                .equals("io.gen2spring.mcp.adapter.persistence")));
    }
    @Test void fetchGatewayLayersPointInward() {
        ArchitectureRules.FETCH_APPLICATION_POINTS_INWARD.check(production);
        ArchitectureRules.FETCH_PRESENTATION_DOES_NOT_USE_INFRASTRUCTURE.check(production);
        ArchitectureRules.FETCH_INFRASTRUCTURE_DOES_NOT_USE_DELIVERY.check(production);
    }
    @Test void providerEgressLayersPointInward() {
        ArchitectureRules.PROVIDER_EGRESS_APPLICATION_POINTS_INWARD.check(production);
        ArchitectureRules.PROVIDER_EGRESS_PRESENTATION_DOES_NOT_USE_INFRASTRUCTURE.check(production);
        ArchitectureRules.PROVIDER_EGRESS_INFRASTRUCTURE_DOES_NOT_USE_DELIVERY.check(production);
    }
    @Test void importRunnerLayersPointInward() {
        ArchitectureRules.IMPORT_RUNNER_APPLICATION_POINTS_INWARD.check(production);
        ArchitectureRules.IMPORT_RUNNER_PRESENTATION_DOES_NOT_USE_INFRASTRUCTURE.check(production);
        ArchitectureRules.IMPORT_RUNNER_INFRASTRUCTURE_DOES_NOT_USE_DELIVERY.check(production);
    }
    @Test void adaptersDoNotDependOnCompositionOrApps() { ArchitectureRules.ADAPTERS_POINT_INWARD.check(production); }
    @Test void adaptersShareOnlyEmitterSupport() { ArchitectureRules.ADAPTERS_ARE_INDEPENDENT.check(production); }
    @Test void bootstrapDoesNotDependOnApps() { ArchitectureRules.BOOTSTRAP_DOES_NOT_DEPEND_ON_APPS.check(production); }
    @Test void appsDoNotDependOnEachOther() { ArchitectureRules.APPS_ARE_INDEPENDENT.check(production); }
    @Test void declaredProductionDependenciesPointInward() { ModuleDependencyRules.check(graph); }
    @Test void coreModulesDeclareOnlyApprovedExternalLibraries() {
        ModuleDependencyRules.checkExternal(externalDependencies);
    }

    @Test
    void unusedExternalLibrariesCannotBypassCoreModuleBoundaries() {
        for (String module : Set.of(":modules:domain", ":modules:application",
                ":modules:adapters:emitters:support", ":modules:adapters:emitters:mcp-runtime")) {
            var mutated = new LinkedHashMap<>(externalDependencies);
            mutated.put(module, Set.of("org.springframework:spring-jdbc"));
            assertThrows(AssertionError.class, () -> ModuleDependencyRules.checkExternal(mutated));
        }
        var missing = new LinkedHashMap<>(externalDependencies);
        missing.remove(":modules:domain");
        assertThrows(AssertionError.class, () -> ModuleDependencyRules.checkExternal(missing));
    }

    @Test
    void everyModuleOwnsItsProductionPackages() {
        String directories = System.getProperty("architecture.moduleDirectories");
        assertTrue(directories != null && !directories.isBlank());
        var modules = new java.util.HashSet<String>();
        for (String line : directories.lines().toList()) {
            String[] pair = line.split("=", 2);
            assertTrue(modules.add(pair[0]), "Duplicate production module: " + pair[0]);
            var paths = Arrays.stream(pair[1].split("\\|")).map(Path::of).toList();
            ModulePackageRules.check(pair[0], new ClassFileImporter().importPaths(paths));
        }
        assertEquals(graph.keySet(), modules, "Package ownership must cover every production module");
    }

    @Test
    void packageOwnershipRejectsClassesHiddenInAnotherLayerNamespace() {
        var misplaced = new ClassFileImporter().importClasses(ApplicationTarget.class);
        assertThrows(AssertionError.class, () -> ModulePackageRules.check(":modules:domain", misplaced));
        assertThrows(AssertionError.class, () -> ModulePackageRules.check(":modules:unknown", misplaced));
    }

    @Test
    void packageRuleDetectsCyclesBelowTheFeatureLevel() {
        var cyclic = new ClassFileImporter().importClasses(First.class, Second.class);
        assertTrue(ArchitectureRules.PACKAGES_ARE_ACYCLIC.evaluate(cyclic).hasViolation());
    }

    @Test
    void domainRuleDetectsBytecodeEdgesButIgnoresText() {
        var illegal = new ClassFileImporter().importClasses(ForbiddenDomain.class, ApplicationTarget.class);
        assertTrue(ArchitectureRules.DOMAIN.evaluate(illegal).hasViolation());
        var allowed = new ClassFileImporter().importClasses(AllowedDomain.class);
        assertFalse(ArchitectureRules.DOMAIN.evaluate(allowed).hasViolation());
    }

    @Test
    void appRulesDetectOutputPortCompositionFilesystemAndSchedulingLeaks() {
        var classes = new ClassFileImporter().importClasses(
                ForbiddenPresentation.class, ForbiddenInfrastructure.class, ForbiddenApplication.class);
        assertTrue(ArchitectureRules.PRESENTATION_USES_INPUT_CONTRACTS.evaluate(classes).hasViolation());
        assertTrue(ArchitectureRules.INFRASTRUCTURE_DOES_NOT_USE_COMPOSITION.evaluate(classes).hasViolation());
        assertTrue(ArchitectureRules.APP_APPLICATIONS_POINT_INWARD.evaluate(classes).hasViolation());
        assertTrue(ArchitectureRules.APPLICATION_DOES_NOT_ACCESS_FILESYSTEM.evaluate(classes).hasViolation());
        assertTrue(ArchitectureRules.APPLICATION_DOES_NOT_CREATE_EXECUTORS.evaluate(classes).hasViolation());
    }

    @Test
    void portRuleRejectsAServiceDependencyEvenWithoutAPackageCycle() {
        var classes = new ClassFileImporter().importClasses(ForbiddenPort.class);
        assertTrue(ArchitectureRules.CONTRACTS_DO_NOT_USE_IMPLEMENTATIONS.evaluate(classes).hasViolation());
    }

    @Test
    void applicationRuleRejectsDirectThreadPoolsWithoutExecutorsFactory() {
        var classes = new ClassFileImporter().importClasses(ForbiddenThreadPool.class);
        assertTrue(ArchitectureRules.APPLICATION_DOES_NOT_CREATE_EXECUTORS.evaluate(classes).hasViolation());
    }

    @Test
    void unusedGradleDependencyStillFailsTheArchitectureGate() {
        var mutated = new LinkedHashMap<>(graph);
        mutated.put(":modules:domain", Set.of(":modules:application"));
        assertThrows(AssertionError.class, () -> ModuleDependencyRules.check(mutated));
    }

    @Test
    void sharingExceptionsDoNotPermitPeerOrReverseEdges() {
        String emitter = "io.gen2spring.mcp.adapter.emitter.";
        assertTrue(ArchitectureRules.allowedAdapterEdge(emitter + "springai1", emitter + "support"));
        assertFalse(ArchitectureRules.allowedAdapterEdge(emitter + "support", emitter + "springai1"));
        assertFalse(ArchitectureRules.allowedAdapterEdge(emitter + "springai1", emitter + "springai2"));
        assertTrue(ModuleDependencyRules.allows(":modules:adapters:emitters:spring-ai-2", ":modules:adapters:emitters:mcp-runtime"));
        assertFalse(ModuleDependencyRules.allows(":modules:adapters:emitters:spring-ai-1", ":modules:adapters:emitters:spring-ai-2"));
        assertFalse(ModuleDependencyRules.allows(":apps:web", ":apps:runtime"));
        assertFalse(ModuleDependencyRules.allows(":modules:adapters:filesystem", ":modules:adapters:openapi"));
    }
}

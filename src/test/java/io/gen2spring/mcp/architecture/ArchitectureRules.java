package io.gen2spring.mcp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Set;

final class ArchitectureRules {
    private static final String ROOT = "io.gen2spring.mcp.";
    private static final String ADAPTER = ROOT + "adapter.";
    private static final Set<String> SHARED_EMITTERS = Set.of(
            ADAPTER + "emitter.support", ADAPTER + "emitter.mcpruntime");

    static final ArchRule DOMAIN = classes().that().resideInAPackage(ROOT + "domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(ROOT + "domain..", "java..");

    static final ArchRule DOMAIN_HAS_NO_SQL = noClasses().that().resideInAPackage(ROOT + "domain..")
            .should().dependOnClassesThat().resideInAPackage("java.sql..");

    static final ArchRule CONTROLLERS_USE_APPLICATION_PORTS = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ADAPTER + "persistence..", ADAPTER + "storage..", "java.sql..", "javax.sql..",
                    "org.springframework.jdbc..", "org.springframework.data.repository..",
                    "org.jooq..", "org.apache.ibatis..");

    static final ArchRule APPLICATION = classes().that().resideInAPackage(ROOT + "application..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    ROOT + "application..", ROOT + "domain..", "java..", "javax.lang.model..",
                    "com.fasterxml.jackson..");

    static final ArchRule FETCH_APPLICATION_POINTS_INWARD = noClasses()
            .that().resideInAPackage(ROOT + "app.fetch.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.fetch.presentation..", ROOT + "app.fetch.infrastructure..",
                    ROOT + "app.fetch.config..");

    static final ArchRule FETCH_PRESENTATION_DOES_NOT_USE_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage(ROOT + "app.fetch.presentation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.fetch.infrastructure..", ROOT + "app.fetch.config..");

    static final ArchRule FETCH_INFRASTRUCTURE_DOES_NOT_USE_DELIVERY = noClasses()
            .that().resideInAPackage(ROOT + "app.fetch.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.fetch.presentation..", ROOT + "app.fetch.config..");

    static final ArchRule PROVIDER_EGRESS_APPLICATION_POINTS_INWARD = noClasses()
            .that().resideInAPackage(ROOT + "app.provideregress.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.provideregress.presentation..", ROOT + "app.provideregress.infrastructure..",
                    ROOT + "app.provideregress.config..", ROOT + "adapter..");

    static final ArchRule PROVIDER_EGRESS_PRESENTATION_DOES_NOT_USE_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage(ROOT + "app.provideregress.presentation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.provideregress.infrastructure..", ROOT + "app.provideregress.config..",
                    ROOT + "adapter..");

    static final ArchRule PROVIDER_EGRESS_INFRASTRUCTURE_DOES_NOT_USE_DELIVERY = noClasses()
            .that().resideInAPackage(ROOT + "app.provideregress.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "app.provideregress.presentation..", ROOT + "app.provideregress.config..");

    static final ArchRule ADAPTERS_POINT_INWARD = noClasses().that().resideInAPackage(ADAPTER + ".")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + "bootstrap..", ROOT + "app..");

    static final ArchRule BOOTSTRAP_DOES_NOT_DEPEND_ON_APPS = noClasses()
            .that().resideInAPackage(ROOT + "bootstrap..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + "app..");

    static final ArchRule APPS_ARE_INDEPENDENT = slices().matching(ROOT + "app.(*)..")
            .should().notDependOnEachOther();

    static final ArchRule ADAPTERS_ARE_INDEPENDENT = classes().that().resideInAPackage(ADAPTER + ".")
            .should(new ArchCondition<>("only use their own adapter or explicitly shared emitter support") {
                @Override
                public void check(JavaClass origin, ConditionEvents events) {
                    String source = adapterGroup(origin.getPackageName());
                    for (var dependency : origin.getDirectDependenciesFromSelf()) {
                        String targetPackage = dependency.getTargetClass().getPackageName();
                        if (!targetPackage.startsWith(ADAPTER)) continue;
                        String target = adapterGroup(targetPackage);
                        if (!allowedAdapterEdge(source, target)) {
                            events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                        }
                    }
                }
            });

    static boolean allowedAdapterEdge(String source, String target) {
        if (source.equals(target)) return true;
        if (source.equals(ADAPTER + "emitter.springai1") || source.equals(ADAPTER + "emitter.springai2")) {
            return SHARED_EMITTERS.contains(target);
        }
        return source.equals(ADAPTER + "emitter.mcpruntime") && target.equals(ADAPTER + "emitter.support");
    }

    private static String adapterGroup(String packageName) {
        String[] parts = packageName.split("\\.");
        int count = packageName.startsWith(ADAPTER + "emitter.") ? 6 : 5;
        return String.join(".", Arrays.copyOf(parts, Math.min(parts.length, count)));
    }

    private ArchitectureRules() {}
}

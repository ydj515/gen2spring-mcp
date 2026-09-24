package io.gen2spring.mcp.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Map;

final class ModulePackageRules {
    private static final String ROOT = "io.gen2spring.mcp.";
    private static final Map<String, String> PACKAGES = Map.ofEntries(
            Map.entry(":modules:domain", "domain"),
            Map.entry(":modules:application", "application"),
            Map.entry(":modules:bootstrap", "bootstrap"),
            Map.entry(":modules:adapters:configuration", "adapter.configuration"),
            Map.entry(":modules:adapters:container-runtime", "adapter.container"),
            Map.entry(":modules:adapters:cryptography", "adapter.cryptography"),
            Map.entry(":modules:adapters:filesystem", "adapter.filesystem"),
            Map.entry(":modules:adapters:mcp-java-sdk", "adapter.mcp"),
            Map.entry(":modules:adapters:object-storage-s3", "adapter.storage"),
            Map.entry(":modules:adapters:openapi", "adapter.openapi"),
            Map.entry(":modules:adapters:persistence-postgres", "adapter.persistence"),
            Map.entry(":modules:adapters:provider-egress", "adapter.provideregress"),
            Map.entry(":modules:adapters:url-fetch", "adapter.urlfetch"),
            Map.entry(":modules:adapters:validation", "adapter.validation"),
            Map.entry(":modules:adapters:emitters:support", "adapter.emitter.support"),
            Map.entry(":modules:adapters:emitters:mcp-runtime", "adapter.emitter.mcpruntime"),
            Map.entry(":modules:adapters:emitters:spring-ai-1", "adapter.emitter.springai1"),
            Map.entry(":modules:adapters:emitters:spring-ai-2", "adapter.emitter.springai2"),
            Map.entry(":apps:cli", "app.cli"),
            Map.entry(":apps:fetch-gateway", "app.fetch"),
            Map.entry(":apps:import-runner", "app.importer"),
            Map.entry(":apps:provider-egress", "app.provideregress"),
            Map.entry(":apps:runtime", "app.runtime"),
            Map.entry(":apps:web", "app.web"),
            Map.entry(":apps:worker", "app.worker"));

    static void check(String module, JavaClasses classes) {
        String suffix = PACKAGES.get(module);
        if (suffix == null) throw new AssertionError("Unclassified module package: " + module);
        String expected = ROOT + suffix;
        for (var type : classes) {
            String actual = type.getPackageName();
            if (!actual.equals(expected) && !actual.startsWith(expected + ".")) {
                throw new AssertionError("Class outside its owning module package: " + module + " -> " + type.getName());
            }
        }
    }

    private ModulePackageRules() {}
}

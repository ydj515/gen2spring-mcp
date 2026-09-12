package io.gen2spring.mcp.architecture;

import java.util.Map;
import java.util.Set;

final class ModuleDependencyRules {
    private static final String DOMAIN = ":modules:domain";
    private static final String APPLICATION = ":modules:application";
    private static final String BOOTSTRAP = ":modules:bootstrap";
    private static final String ADAPTERS = ":modules:adapters:";
    private static final String EMITTERS = ADAPTERS + "emitters:";

    static boolean allows(String source, String target) {
        if (source.equals(target)) return false;
        if (source.equals(DOMAIN)) return false;
        if (source.equals(APPLICATION)) return target.equals(DOMAIN);
        if (source.equals(EMITTERS + "support")) return target.equals(DOMAIN);
        if (source.equals(EMITTERS + "mcp-runtime")) {
            return target.equals(DOMAIN) || target.equals(EMITTERS + "support");
        }
        if (source.equals(EMITTERS + "spring-ai-1") || source.equals(EMITTERS + "spring-ai-2")) {
            return Set.of(DOMAIN, APPLICATION, EMITTERS + "support", EMITTERS + "mcp-runtime").contains(target);
        }
        if (source.startsWith(ADAPTERS)) return target.equals(DOMAIN) || target.equals(APPLICATION);
        if (source.equals(BOOTSTRAP) || source.startsWith(":apps:")) {
            return target.equals(DOMAIN) || target.equals(APPLICATION) || target.startsWith(ADAPTERS)
                    || source.startsWith(":apps:") && target.equals(BOOTSTRAP);
        }
        return false;
    }

    static void check(Map<String, Set<String>> graph) {
        for (var entry : graph.entrySet()) {
            String source = entry.getKey();
            if (!source.equals(DOMAIN) && !source.equals(APPLICATION) && !source.equals(BOOTSTRAP)
                    && !source.startsWith(ADAPTERS) && !source.startsWith(":apps:")) {
                throw new AssertionError("Unclassified production module: " + source);
            }
            for (String target : entry.getValue()) {
                if (!graph.containsKey(target) || !allows(source, target)) {
                    throw new AssertionError("Forbidden production module dependency: " + source + " -> " + target);
                }
            }
        }
    }

    private ModuleDependencyRules() {}
}

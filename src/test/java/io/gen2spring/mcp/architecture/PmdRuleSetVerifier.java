package io.gen2spring.mcp.architecture;

import net.sourceforge.pmd.lang.rule.RuleSetLoader;

public final class PmdRuleSetVerifier {
    private PmdRuleSetVerifier() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected a PMD ruleset path");
        var rules = new RuleSetLoader().loadFromResource(arguments[0]);
        if (rules.getRules().isEmpty()) throw new IllegalStateException("PMD ruleset must not be empty");
        System.out.println("Validated " + rules.getRules().size() + " PMD rules");
    }
}

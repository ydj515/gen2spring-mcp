package io.gen2spring.mcp.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.rule.RuleSetLoadException;
import net.sourceforge.pmd.lang.rule.RuleSetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PmdConfigurationTest {
    @TempDir Path directory;

    @Test
    void configuredRulesDetectARealBugWithoutTreatingTemplateTextAsCode() throws Exception {
        Path input = directory.resolve("Example.java");
        Files.writeString(input, """
                class Example {
                    boolean broken(Object input) { return input.equals(null); }
                    String template() { return "boolean broken(Object input) { return input.equals(null); }"; }
                }
                """);
        var configuration = new PMDConfiguration();
        configuration.setIgnoreIncrementalAnalysis(true);
        try (var analysis = PmdAnalysis.create(configuration)) {
            analysis.addRuleSet(new RuleSetLoader().loadFromResource(System.getProperty("quality.pmdRules")));
            analysis.files().addFile(input);
            var report = analysis.performAnalysisAndCollectReport();
            assertTrue(report.getProcessingErrors().isEmpty());
            assertTrue(report.getConfigurationErrors().isEmpty());
            assertEquals(1, report.getViolations().size());
            assertEquals("EqualsNull", report.getViolations().getFirst().getRule().getName());
        }
    }

    @Test
    void invalidAndEmptyRulesCannotProduceAGreenQualityGate() throws Exception {
        Path ruleset = directory.resolve("ruleset.xml");
        Files.writeString(ruleset, """
                <ruleset name="Invalid" xmlns="http://pmd.sourceforge.net/ruleset/2.0.0">
                    <description>Invalid rule fixture</description>
                    <rule ref="category/java/errorprone.xml/DoesNotExist" />
                </ruleset>
                """);
        assertThrows(RuleSetLoadException.class, () -> PmdRuleSetVerifier.main(new String[] {ruleset.toString()}));
        Files.writeString(ruleset, """
                <ruleset name="Empty" xmlns="http://pmd.sourceforge.net/ruleset/2.0.0">
                    <description>Empty rule fixture</description>
                </ruleset>
                """);
        assertThrows(IllegalStateException.class, () -> PmdRuleSetVerifier.main(new String[] {ruleset.toString()}));
    }
}

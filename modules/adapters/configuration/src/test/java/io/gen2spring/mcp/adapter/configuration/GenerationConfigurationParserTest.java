package io.gen2spring.mcp.adapter.configuration;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.tool.OutputKind;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class GenerationConfigurationParserTest {
    private final GenerationConfigurationParser parser =
            new GenerationConfigurationParser(CompatibilityProfileRegistry.defaults());

    @Test
    void parsesProtocolSelectionAndRejectsUnsupportedCombinations() {
        assertEquals("LEGACY", parser.parseYaml(validYaml().getBytes(UTF_8)).mcpProtocol().name());
        for (String mode : java.util.List.of("LEGACY", "MODERN", "DUAL")) {
            var selected = parser.parseYaml(("mcpImplementation: MCP_JAVA_SDK\nmcpProtocol: " + mode
                    + "\n" + validYaml()).getBytes(UTF_8));
            assertEquals(mode, selected.mcpProtocol().name());
        }
        var incompatible = assertThrows(GenerationConfigurationException.class, () -> parser.parseJson(
                validJson().replaceFirst("\\{", "{\"mcpProtocol\":\"MODERN\",").getBytes(UTF_8)));
        assertEquals("Selected MCP protocol requires MCP_JAVA_SDK", incompatible.getMessage());
        assertInvalidJson(validJson().replaceFirst("\\{", "{\"mcpProtocol\":\"UNKNOWN\","));
        assertInvalidJson(validJson().replaceFirst("\\{", "{\"mcpProtocol\":null,"));
    }

    @Test
    void defaultsLegacyRequestsAndParsesBothImplementationChoices() {
        assertEquals("SPRING_AI_EXPLICIT", parser.parseYaml(validYaml().getBytes(UTF_8)).mcpImplementation().name());
        for (String mode : java.util.List.of("SPRING_AI_ANNOTATIONS", "MCP_JAVA_SDK")) {
            var yaml = parser.parseYaml(("mcpImplementation: " + mode + "\n" + validYaml()).getBytes(UTF_8));
            var json = parser.parseJson(validJson().replaceFirst("\\{", "{\"mcpImplementation\":\"" + mode + "\",").getBytes(UTF_8));
            assertEquals(yaml, json);
            assertEquals(mode, yaml.mcpImplementation().name());
        }
        assertInvalidJson(validJson().replaceFirst("\\{", "{\"mcpImplementation\":null,"));
        assertInvalidJson(validJson().replaceFirst("\\{", "{\"mcpImplementation\":\"UNKNOWN\","));
    }

    @Test
    void parsesEquivalentYamlAndJsonIntoTheSameRequest() {
        var yaml = parser.parseYaml(validYaml().getBytes(UTF_8));
        var json = parser.parseJson(validJson().getBytes(UTF_8));

        assertEquals(yaml, json);
        assertEquals(BigInteger.valueOf(3), json.validation().toolCall().arguments().get("days"));
        assertEquals(new BigDecimal("127.0"), json.validation().toolCall().arguments().get("longitude"));
    }

    @Test
    void preservesExplicitJsonNullInToolCallArguments() {
        var yaml = parser.parseYaml(validYaml().replace("days: 3", "days: null").getBytes(UTF_8));
        var json = parser.parseJson(validJson().replace("\"days\": 3", "\"days\": null").getBytes(UTF_8));

        assertEquals(yaml, json);
        assertTrue(yaml.validation().toolCall().arguments().containsKey("days"));
        assertEquals(null, yaml.validation().toolCall().arguments().get("days"));
    }

    @Test
    void rejectsDuplicateUnknownNonFiniteAndOversizedInputWithFixedMessages() {
        assertInvalidJson(validJson().replace(
                "\"provider\": \"kma\",", "\"provider\": \"kma\", \"provider\": \"other\","));
        assertInvalidJson(validJson().replace(
                "\"domain\": \"weather\",", "\"domain\": \"weather\", \"unknown\": true,"));
        assertInvalidJson(validJson().replace("\"days\": 3", "\"days\": NaN"));

        GenerationConfigurationException oversized = assertThrows(
                GenerationConfigurationException.class,
                () -> parser.parseJson(new byte[GenerationConfigurationParser.MAX_BYTES + 1]));
        assertEquals("Generation configuration is invalid", oversized.getMessage());
    }

    @Test
    void preservesValueFreeSemanticFailures() {
        String rejected = "private-profile-marker";

        GenerationConfigurationException exception = assertThrows(
                GenerationConfigurationException.class,
                () -> parser.parseJson(validJson().replace(
                        "spring-ai-2.0-java21-mvc-streamable", rejected).getBytes(UTF_8)));

        assertEquals("Target profile is unavailable", exception.getMessage());
        assertFalse(exception.getMessage().contains(rejected));
    }

    @Test
    void rejectsToolDescriptionsThatGeneratedSourcesCannotRepresent() {
        String oversized = "x".repeat(1_025);

        GenerationConfigurationException exception = assertThrows(
                GenerationConfigurationException.class,
                () -> parser.parseJson(validJson().replace(
                        "Get the public weather forecast.", oversized).getBytes(UTF_8)));

        assertEquals("Tool description is invalid", exception.getMessage());
    }

    @Test
    void parsesStrictOutputSelectionsAndDefaultsToGenericJson() {
        var omitted = parser.parseYaml(validYaml().getBytes(UTF_8));
        var generic = parser.parseYaml(withYamlOutput("GENERIC_JSON").getBytes(UTF_8));
        var typed = parser.parseYaml(withYamlOutput("TYPED").getBytes(UTF_8));

        assertEquals(OutputKind.GENERIC_JSON, omitted.operations().getFirst().output().mode());
        assertEquals(OutputKind.GENERIC_JSON, generic.operations().getFirst().output().mode());
        assertEquals(OutputKind.TYPED_DTO, typed.operations().getFirst().output().mode());
    }

    @Test
    void rejectsMalformedOutputSelectionsWithoutLeakingRejectedValues() {
        String rejected = "private-output-marker";
        for (String yaml : java.util.List.of(
                withYamlOutput(rejected),
                validYaml().replace("    parameters: {}", "    parameters: {}\n    output: null"),
                validYaml().replace("    parameters: {}",
                        "    parameters: {}\n    output:\n      mode: TYPED\n      unknown: true"),
                validYaml().replace("    parameters: {}", "    parameters: {}\n    output:\n      mode: 7"))) {
            GenerationConfigurationException failure = assertThrows(
                    GenerationConfigurationException.class,
                    () -> parser.parseYaml(yaml.getBytes(UTF_8)));
            assertEquals("Generation configuration is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains(rejected));
        }
    }

    @Test
    void parsesStrictRetryPoliciesAndLeavesOmittedRetryDisabled() {
        var omitted = parser.parseYaml(validYaml().getBytes(UTF_8));
        var configured = parser.parseYaml(withYamlRetry("""
                statusCodes: [503, 429]
                      networkErrors: true
                      maxRetries: 2
                      initialBackoffMillis: 100
                      maxBackoffMillis: 1000
                      respectRetryAfter: true
                """).getBytes(UTF_8));

        assertEquals(null, omitted.operations().getFirst().retry());
        assertEquals(new RetryPolicy(List.of(429, 503), true, 2, 100, 1_000, true),
                configured.operations().getFirst().retry());
    }

    @Test
    void rejectsMalformedRetryPoliciesWithoutLeakingRejectedValues() {
        String rejected = "private-retry-marker";
        List<String> invalidPolicies = java.util.List.of(
                "statusCodes: []\n      networkErrors: false\n      maxRetries: 1\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [429, 429]\n      maxRetries: 1\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [399]\n      maxRetries: 1\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [429]\n      maxRetries: 0\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [429]\n      maxRetries: 4\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [429]\n      maxRetries: 1\n      initialBackoffMillis: 100\n      maxBackoffMillis: 99",
                "statusCodes: [429]\n      maxRetries: \"2\"\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1",
                "statusCodes: [429]\n      maxRetries: 1\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1\n      respectRetryAfter: null",
                "statusCodes: [429]\n      maxRetries: 1\n      initialBackoffMillis: 1\n      maxBackoffMillis: 1\n      unknown: " + rejected);
        for (int index = 0; index < invalidPolicies.size(); index++) {
            String policy = invalidPolicies.get(index);
            GenerationConfigurationException failure = assertThrows(
                    GenerationConfigurationException.class,
                    () -> parser.parseYaml(withYamlRetry(policy).getBytes(UTF_8)), "case " + index);
            assertEquals("Generation configuration is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains(rejected));
        }
    }

    @Test
    void parsesStrictPaginationPoliciesAndLeavesOmittedPaginationDisabled() {
        var omitted = parser.parseYaml(validYaml().getBytes(UTF_8));
        var stringValue = parser.parseYaml(withYamlPagination("first").getBytes(UTF_8));
        var integerValue = parser.parseYaml(withYamlPagination("9223372036854775808").getBytes(UTF_8));

        assertEquals(null, omitted.operations().getFirst().pagination());
        assertEquals(new PaginationPolicy(
                        "cursor", "first", "/response/body/items", "/response/body/nextCursor", 10, 1_000),
                stringValue.operations().getFirst().pagination());
        assertEquals(new BigInteger("9223372036854775808"),
                integerValue.operations().getFirst().pagination().initialValue());
    }

    @Test
    void rejectsMalformedPaginationWithoutLeakingRejectedValues() {
        String rejected = "private-pagination-marker";
        List<String> invalid = List.of(
                withYamlPagination("null"),
                withYamlPagination("true"),
                withYamlPagination("1.5"),
                withYamlPagination("[]"),
                withYamlPagination("{}"),
                withYamlPagination("first").replace("itemsPath: /response/body/items", "itemsPath: items"),
                withYamlPagination("first").replace("maxPages: 10", "maxPages: 1"),
                withYamlPagination("first").replace("maxItems: 1000", "maxItems: 2001"),
                withYamlPagination("first") + "      unknown: " + rejected + "\n");

        for (int index = 0; index < invalid.size(); index++) {
            String yaml = invalid.get(index);
            GenerationConfigurationException failure = assertThrows(
                    GenerationConfigurationException.class,
                    () -> parser.parseYaml(yaml.getBytes(UTF_8)), "case " + index);
            assertEquals("Generation configuration is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains(rejected));
        }
    }

    private void assertInvalidJson(String json) {
        GenerationConfigurationException exception = assertThrows(
                GenerationConfigurationException.class,
                () -> parser.parseJson(json.getBytes(UTF_8)));
        assertEquals("Generation configuration is invalid", exception.getMessage());
    }

    private static String validYaml() {
        return """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                validation:
                  toolCall:
                    operationId: getForecast
                    arguments:
                      days: 3
                      longitude: 127.0
                operations:
                  - operationId: getForecast
                    enabled: true
                    toolName: kma_weather_get_forecast
                    toolDescription: Get the public weather forecast.
                    parameters: {}
                """;
    }

    private static String withYamlOutput(String mode) {
        return validYaml().replace("    parameters: {}", "    parameters: {}\n    output:\n      mode: " + mode);
    }

    private static String withYamlRetry(String policy) {
        return validYaml().replace("    parameters: {}", "    parameters: {}\n    retry:\n      " + policy.strip());
    }

    private static String withYamlPagination(String initialValue) {
        return validYaml().replace("    parameters: {}", "    parameters: {}\n"
                + "    pagination:\n"
                + "      requestParameter: cursor\n"
                + "      initialValue: " + initialValue + "\n"
                + "      itemsPath: /response/body/items\n"
                + "      nextValuePath: /response/body/nextCursor\n"
                + "      maxPages: 10\n"
                + "      maxItems: 1000");
    }

    private static String validJson() {
        return """
                {
                  "project": {
                    "groupId": "com.example",
                    "artifactId": "weather-mcp-server",
                    "packageName": "com.example.weather"
                  },
                  "provider": "kma",
                  "domain": "weather",
                  "targetProfileId": "spring-ai-2.0-java21-mvc-streamable",
                  "validationLevel": "MCP_PROTOCOL",
                  "validation": {
                    "toolCall": {
                      "operationId": "getForecast",
                      "arguments": {"days": 3, "longitude": 127.0}
                    }
                  },
                  "operations": [{
                    "operationId": "getForecast",
                    "enabled": true,
                    "toolName": "kma_weather_get_forecast",
                    "toolDescription": "Get the public weather forecast.",
                    "parameters": {}
                  }]
                }
                """;
    }
}

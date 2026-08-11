package io.gen2spring.mcp.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.OutputKind;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class GenerationConfigurationParserTest {
    private final GenerationConfigurationParser parser =
            new GenerationConfigurationParser(CompatibilityProfileRegistry.defaults());

    @Test
    void parsesEquivalentYamlAndJsonIntoTheSameRequest() {
        var yaml = parser.parseYaml(validYaml().getBytes(UTF_8));
        var json = parser.parseJson(validJson().getBytes(UTF_8));

        assertEquals(yaml, json);
        assertEquals(BigInteger.valueOf(3), json.validation().toolCall().arguments().get("days"));
        assertEquals(new BigDecimal("127.0"), json.validation().toolCall().arguments().get("longitude"));
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

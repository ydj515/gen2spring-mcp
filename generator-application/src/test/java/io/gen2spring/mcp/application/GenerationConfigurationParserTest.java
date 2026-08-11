package io.gen2spring.mcp.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
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

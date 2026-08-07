package io.gen2spring.mcp.cli;

import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.SERVER_SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationConfigurationReaderTest {
    @TempDir
    Path tempDir;

    private final GenerationConfigurationReader reader = new GenerationConfigurationReader();

    @Test
    void readsAndValidatesTheStrictConfiguration() throws Exception {
        Path configuration = copyResource("/config/weather-generation.yaml", "generation.yaml");

        var request = reader.read(configuration);

        assertEquals("com.example", request.project().groupId());
        assertEquals("weather-mcp-server", request.project().artifactId());
        assertEquals("getForecast", request.operations().getFirst().operationId());
        assertEquals(SERVER_SECRET,
                request.operations().getFirst().parameters().get("serviceKey").source());
        assertEquals("KMA_SERVICE_KEY",
                request.operations().getFirst().parameters().get("serviceKey").environmentVariable());
    }

    @Test
    void rejectsUnknownAndDuplicateProperties() throws Exception {
        assertInvalid(validConfiguration() + "unknown: value\n");
        assertInvalid(validConfiguration().replace("provider: kma", "provider: kma\nprovider: other"));
    }

    @Test
    void rejectsYamlScalarCoercionAndExplicitNullsBeforeBinding() throws Exception {
        assertInvalid(validConfiguration().replace("enabled: true", "enabled: \"true\""));
        assertInvalid(validConfiguration().replace("artifactId: weather-mcp-server", "artifactId: 12345"));
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.", "toolDescription: 12345"));
        assertInvalid(validConfiguration().replace("toolName: kma_weather_get_forecast", "toolName: null"));
        assertInvalid(validConfiguration().replace("""
                    parameters:
                      serviceKey:
                        source: SERVER_SECRET
                        environmentVariable: KMA_SERVICE_KEY
                """, """
                    parameters: null
                """));
        assertInvalid(validConfiguration().replace("provider: kma", "provider: null"));
    }

    @Test
    void rejectsImplicitTimestampsInStringFieldsAndAllowsQuotedDates() throws Exception {
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.", "toolDescription: 2026-08-07"));
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: 2026-08-07T14:30:00+09:00"));

        Path quoted = write("quoted-date.yaml", validConfiguration().replace(
                "toolDescription: Get the weather forecast.", "toolDescription: \"2026-08-07\""));

        assertEquals("2026-08-07", reader.read(quoted).operations().getFirst().toolDescription());
    }

    @Test
    void acceptsYamlIndicatorsInsideLiteralFoldedAndChompedDescriptions() throws Exception {
        Path literal = write("literal-description.yaml", validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: |\n      Use !important, &reference, and *note."));
        Path folded = write("folded-description.yaml", validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: >\n      Use !important,\n      &reference, and *note."));
        Path chompedLiteral = write("chomped-literal-description.yaml", validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: |-\n      Keep !important, &reference, and *note."));
        Path chompedFolded = write("chomped-folded-description.yaml", validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: >-\n      Keep !important,\n      &reference, and *note."));

        assertEquals("Use !important, &reference, and *note.\n",
                reader.read(literal).operations().getFirst().toolDescription());
        assertEquals("Use !important, &reference, and *note.\n",
                reader.read(folded).operations().getFirst().toolDescription());
        assertEquals("Keep !important, &reference, and *note.",
                reader.read(chompedLiteral).operations().getFirst().toolDescription());
        assertEquals("Keep !important, &reference, and *note.",
                reader.read(chompedFolded).operations().getFirst().toolDescription());
    }

    @Test
    void rejectsBlankOversizedAndUnsafeControlCharacterDescriptions() throws Exception {
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.", "toolDescription: \"   \""));
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.",
                "toolDescription: " + "x".repeat(2_049)));
        assertInvalid(validConfiguration().replace(
                "toolDescription: Get the weather forecast.", "toolDescription: \"bad\\u0007control\""));
    }

    @Test
    void rejectsUnsafeProjectProfileOperationAndSecretMetadata() throws Exception {
        assertInvalid(validConfiguration().replace("packageName: com.example.weather", "packageName: com.example.bad-name"));
        assertInvalid(validConfiguration().replace(
                "spring-ai-2.0-java21-mvc-streamable", "spring-ai-unknown"));
        assertInvalid(validConfiguration().substring(0, validConfiguration().indexOf("operations:"))
                + "operations: []\n");
        assertInvalid(validConfiguration().replace("KMA_SERVICE_KEY", "bad-secret"));
        assertInvalid(validConfiguration().replace(
                "toolName: kma_weather_get_forecast", "toolName: Bad Tool"));
        assertInvalid(validConfiguration().replace("source: SERVER_SECRET", "source: SERVER_DEFAULT"));
        assertInvalid(validConfiguration().replace("packageName: com.example.weather", "packageName: com.ex\u00e4mple.weather"));
    }

    @Test
    void rejectsYamlAliasesTagsAndSymbolicLinks() throws Exception {
        assertInvalid(validConfiguration().replace("provider: kma", "provider: &provider kma")
                .replace("domain: weather", "domain: *provider"));
        assertInvalid(validConfiguration().replace("provider: kma", "provider: !!java.lang.String kma"));
        assertInvalid(validConfiguration().replace("provider: kma", "provider: !custom kma"));
        assertInvalid("""
                project: &project
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                <<: *project
                provider: kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                operations: []
                """);
        assertInvalid(validConfiguration() + "---\nprovider: other\n");

        Path target = write("real.yaml", validConfiguration());
        Path link = tempDir.toRealPath().resolve("link.yaml");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }
        assertThrows(CliConfigurationException.class, () -> reader.read(link));
    }

    @Test
    void rejectsExplicitStandardTagsOnScalarsMappingsAndSequences() throws Exception {
        assertInvalid(validConfiguration().replace("provider: kma", "provider: !!str kma"));
        assertInvalid(validConfiguration().replace("enabled: true", "enabled: !!bool true"));
        assertInvalid("!!map\n" + validConfiguration());
        assertInvalid(validConfiguration().replace("project:\n", "project: !!map\n"));
        assertInvalid(validConfiguration().replace("operations:\n", "operations: !!seq\n"));
    }

    @Test
    void rejectsAConfigurationBelowAnAncestorSymbolicLink() throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path realDirectory = Files.createDirectory(safeTemp.resolve("real-config"));
        Path configuration = Files.writeString(realDirectory.resolve("generation.yaml"), validConfiguration());
        Path alias = safeTemp.resolve("config-alias");
        try {
            Files.createSymbolicLink(alias, realDirectory);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        assertThrows(CliConfigurationException.class,
                () -> reader.read(alias.resolve(configuration.getFileName())));
    }

    @Test
    void rejectsOversizedConfigurationWithoutEchoingItsContent() throws Exception {
        Path configuration = write("large.yaml", "x".repeat(GenerationConfigurationReader.MAX_BYTES + 1));

        var exception = assertThrows(CliConfigurationException.class, () -> reader.read(configuration));

        assertTrue(exception.getMessage().contains("size"));
        assertTrue(!exception.getMessage().contains("xxxxx"));
    }

    private void assertInvalid(String yaml) throws Exception {
        Path safeTemp = tempDir.toRealPath();
        Path configuration = write("invalid-" + Files.list(safeTemp).count() + ".yaml", yaml);
        assertThrows(CliConfigurationException.class, () -> reader.read(configuration));
    }

    private Path copyResource(String resource, String fileName) throws Exception {
        try (var input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            return Files.write(tempDir.toRealPath().resolve(fileName), input.readAllBytes());
        }
    }

    private Path write(String fileName, String content) throws IOException {
        return Files.writeString(tempDir.toRealPath().resolve(fileName), content);
    }

    private String validConfiguration() {
        return """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                operations:
                  - operationId: getForecast
                    enabled: true
                    toolName: kma_weather_get_forecast
                    toolDescription: Get the weather forecast.
                    parameters:
                      serviceKey:
                        source: SERVER_SECRET
                        environmentVariable: KMA_SERVICE_KEY
                """;
    }
}

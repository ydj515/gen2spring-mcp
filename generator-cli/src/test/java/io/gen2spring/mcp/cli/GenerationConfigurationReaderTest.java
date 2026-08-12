package io.gen2spring.mcp.cli;

import static io.gen2spring.mcp.domain.tool.ParameterSource.SERVER_SECRET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

        var call = request.validation().toolCall();
        assertEquals("getForecast", call.operationId());
        assertEquals(BigInteger.valueOf(3), call.arguments().get("days"));
        assertEquals(List.of("public"), call.arguments().get("tags"));
        assertEquals(Map.of("latitude", new BigDecimal("37.5"), "longitude", new BigDecimal("127.0")),
                call.arguments().get("location"));
        assertThrows(UnsupportedOperationException.class, () -> call.arguments().put("days", 4));
    }

    @Test
    void readsEveryRegisteredTargetProfile() throws Exception {
        Path ai2Java17 = copyResource("/config/weather-generation-java17.yaml", "generation-ai2-java17.yaml");
        Path ai2Java21 = copyResource("/config/weather-generation.yaml", "generation-ai2-java21.yaml");
        Path ai1Java17 = copyResource(
                "/config/weather-generation-spring-ai1-java17.yaml", "generation-ai1-java17.yaml");
        Path ai1Java21 = copyResource(
                "/config/weather-generation-spring-ai1-java21.yaml", "generation-ai1-java21.yaml");

        assertEquals("spring-ai-1.1-java17-mvc-streamable", reader.read(ai1Java17).targetProfileId());
        assertEquals("spring-ai-1.1-java21-mvc-streamable", reader.read(ai1Java21).targetProfileId());
        assertEquals("spring-ai-2.0-java17-mvc-streamable", reader.read(ai2Java17).targetProfileId());
        assertEquals("spring-ai-2.0-java21-mvc-streamable", reader.read(ai2Java21).targetProfileId());
        assertEquals(
                Files.readString(ai2Java17).replace(
                        "spring-ai-2.0-java17-mvc-streamable", "spring-ai-1.1-java17-mvc-streamable"),
                Files.readString(ai1Java17));
        assertEquals(
                Files.readString(ai2Java21).replace(
                        "spring-ai-2.0-java21-mvc-streamable", "spring-ai-1.1-java21-mvc-streamable"),
                Files.readString(ai1Java21));
    }

    @Test
    void usesTheInjectedProfileRegistryAndDoesNotEchoAnUnknownProfile() throws Exception {
        var java21Only = CompatibilityProfileRegistry.of(List.of(CompatibilityProfile.p0()));
        var restrictedReader = new GenerationConfigurationReader(java21Only);
        Path java17 = copyResource("/config/weather-generation-java17.yaml", "restricted-java17.yaml");

        CliConfigurationException exception = assertThrows(
                CliConfigurationException.class,
                () -> restrictedReader.read(java17));

        assertEquals("Target profile is unavailable", exception.getMessage());
        assertFalse(exception.getMessage().contains("spring-ai-2.0-java17-mvc-streamable"));
    }

    @Test
    void rejectsAnUnknownProfileWithAFixedValueFreeFailure() throws Exception {
        String unknownProfile = "spring-ai-secret-unknown";
        Path configuration = write("unknown-profile.yaml", validConfiguration().replace(
                "spring-ai-2.0-java21-mvc-streamable", unknownProfile));

        CliConfigurationException exception = assertThrows(
                CliConfigurationException.class,
                () -> reader.read(configuration));

        assertEquals("Target profile is unavailable", exception.getMessage());
        assertFalse(exception.getMessage().contains(unknownProfile));
    }

    @Test
    void readsTypedResponseNormalization() throws IOException {
        GenerationCommand request = reader.read(write("normalization.yaml", validConfiguration().replace(
                "    parameters:\n", "    responseNormalization:\n"
                        + "      dataPath: /response/body/items/0\n"
                        + "      successCodePath: /response/header/resultCode\n"
                        + "      successValues: [\"00\", 0, false]\n"
                        + "      errorMessagePath: /response/header/resultMsg\n"
                        + "      totalCountPath: /response/body/totalCount\n"
                        + "    parameters:\n")));

        assertEquals(new ResponseNormalizationPolicy(
                "/response/body/items/0", "/response/header/resultCode", List.of("00", BigInteger.ZERO, false),
                "/response/header/resultMsg", "/response/body/totalCount"),
                request.operations().getFirst().responseNormalization());
    }

    @Test
    void rejectsUnknownNormalizationFieldsAndNonScalarSuccessValues() throws IOException {
        assertThrows(CliConfigurationException.class,
                () -> reader.read(write("unknown-normalization.yaml", validConfiguration().replace(
                        "    parameters:\n", "    responseNormalization: {jsonPath: $.items}\n    parameters:\n"))));
        assertThrows(CliConfigurationException.class,
                () -> reader.read(write("nested-normalization.yaml", validConfiguration().replace(
                        "    parameters:\n", "    responseNormalization: {successCodePath: /code, successValues: [[00]]}\n"
                                + "    parameters:\n"))));
    }

    @Test
    void distinguishesAbsentPointersFromExplicitEmptyPointerStrings() throws IOException {
        assertThrows(CliConfigurationException.class,
                () -> reader.read(write("empty-pointer.yaml", validConfiguration().replace(
                        "    parameters:\n", "    responseNormalization: {dataPath: \"\"}\n"
                                + "    parameters:\n"))));

        GenerationCommand absent = reader.read(write("absent-pointer.yaml", validConfiguration().replace(
                "    parameters:\n", "    responseNormalization: {}\n    parameters:\n")));
        GenerationCommand emptyProperty = reader.read(write(
                "empty-property-pointer.yaml", validConfiguration().replace(
                        "    parameters:\n", "    responseNormalization: {dataPath: /}\n"
                                + "    parameters:\n")));

        assertEquals(new ResponseNormalizationPolicy(null, null, List.of(), null, null),
                absent.operations().getFirst().responseNormalization());
        assertEquals("/", emptyProperty.operations().getFirst().responseNormalization().dataPointer());
    }

    @ParameterizedTest
    @MethodSource("invalidValidationConfigurations")
    void rejectsInvalidRepresentativeToolCallValidation(String yaml) throws Exception {
        assertInvalid(yaml);
    }

    private static List<String> invalidValidationConfigurations() {
        String valid = validConfiguration();
        return List.of(
                valid.replace(validationBlock(), ""),
                valid.replace("  toolCall:\n", "  unsupported: value\n  toolCall:\n"),
                valid.replace("      days: 3", "      days: null"),
                valid.replace("      days: 3", "      nested:\n" + nestedMapping(17, "        ") + "      days: 3"),
                valid.replace("      days: 3", members(257)),
                valid.replace("        - public", arrayItems(257)),
                valid.replace("        - public", "        - " + "x".repeat(2_049)),
                valid.replace("      days: 3", "      days: &days 3"),
                valid.replace("      days: 3", "      days: !!int 3"),
                valid.replace("provider: kma", "provider: 3"),
                valid.replace("domain: weather", "domain: true"));
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

    private static String validConfiguration() {
        return """
                project:
                  groupId: com.example
                  artifactId: weather-mcp-server
                  packageName: com.example.weather
                provider: kma
                domain: weather
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                """ + validationBlock() + """
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

    private static String validationBlock() {
        return """
                validation:
                  toolCall:
                    operationId: getForecast
                    arguments:
                      days: 3
                      tags:
                        - public
                      location:
                        latitude: 37.5
                        longitude: 127.0
                """;
    }

    private static String nestedMapping(int depth, String indentation) {
        StringBuilder yaml = new StringBuilder();
        String current = indentation;
        for (int index = 0; index < depth; index++) {
            yaml.append(current).append("value:").append('\n');
            current += "  ";
        }
        return yaml.append(current).append("leaf: true").append('\n').toString();
    }

    private static String members(int count) {
        StringBuilder yaml = new StringBuilder();
        for (int index = 0; index < count; index++) {
            yaml.append("      member").append(index).append(": ").append(index).append('\n');
        }
        return yaml.toString();
    }

    private static String arrayItems(int count) {
        StringBuilder yaml = new StringBuilder();
        for (int index = 0; index < count; index++) {
            yaml.append("        - item").append(index).append('\n');
        }
        return yaml.toString();
    }
}

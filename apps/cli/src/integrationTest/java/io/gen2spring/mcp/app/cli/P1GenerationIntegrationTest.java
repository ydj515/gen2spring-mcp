package io.gen2spring.mcp.app.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P1GenerationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JAVA_17_HOME = "GEN2SPRING_JAVA_17_HOME";
    private static final String JAVA_21_HOME = "GEN2SPRING_JAVA_21_HOME";
    private static final String TOOL_NAME = "kma_weather_get_forecast";
    private static final String TOOL_DESCRIPTION = "Get the public weather forecast for a grid location.";
    private static final String REPRESENTATIVE_STATION_ID = "STN01";
    private static final String LIVE_QUERY_SECRET = "independent-query-secret";
    private static final String LIVE_HEADER_SECRET = "independent-header-secret";
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";
    private static final ProfileCase SPRING_AI_1_JAVA_17 = new ProfileCase(
            "spring-ai-1.1-java17-mvc-streamable",
            "generator-spring-ai-1",
            "spring-ai-1-v2",
            "3.5.16",
            "1.1.8",
            17,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_17_IMAGE,
            "config/weather-generation-spring-ai1-java17.yaml");
    private static final ProfileCase SPRING_AI_1_JAVA_17_MAVEN = new ProfileCase(
            "spring-ai-1.1-java17-maven-mvc-streamable",
            "generator-spring-ai-1",
            "spring-ai-1-v2",
            "3.5.16",
            "1.1.8",
            17,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_17_IMAGE,
            "config/weather-generation-spring-ai1-java17-maven.yaml");
    private static final ProfileCase SPRING_AI_1_JAVA_21 = new ProfileCase(
            "spring-ai-1.1-java21-mvc-streamable",
            "generator-spring-ai-1",
            "spring-ai-1-v2",
            "3.5.16",
            "1.1.8",
            21,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_21_IMAGE,
            "config/weather-generation-spring-ai1-java21.yaml");
    private static final ProfileCase SPRING_AI_1_JAVA_21_MAVEN = new ProfileCase(
            "spring-ai-1.1-java21-maven-mvc-streamable",
            "generator-spring-ai-1",
            "spring-ai-1-v2",
            "3.5.16",
            "1.1.8",
            21,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_21_IMAGE,
            "config/weather-generation-spring-ai1-java21-maven.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_17 = new ProfileCase(
            "spring-ai-2.0-java17-mvc-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            17,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_17_IMAGE,
            "config/weather-generation-java17.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_17_MAVEN = new ProfileCase(
            "spring-ai-2.0-java17-maven-mvc-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            17,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_17_IMAGE,
            "config/weather-generation-spring-ai2-java17-maven.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_21 = new ProfileCase(
            "spring-ai-2.0-java21-mvc-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            21,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_21_IMAGE,
            "config/weather-generation.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_21_MAVEN = new ProfileCase(
            "spring-ai-2.0-java21-maven-mvc-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            21,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_21_IMAGE,
            "config/weather-generation-spring-ai2-java21-maven.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_17_WEBFLUX = new ProfileCase(
            "spring-ai-2.0-java17-webflux-async-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            17,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_17_IMAGE,
            "config/weather-generation-java17.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_17_MAVEN_WEBFLUX = new ProfileCase(
            "spring-ai-2.0-java17-maven-webflux-async-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            17,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_17_IMAGE,
            "config/weather-generation-spring-ai2-java17-maven.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_21_WEBFLUX = new ProfileCase(
            "spring-ai-2.0-java21-webflux-async-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            21,
            "GRADLE_KOTLIN",
            "9.6.1",
            "9.6.1",
            JAVA_21_IMAGE,
            "config/weather-generation.yaml");
    private static final ProfileCase SPRING_AI_2_JAVA_21_MAVEN_WEBFLUX = new ProfileCase(
            "spring-ai-2.0-java21-maven-webflux-async-streamable",
            "generator-spring-ai-2",
            "spring-ai-2-v3",
            "4.1.0",
            "2.0.0",
            21,
            "MAVEN",
            "3.9.16",
            "3.3.4",
            JAVA_21_IMAGE,
            "config/weather-generation-spring-ai2-java21-maven.yaml");
    private static final List<ProfileCase> PROFILE_CASES = List.of(
            SPRING_AI_1_JAVA_17,
            SPRING_AI_1_JAVA_21,
            SPRING_AI_2_JAVA_17,
            SPRING_AI_2_JAVA_21);
    private static final List<ProfileCase> MAVEN_PROFILE_CASES = List.of(
            SPRING_AI_1_JAVA_17_MAVEN,
            SPRING_AI_1_JAVA_21_MAVEN,
            SPRING_AI_2_JAVA_17_MAVEN,
            SPRING_AI_2_JAVA_21_MAVEN);
    private static final List<ProfileCase> WEBFLUX_PROFILE_CASES = List.of(
            SPRING_AI_2_JAVA_17_MAVEN_WEBFLUX,
            SPRING_AI_2_JAVA_17_WEBFLUX,
            SPRING_AI_2_JAVA_21_MAVEN_WEBFLUX,
            SPRING_AI_2_JAVA_21_WEBFLUX);
    private static final List<ProfileCase> ALL_PROFILE_CASES = List.of(
            SPRING_AI_1_JAVA_17_MAVEN,
            SPRING_AI_1_JAVA_17,
            SPRING_AI_1_JAVA_21_MAVEN,
            SPRING_AI_1_JAVA_21,
            SPRING_AI_2_JAVA_17_MAVEN,
            SPRING_AI_2_JAVA_17_MAVEN_WEBFLUX,
            SPRING_AI_2_JAVA_17,
            SPRING_AI_2_JAVA_17_WEBFLUX,
            SPRING_AI_2_JAVA_21_MAVEN,
            SPRING_AI_2_JAVA_21_MAVEN_WEBFLUX,
            SPRING_AI_2_JAVA_21,
            SPRING_AI_2_JAVA_21_WEBFLUX);
    private static final List<ProfileCase> WINDOWS_REPRESENTATIVE_PROFILE_CASES = List.of(
            SPRING_AI_1_JAVA_17,
            SPRING_AI_2_JAVA_21_MAVEN_WEBFLUX);
    private static final Set<String> REQUIRED_OUTPUTS = Set.of(
            ".dockerignore",
            ".gitignore",
            "Dockerfile",
            "GENERATION_MANIFEST.json",
            "RUNTIME_METADATA.json",
            "README.md",
            "VALIDATION_REPORT.json",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "openapi/source.yaml",
            "settings.gradle.kts",
            "src/main/java/com/example/weather/application/WeatherMcpApplication.java",
            "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java",
            "src/main/java/com/example/weather/generated/model/GetForecastInput.java",
            "src/main/java/com/example/weather/generated/model/GetForecastModeValue.java",
            "src/main/java/com/example/weather/generated/model/GetForecastResult.java",
            "src/main/java/com/example/weather/generated/model/GetForecastResultDataItem.java",
            "src/main/java/com/example/weather/generated/model/GetForecastResultPage.java",
            "src/main/java/com/example/weather/generated/model/GetForecastResultProvider.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java",
            "src/main/java/com/example/weather/runtime/NormalizedSuccess.java",
            "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java",
            "src/main/java/com/example/weather/runtime/OperationDefinition.java",
            "src/main/java/com/example/weather/runtime/OperationOutcome.java",
            "src/main/java/com/example/weather/runtime/PageAccumulator.java",
            "src/main/java/com/example/weather/runtime/PaginationPolicy.java",
            "src/main/java/com/example/weather/runtime/ParameterBinding.java",
            "src/main/java/com/example/weather/runtime/ParameterLocation.java",
            "src/main/java/com/example/weather/runtime/ProviderError.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorCategory.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorException.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizationPolicy.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizer.java",
            "src/main/java/com/example/weather/runtime/RetryPolicy.java",
            "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java",
            "src/main/java/com/example/weather/runtime/SchemaValueValidator.java",
            "src/main/java/com/example/weather/runtime/SecretBinding.java",
            "src/main/java/com/example/weather/runtime/ToolArgumentContext.java",
            "src/main/resources/application.yml",
            "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java",
            "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java");

    @TempDir
    Path tempDir;

    @Test
    void installedCliValidatesTheJavaProfileMatrixDeterministically() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        TargetJavaHomes targetJavaHomes = targetJavaHomes();
        assertInstalledProfileMatrix();
        assertFixturesDifferOnlyByProfile();
        Map<String, GenerationResult> firstByProfile = new LinkedHashMap<>();

        for (ProfileCase profile : PROFILE_CASES) {
            Path configuration = resource(profile.configurationResource());
            String outputName = "weather-mcp-server-" + profile.id();
            GenerationResult first = generate(specification, configuration, tempDir.resolve(outputName));
            assertReleaseContract(first, specification, profile);

            GenerationResult second = generate(
                    specification, configuration, tempDir.resolve(outputName + "-second"));
            assertReleaseContract(second, specification, profile);
            assertEquals(first.sourceChecksum(), second.sourceChecksum(), profile.id());
            assertEquals(first.manifest(), second.manifest(), profile.id());
            assertCanonicalArchiveEntriesEqual(first.archiveEntries(), second.archiveEntries());
            assertValidationReportsEqualExceptMeasurements(first.report(), second.report());
            assertSensitiveValuesAbsent(first, REPRESENTATIVE_STATION_ID,
                    "mcp-validation-secret-1", "mcp-validation-secret-2",
                    targetJavaHomes.java17Home().toString(), targetJavaHomes.java21Home().toString());
            assertSensitiveValuesAbsent(second, REPRESENTATIVE_STATION_ID,
                    "mcp-validation-secret-1", "mcp-validation-secret-2",
                    targetJavaHomes.java17Home().toString(), targetJavaHomes.java21Home().toString());
            assertOperationalDetailsAbsent(first);
            assertOperationalDetailsAbsent(second);
            firstByProfile.put(profile.id(), first);
        }

        assertEquals(4L, firstByProfile.values().stream()
                .map(GenerationResult::sourceChecksum)
                .distinct()
                .count());
        for (ProfileCase profile : PROFILE_CASES) {
            assertIndependentLiveMcpContract(firstByProfile.get(profile.id()), profile, targetJavaHomes);
        }
    }

    @Test
    void mavenMvcProfilesValidateAndArchiveDeterministically() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        targetJavaHomes();
        assertInstalledProfileMatrix();

        for (ProfileCase profile : MAVEN_PROFILE_CASES) {
            Path configuration = resource(profile.configurationResource());
            String outputName = "maven-weather-mcp-server-" + profile.id();
            GenerationResult first = generate(specification, configuration, tempDir.resolve(outputName));
            GenerationResult second = generate(
                    specification, configuration, tempDir.resolve(outputName + "-second"));

            assertMavenReleaseContract(first, specification, profile);
            assertMavenReleaseContract(second, specification, profile);
            assertEquals(first.sourceChecksum(), second.sourceChecksum(), profile.id());
            assertEquals(first.manifest(), second.manifest(), profile.id());
            assertCanonicalArchiveEntriesEqual(first.archiveEntries(), second.archiveEntries());
            assertValidationReportsEqualExceptMeasurements(first.report(), second.report());
        }
    }

    @Test
    void webfluxAsyncProfilesValidateAndArchiveDeterministically() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        targetJavaHomes();
        assertInstalledProfileMatrix();

        for (ProfileCase profile : WEBFLUX_PROFILE_CASES) {
            Path configuration = configurationFor(profile);
            String outputName = "webflux-weather-mcp-server-" + profile.id();
            GenerationResult first = generate(specification, configuration, tempDir.resolve(outputName));
            GenerationResult second = generate(
                    specification, configuration, tempDir.resolve(outputName + "-second"));

            assertWebFluxReleaseContract(first, profile);
            assertWebFluxReleaseContract(second, profile);
            assertEquals(first.sourceChecksum(), second.sourceChecksum(), profile.id());
            assertEquals(first.manifest(), second.manifest(), profile.id());
            assertCanonicalArchiveEntriesEqual(first.archiveEntries(), second.archiveEntries());
            assertValidationReportsEqualExceptMeasurements(first.report(), second.report());
        }
    }

    @Test
    void windowsRepresentativeProfilesValidateAcrossTargetAxes() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        targetJavaHomes();
        assertInstalledProfileMatrix();

        for (ProfileCase profile : WINDOWS_REPRESENTATIVE_PROFILE_CASES) {
            GenerationResult result = generate(
                    specification,
                    configurationFor(profile),
                    tempDir.resolve("windows-representative-" + profile.id()));

            if (profile.webFlux()) {
                assertWebFluxReleaseContract(result, profile);
            } else {
                assertReleaseContract(result, specification, profile);
            }
        }
    }

    @Test
    void suppliedOpenApiVersionPairGeneratesTheSameRepresentativeToolContract() throws Exception {
        Path configuration = Files.writeString(tempDir.resolve("paired-customers-generation.yaml"), """
                project:
                  groupId: com.example
                  artifactId: paired-customers-mcp-server
                  packageName: com.example.customers
                provider: sample
                domain: customers
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                validation:
                  toolCall:
                    operationId: getCustomers
                    arguments: {}
                operations:
                  - operationId: getCustomers
                    enabled: true
                    toolName: sample_customers_get_customers
                    toolDescription: Get all customers.
                """, UTF_8);

        GenerationResult openApi30 = generate(
                repositoryRoot().resolve("swagger-3.0.yml"), configuration, tempDir.resolve("paired-openapi-30"));
        GenerationResult openApi31 = generate(
                repositoryRoot().resolve("swagger-3.1.yml"), configuration, tempDir.resolve("paired-openapi-31"));

        assertEquivalentRepresentativeGeneration(openApi30);
        assertEquivalentRepresentativeGeneration(openApi31);
        assertEquals(openApi30.manifest().path("operationMappings"),
                openApi31.manifest().path("operationMappings"));
        assertEquals(mainSourceFiles(openApi30.projectRoot()), mainSourceFiles(openApi31.projectRoot()));
        assertEquals(openApi30.report().path("tools"), openApi31.report().path("tools"));
    }

    @Test
    void suppliedOpenApiVersionPairPreservesNullableBodiesAndArrayMinimums() throws Exception {
        Path configuration = Files.writeString(tempDir.resolve("paired-cancel-items-generation.yaml"), """
                project:
                  groupId: com.example
                  artifactId: paired-orders-mcp-server
                  packageName: com.example.orders
                provider: sample
                domain: orders
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                validation:
                  toolCall:
                    operationId: cancelOrderItems
                    arguments:
                      id: 2
                      idempotencyKey: paired-cancel-key
                      lines:
                        - orderItemId: 1
                          quantity: 1
                      reason: null
                operations:
                  - operationId: cancelOrderItems
                    enabled: true
                    toolName: sample_orders_cancel_order_items
                    toolDescription: Cancel selected order items.
                """, UTF_8);

        GenerationResult openApi30 = generate(
                repositoryRoot().resolve("swagger-3.0.yml"), configuration,
                tempDir.resolve("nullable-openapi-30"));
        GenerationResult openApi31 = generate(
                repositoryRoot().resolve("swagger-3.1.yml"), configuration,
                tempDir.resolve("nullable-openapi-31"));

        assertEquivalentNullableGeneration(openApi30);
        assertEquivalentNullableGeneration(openApi31);
        assertEquals(openApi30.manifest().path("operationMappings"),
                openApi31.manifest().path("operationMappings"));
        assertEquals(mainSourceFiles(openApi30.projectRoot()), mainSourceFiles(openApi31.projectRoot()));
        assertEquals(openApi30.report().path("tools"), openApi31.report().path("tools"));

        String validConfiguration = Files.readString(configuration, UTF_8);
        String invalidConfigurationSource = validConfiguration.replace(
                "      lines:\n        - orderItemId: 1\n          quantity: 1",
                "      lines: []");
        assertFalse(validConfiguration.equals(invalidConfigurationSource));
        Path invalidConfiguration = Files.writeString(
                tempDir.resolve("paired-empty-lines-generation.yaml"), invalidConfigurationSource, UTF_8);
        Path invalidOutput = tempDir.resolve("nullable-empty-lines");
        InstalledCliResult invalid = runInstalledCli(
                repositoryRoot().resolve("swagger-3.1.yml"), invalidConfiguration, invalidOutput);

        assertEquals(3, invalid.exitCode(), invalid.stderr() + invalid.stdout());
        assertFalse(Files.exists(invalidOutput));
        assertFalse(Files.exists(invalidOutput.resolveSibling(invalidOutput.getFileName() + ".zip")));
    }

    @Test
    void suppliedOpenApiVersionPairGeneratesTheBoundedSchemaContractSlice() throws Exception {
        Path configuration = Files.writeString(tempDir.resolve("paired-schema-contracts-generation.yaml"), """
                project:
                  groupId: com.example
                  artifactId: schema-contracts-mcp-server
                  packageName: com.example.schemas
                provider: sample
                domain: schemas
                targetProfileId: spring-ai-2.0-java21-mvc-streamable
                validationLevel: MCP_PROTOCOL
                validation:
                  toolCall:
                    operationId: submitBoundedUniqueItems
                    arguments:
                      body:
                        - amount: 1
                operations:
                  - operationId: listSchemaFixtures
                    enabled: true
                    toolName: sample_schema_list_filters
                    toolDescription: List schema fixtures.
                  - operationId: submitRequiredNullablePayload
                    enabled: true
                    toolName: sample_schema_submit_required_nullable
                    toolDescription: Submit a required nullable body.
                  - operationId: submitOptionalNullablePayload
                    enabled: true
                    toolName: sample_schema_submit_optional_nullable
                    toolDescription: Submit an optional nullable body.
                  - operationId: submitBoundedUniqueItems
                    enabled: true
                    toolName: sample_schema_submit_bounded_items
                    toolDescription: Submit bounded unique items.
                  - operationId: submitCompatibleAllOf
                    enabled: true
                    toolName: sample_schema_submit_compatible
                    toolDescription: Submit a compatible intersection.
                  - operationId: submitOneOfValue
                    enabled: true
                    toolName: sample_schema_submit_one_of
                    toolDescription: Submit an exclusive value.
                  - operationId: submitAnyOfValue
                    enabled: true
                    toolName: sample_schema_submit_any_of
                    toolDescription: Submit a compatible value.
                  - operationId: submitReferencedConstraint
                    enabled: true
                    toolName: sample_schema_submit_reference
                    toolDescription: Submit a referenced constrained value.
                """, UTF_8);

        GenerationResult openApi30 = generate(
                repositoryRoot().resolve("swagger-3.0.yml"), configuration,
                tempDir.resolve("schema-contract-openapi-30"));
        GenerationResult openApi31 = generate(
                repositoryRoot().resolve("swagger-3.1.yml"), configuration,
                tempDir.resolve("schema-contract-openapi-31"));

        assertEquals(openApi30.manifest().path("operationMappings"),
                openApi31.manifest().path("operationMappings"));
        assertEquals(mainSourceFiles(openApi30.projectRoot()), mainSourceFiles(openApi31.projectRoot()));
        JsonNode metadata30 = readJson(openApi30.projectRoot().resolve("RUNTIME_METADATA.json"));
        JsonNode metadata31 = readJson(openApi31.projectRoot().resolve("RUNTIME_METADATA.json"));
        assertEquals(metadata30.path("version"), metadata31.path("version"));
        assertEquals(metadata30.path("tools"), metadata31.path("tools"));
        assertEquals(8, metadata30.path("tools").size());

        String invalidSource = Files.readString(configuration, UTF_8).replace(
                "      body:\n        - amount: 1",
                "      body:\n        - amount: 1\n        - amount: 1.0");
        assertFalse(invalidSource.equals(Files.readString(configuration, UTF_8)));
        Path invalidConfiguration = Files.writeString(
                tempDir.resolve("paired-schema-contracts-duplicate.yaml"), invalidSource, UTF_8);
        Path invalidOutput = tempDir.resolve("schema-contract-duplicate");

        InstalledCliResult invalid = runInstalledCli(
                repositoryRoot().resolve("swagger-3.1.yml"), invalidConfiguration, invalidOutput);

        assertEquals(3, invalid.exitCode(), invalid.stderr() + invalid.stdout());
        assertFalse(Files.exists(invalidOutput));
        assertFalse(Files.exists(invalidOutput.resolveSibling(invalidOutput.getFileName() + ".zip")));
    }

    @Test
    void installedCliRejectsInvalidRepresentativeArgumentWithoutPublishingOrLeakingIt() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        String configuredValue = "configured-invalid-representative-value";
        Path configuration = Files.writeString(tempDir.resolve("invalid-weather-generation.yaml"),
                Files.readString(resource("config/weather-generation.yaml"), UTF_8)
                        .replace("days: 3", "days: " + configuredValue), UTF_8);
        Path output = tempDir.resolve("invalid-weather-mcp-server");

        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        assertEquals(3, result.exitCode(), result.stderr() + result.stdout());
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".zip")));
        assertFalse(result.stdout().contains(configuredValue));
        assertFalse(result.stderr().contains(configuredValue));
    }

    private GenerationResult generate(Path specification, Path configuration, Path output) throws Exception {
        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        String validationReport = Files.exists(output.resolve("VALIDATION_REPORT.json"))
                ? Files.readString(output.resolve("VALIDATION_REPORT.json"), UTF_8)
                : "";
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout() + validationReport);
        assertEquals("", result.stderr());
        JsonNode response = JSON.readTree(result.stdout());
        assertNotNull(response);
        assertEquals("VALIDATED", response.path("status").asText());
        assertEquals(output.toAbsolutePath().normalize().toString(), response.path("project").asText());
        assertEquals(output.resolve("VALIDATION_REPORT.json").toAbsolutePath().normalize().toString(),
                response.path("report").asText());
        Path archive = output.resolveSibling(output.getFileName() + ".zip");
        assertEquals(archive.toAbsolutePath().normalize().toString(), response.path("archive").asText());
        return new GenerationResult(
                output,
                archive,
                response.path("sourceChecksum").asText(),
                readJson(output.resolve("GENERATION_MANIFEST.json")),
                readJson(output.resolve("VALIDATION_REPORT.json")),
                readArchive(archive),
                result.stdout(),
                result.stderr());
    }

    private InstalledCliResult runInstalledCli(Path specification, Path configuration, Path output) throws Exception {
        return runInstalledCliCommand(
                "generate",
                "--spec", specification.toString(),
                "--config", configuration.toString(),
                "--output", output.toString());
    }

    private InstalledCliResult runInstalledCliCommand(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        assertTrue(Files.isRegularFile(executable));
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        forwardExplicitJavaHome(processBuilder, JAVA_17_HOME);
        forwardExplicitJavaHome(processBuilder, JAVA_21_HOME);
        try (ObservedProcess process = ObservedProcess.start(processBuilder)) {
            int exitCode = process.await(Duration.ofMinutes(5), "installed CLI timed out safely");
            return new InstalledCliResult(
                    exitCode,
                    new String(process.stdout().readAllBytes(), UTF_8),
                    new String(process.stderr().readAllBytes(), UTF_8));
        }
    }

    private void forwardExplicitJavaHome(ProcessBuilder processBuilder, String variable) {
        String configured = System.getenv(variable);
        if (configured != null && !configured.isBlank()) {
            processBuilder.environment().put(variable, configured);
        }
    }

    private void assertInstalledProfileMatrix() throws Exception {
        InstalledCliResult result = runInstalledCliCommand("profiles");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        JsonNode installedProfiles = JSON.readTree(result.stdout()).path("profiles");
        assertEquals(ALL_PROFILE_CASES.size(), installedProfiles.size());
        for (int index = 0; index < ALL_PROFILE_CASES.size(); index++) {
            ProfileCase expected = ALL_PROFILE_CASES.get(index);
            JsonNode actual = installedProfiles.get(index);
            assertEquals(expected.id(), actual.path("id").asText());
            assertEquals(expected.generatorModule(), actual.path("generatorModule").asText());
            assertEquals(expected.templateVersion(), actual.path("templateVersion").asText());
            assertEquals("0.3.0", actual.path("runtimeVersion").asText());
            assertEquals(expected.buildTool(), actual.path("buildTool").path("type").asText());
            assertEquals(expected.distributionVersion(),
                    actual.path("buildTool").path("distributionVersion").asText());
            assertEquals(expected.wrapperVersion(), actual.path("buildTool").path("wrapperVersion").asText());
            if ("GRADLE_KOTLIN".equals(expected.buildTool())) {
                assertEquals(expected.distributionVersion(), actual.path("gradleVersion").asText());
            } else {
                assertFalse(actual.has("gradleVersion"));
            }
            assertEquals(expected.containerImage(), actual.path("containerImage").asText());
            assertEquals(expected.javaFeature(), actual.path("target").path("javaVersion").asInt());
            assertEquals(expected.springBootVersion(),
                    actual.path("target").path("springBootVersion").asText());
            assertEquals(expected.springAiVersion(), actual.path("target").path("springAiVersion").asText());
            assertEquals(expected.buildTool(), actual.path("target").path("buildTool").asText());
            assertEquals(expected.webStack(), actual.path("target").path("webStack").asText());
            assertEquals(expected.programmingModel(), actual.path("target").path("programmingModel").asText());
            assertEquals("STREAMABLE_HTTP", actual.path("target").path("transport").asText());
        }
    }

    private Path configurationFor(ProfileCase profile) throws IOException, URISyntaxException {
        Path source = resource(profile.configurationResource());
        if (!profile.webFlux()) {
            return source;
        }
        String configuration = Files.readString(source, UTF_8)
                .replaceFirst("(?m)^targetProfileId: \\S+$", "targetProfileId: " + profile.id());
        return Files.writeString(tempDir.resolve(profile.id() + ".yaml"), configuration, UTF_8);
    }

    private void assertReleaseContract(
            GenerationResult result,
            Path specification,
            ProfileCase profile) throws Exception {
        assertTrue(Files.isDirectory(result.projectRoot()));
        assertTrue(Files.isRegularFile(result.archive()));
        Path hostWrapper = gradleWrapper(result.projectRoot());
        assertTrue(Files.isRegularFile(hostWrapper));
        if (!isWindows() && Files.getFileStore(result.projectRoot()).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(result.projectRoot().resolve("gradlew"))
                    .contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTransientBuildOutputsAbsent(result.projectRoot());

        Set<String> outputPaths = regularFiles(result.projectRoot());
        assertEquals(REQUIRED_OUTPUTS, outputPaths);
        assertEquals(outputPaths, result.archiveEntries().keySet());
        assertEquals(new ArrayList<>(new TreeSet<>(result.archiveEntries().keySet())),
                new ArrayList<>(result.archiveEntries().keySet()));
        assertEquals(0755, centralDirectoryMode(Files.readAllBytes(result.archive()), "gradlew"));
        assertArrayEquals(Files.readAllBytes(specification),
                Files.readAllBytes(result.projectRoot().resolve("openapi/source.yaml")));
        byte[] runtimeMetadataBytes = Files.readAllBytes(result.projectRoot().resolve("RUNTIME_METADATA.json"));
        assertArrayEquals(runtimeMetadataBytes, result.archiveEntries().get("RUNTIME_METADATA.json"));
        JsonNode runtimeMetadata = JSON.readTree(runtimeMetadataBytes);
        assertEquals(List.of("metadataVersion", "specificationChecksum", "checksum", "tools"),
                iterable(runtimeMetadata.fieldNames()));
        assertEquals("1.0", runtimeMetadata.path("metadataVersion").textValue());
        assertEquals(sha256(Files.readAllBytes(specification)),
                runtimeMetadata.path("specificationChecksum").textValue());
        com.fasterxml.jackson.databind.node.ObjectNode checksumPayload = runtimeMetadata.deepCopy();
        String metadataChecksum = checksumPayload.remove("checksum").textValue();
        assertEquals(sha256(JSON.writeValueAsBytes(checksumPayload)), metadataChecksum);
        assertEquals(1, runtimeMetadata.path("tools").size());
        assertEquals(TOOL_NAME, runtimeMetadata.path("tools").get(0).path("name").textValue());
        assertEquals("TYPED_DTO", runtimeMetadata.path("tools").get(0).path("outputKind").textValue());
        String runtimeMetadataText = new String(runtimeMetadataBytes, UTF_8);
        assertFalse(runtimeMetadataText.contains(profile.id()));
        assertFalse(runtimeMetadataText.contains("KMA_SERVICE_KEY"));
        assertFalse(runtimeMetadataText.contains(LIVE_QUERY_SECRET));
        assertFalse(runtimeMetadataText.contains(LIVE_HEADER_SECRET));

        JsonNode manifest = result.manifest();
        assertEquals("0.1.0", manifest.path("generatorVersion").asText());
        assertEquals(profile.templateVersion(), manifest.path("templateVersion").asText());
        assertEquals("0.3.0", manifest.path("runtimeVersion").asText());
        assertEquals(profile.id(), manifest.path("targetProfileId").asText());
        assertEquals(profile.springBootVersion(), manifest.path("springBootVersion").asText());
        assertEquals(profile.springAiVersion(), manifest.path("springAiVersion").asText());
        assertEquals(profile.javaFeature(), manifest.path("javaVersion").asInt());
        assertEquals("9.6.1", manifest.path("gradleVersion").asText());
        assertEquals(profile.containerImage(), manifest.path("containerImage").asText());
        assertEquals(sha256(Files.readAllBytes(specification)),
                manifest.path("originalSpecificationChecksum").asText());
        assertEquals(result.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(independentSourceChecksum(result.projectRoot()), result.sourceChecksum());
        JsonNode mapping = manifest.path("operationMappings").get(0);
        assertEquals(List.of("operationId", "toolName", "output", "pagination", "retry", "responseNormalization"),
                iterable(mapping.fieldNames()));
        assertEquals("getForecast", mapping.path("operationId").textValue());
        assertEquals(TOOL_NAME, mapping.path("toolName").textValue());
        assertEquals("TYPED", mapping.path("output").path("mode").textValue());
        assertTrue(mapping.path("output").path("schemaChecksum").textValue().matches("[0-9a-f]{64}"));
        assertEquals(jsonLiteral("""
                {"itemsPath":"/response/body/items/item","maxItems":10,"maxPages":2,
                 "nextValuePath":"/response/body/nextCursor","requestParameter":"cursor"}
                """), mapping.path("pagination"));
        assertEquals(jsonLiteral("""
                {"initialBackoffMillis":1,"maxBackoffMillis":10,"maxRetries":1,
                 "networkErrors":false,"respectRetryAfter":true,"statusCodes":[503]}
                """), mapping.path("retry"));
        assertFalse(mapping.toString().contains("page-1"));
        JsonNode normalization = mapping.path("responseNormalization");
        assertEquals(List.of("dataPath", "successCodePath", "successValues", "errorMessagePath", "totalCountPath"),
                iterable(normalization.fieldNames()));
        assertEquals("/response/body/items/item", normalization.path("dataPath").textValue());
        assertEquals("/response/header/resultCode", normalization.path("successCodePath").textValue());
        assertEquals(List.of("00"), JSON.convertValue(normalization.path("successValues"), List.class));
        assertEquals("/response/header/resultMsg", normalization.path("errorMessagePath").textValue());
        assertEquals("/response/body/totalCount", normalization.path("totalCountPath").textValue());

        JsonNode report = result.report();
        assertEquals("VALIDATED", report.path("status").asText());
        assertEquals(List.of(
                "COMPILE",
                "APPLICATION_CONTEXT",
                "MCP_INITIALIZE",
                "MCP_TOOLS_LIST",
                "MCP_TOOL_CALL"), stageNames(report));
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                report.path("stages").findValuesAsText("status"));
        assertEquals("SUCCESS", stage(report, "MCP_TOOL_CALL").path("status").textValue());
        assertEquals("MCP initialize contract matched", stage(report, "MCP_INITIALIZE").path("summary").textValue());
        assertEquals("MCP Tool metadata matched the generated contract",
                stage(report, "MCP_TOOLS_LIST").path("summary").textValue());
        assertEquals("Representative MCP Tool call matched the mock upstream contract",
                stage(report, "MCP_TOOL_CALL").path("summary").textValue());
        assertEquals(List.of(TOOL_NAME), report.path("tools").findValuesAsText("name"));
        assertEquals(List.of(TOOL_DESCRIPTION), report.path("tools").findValuesAsText("description"));
        assertTrue(report.path("tools").get(0).path("inputSchemaPresent").asBoolean());

        String generatedReadme = Files.readString(result.projectRoot().resolve("README.md"), UTF_8);
        assertTrue(generatedReadme.contains("Requirements: Java " + profile.javaFeature() + "."));
        assertTrue(generatedReadme.contains("- Compatibility profile: `" + profile.id() + "`"));
        assertTrue(generatedReadme.contains("- Template: `" + profile.templateVersion() + "`"));
        assertTrue(generatedReadme.contains("- Generator module: `" + profile.generatorModule() + "`"));
        assertTrue(generatedReadme.contains("- Runtime version: `0.3.0`"));
        assertTrue(generatedReadme.contains("- Gradle 9.6.1"));
        assertTrue(generatedReadme.contains("- Container image: `" + profile.containerImage() + "`"));
        assertTrue(generatedReadme.contains("- Java " + profile.javaFeature()));
        assertTrue(generatedReadme.contains("## Response handling"));
        assertTrue(generatedReadme.contains("`getForecast`"));
        assertTrue(generatedReadme.contains("`output.mode`: `TYPED`"));
        assertTrue(generatedReadme.contains("`retry.statusCodes`: `[503]`"));
        assertTrue(generatedReadme.contains("`pagination.requestParameter`: `cursor`"));
        assertTrue(generatedReadme.contains("`pagination.maxPages`: `2`"));
        assertTrue(generatedReadme.contains("`pagination.maxItems`: `10`"));
        assertTrue(generatedReadme.contains("`successValues`: `[\"00\"]`"));
        assertFalse(generatedReadme.contains("page-1"));
        assertFalse(generatedReadme.contains("Known P0 limits"));
        assertFalse(generatedReadme.contains("\"response\": {"));

        String build = Files.readString(result.projectRoot().resolve("build.gradle.kts"), UTF_8);
        assertTrue(build.contains("languageVersion = JavaLanguageVersion.of(" + profile.javaFeature() + ")"));
        assertEquals("""
                FROM %s
                WORKDIR /app
                COPY build/libs/weather-mcp-server.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(profile.containerImage()),
                Files.readString(result.projectRoot().resolve("Dockerfile"), UTF_8));
        assertEquals("""
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/weather-mcp-server.jar
                """, Files.readString(result.projectRoot().resolve(".dockerignore"), UTF_8));
        String runtimeTest = Files.readString(result.projectRoot().resolve(
                "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java"), UTF_8);
        assertTrue(runtimeTest.contains(
                "assertEquals(" + profile.javaFeature() + ", Runtime.version().feature());"));

        String applicationYaml = Files.readString(
                result.projectRoot().resolve("src/main/resources/application.yml"), UTF_8);
        assertTrue(applicationYaml.contains("${KMA_SERVICE_KEY:}"));
        assertTrue(applicationYaml.contains("${WEATHER_HEADER_KEY:}"));
        assertFalse(applicationYaml.contains("secretValue"));
        result.archiveEntries().forEach((path, bytes) -> {
            assertFalse(path.equals("build") || path.startsWith("build/"));
            assertFalse(path.equals(".gradle") || path.startsWith(".gradle/"));
            assertFalse(path.equals("process-logs") || path.startsWith("process-logs/"));
        });
    }

    private void assertMavenReleaseContract(
            GenerationResult result,
            Path specification,
            ProfileCase profile) throws Exception {
        assertTrue(Files.isDirectory(result.projectRoot()));
        assertTrue(Files.isRegularFile(result.archive()));
        Path wrapper = result.projectRoot().resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        assertTrue(Files.isRegularFile(wrapper));
        if (!isWindows() && Files.getFileStore(result.projectRoot()).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(result.projectRoot().resolve("mvnw"))
                    .contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTransientBuildOutputsAbsent(result.projectRoot());

        Set<String> expectedOutputs = new TreeSet<>(REQUIRED_OUTPUTS);
        expectedOutputs.removeAll(Set.of(
                "build.gradle.kts",
                "gradle.properties",
                "gradle/wrapper/gradle-wrapper.jar",
                "gradle/wrapper/gradle-wrapper.properties",
                "gradlew",
                "gradlew.bat",
                "settings.gradle.kts"));
        expectedOutputs.addAll(Set.of(
                ".mvn/wrapper/maven-wrapper.properties", "mvnw", "mvnw.cmd", "pom.xml"));
        Set<String> outputPaths = regularFiles(result.projectRoot());
        assertEquals(expectedOutputs, outputPaths);
        assertEquals(outputPaths, result.archiveEntries().keySet());
        assertEquals(0755, centralDirectoryMode(Files.readAllBytes(result.archive()), "mvnw"));
        assertArrayEquals(Files.readAllBytes(specification),
                Files.readAllBytes(result.projectRoot().resolve("openapi/source.yaml")));

        JsonNode manifest = result.manifest();
        assertEquals(profile.id(), manifest.path("targetProfileId").asText());
        assertEquals(profile.javaFeature(), manifest.path("javaVersion").asInt());
        assertEquals("MAVEN", manifest.path("buildTool").path("type").asText());
        assertEquals("3.9.16", manifest.path("buildTool").path("distributionVersion").asText());
        assertEquals("3.3.4", manifest.path("buildTool").path("wrapperVersion").asText());
        assertFalse(manifest.has("gradleVersion"));
        assertEquals(result.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(independentSourceChecksum(result.projectRoot()), result.sourceChecksum());

        JsonNode report = result.report();
        assertEquals("VALIDATED", report.path("status").asText());
        assertEquals(List.of(
                "COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL"),
                stageNames(report));
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                report.path("stages").findValuesAsText("status"));

        String pom = Files.readString(result.projectRoot().resolve("pom.xml"), UTF_8);
        assertTrue(pom.contains("<maven.compiler.release>" + profile.javaFeature()
                + "</maven.compiler.release>"));
        assertTrue(pom.contains("<spring-ai.version>" + profile.springAiVersion()
                + "</spring-ai.version>"));
        assertTrue(Files.readString(result.projectRoot().resolve("README.md"), UTF_8)
                .contains("- Maven 3.9.16 (Wrapper 3.3.4)"));
        assertEquals("""
                FROM %s
                WORKDIR /app
                COPY target/weather-mcp-server.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """.formatted(profile.containerImage()),
                Files.readString(result.projectRoot().resolve("Dockerfile"), UTF_8));
        assertFalse(outputPaths.stream().anyMatch(path -> path.startsWith("gradle")
                || path.equals("build.gradle.kts") || path.equals("settings.gradle.kts")));
    }

    private void assertWebFluxReleaseContract(
            GenerationResult result,
            ProfileCase profile) throws Exception {
        assertTrue(Files.isDirectory(result.projectRoot()));
        assertTrue(Files.isRegularFile(result.archive()));
        assertEquals(regularFiles(result.projectRoot()), result.archiveEntries().keySet());
        assertEquals(profile.id(), result.manifest().path("targetProfileId").asText());
        assertEquals(profile.buildTool(), result.manifest().path("buildTool").path("type").asText());
        assertEquals("VALIDATED", result.report().path("status").asText());
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                result.report().path("stages").findValuesAsText("status"));
        assertEquals(List.of(TOOL_NAME), result.report().path("tools").findValuesAsText("name"));

        Path project = result.projectRoot();
        assertTrue(Files.isRegularFile(project.resolve(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolSpecifications.java")));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java")));
        assertFalse(Files.exists(project.resolve(
                "src/main/java/com/example/weather/runtime/ToolArgumentContext.java")));
        String applicationYaml = Files.readString(project.resolve("src/main/resources/application.yml"), UTF_8);
        assertTrue(applicationYaml.contains("type: ASYNC"), applicationYaml);
        assertTrue(applicationYaml.contains("protocol: STREAMABLE"), applicationYaml);
        String build = Files.readString(project.resolve(
                "MAVEN".equals(profile.buildTool()) ? "pom.xml" : "build.gradle.kts"), UTF_8);
        assertTrue(build.contains("spring-ai-starter-mcp-server-webflux"), build);
        assertTrue(build.contains("spring-boot-starter-webflux"), build);
        assertFalse(build.contains("spring-ai-starter-mcp-server-webmvc"), build);
        for (String source : mainSourceFiles(project).values()) {
            assertFalse(source.contains("RestClient"), source);
            assertFalse(source.contains(".block("), source);
            assertFalse(source.contains("boundedElastic"), source);
        }
    }

    private void assertEquivalentRepresentativeGeneration(GenerationResult result) {
        JsonNode mapping = result.manifest().path("operationMappings").get(0);
        assertEquals("getCustomers", mapping.path("operationId").asText());
        assertEquals("sample_customers_get_customers", mapping.path("toolName").asText());
        assertEquals("GENERIC_JSON", mapping.path("output").path("mode").asText());
        assertEquals("VALIDATED", result.report().path("status").asText());
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                result.report().path("stages").findValuesAsText("status"));
        assertEquals("Representative MCP Tool call matched the mock upstream contract",
                stage(result.report(), "MCP_TOOL_CALL").path("summary").asText());
        assertEquals(List.of("sample_customers_get_customers"),
                result.report().path("tools").findValuesAsText("name"));
        assertTrue(result.report().path("tools").get(0).path("inputSchemaPresent").asBoolean());
    }

    private void assertEquivalentNullableGeneration(GenerationResult result) throws IOException {
        JsonNode mapping = result.manifest().path("operationMappings").get(0);
        assertEquals("cancelOrderItems", mapping.path("operationId").asText());
        assertEquals("sample_orders_cancel_order_items", mapping.path("toolName").asText());
        assertEquals("VALIDATED", result.report().path("status").asText());
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                result.report().path("stages").findValuesAsText("status"));
        assertEquals("Representative MCP Tool call matched the mock upstream contract",
                stage(result.report(), "MCP_TOOL_CALL").path("summary").asText());
        String callbacks = Files.readString(result.projectRoot().resolve(
                "src/main/java/com/example/orders/generated/tool/OrdersMcpToolCallbacks.java"), UTF_8);
        assertTrue(callbacks.contains("\\\"minItems\\\":1"), callbacks);
        assertTrue(callbacks.contains("\\\"type\\\":\\\"null\\\""), callbacks);
    }

    private Map<String, String> mainSourceFiles(Path projectRoot) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Path main = projectRoot.resolve("src/main");
        try (var paths = Files.walk(main)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                sources.put(main.relativize(path).toString().replace('\\', '/'), Files.readString(path, UTF_8));
            }
        }
        return Map.copyOf(sources);
    }

    private List<String> stageNames(JsonNode report) {
        return report.path("stages").findValuesAsText("stage");
    }

    private List<String> iterable(Iterator<String> values) {
        List<String> result = new ArrayList<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private JsonNode stage(JsonNode report, String stageName) {
        for (JsonNode stage : report.path("stages")) {
            if (stageName.equals(stage.path("stage").textValue())) {
                return stage;
            }
        }
        throw new AssertionError("Missing validation stage: " + stageName);
    }

    private void assertSensitiveValuesAbsent(GenerationResult result, String... sensitiveValues) throws IOException {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(result.stdout().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.stderr().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.manifest().toString().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.report().toString().contains(sensitiveValue), sensitiveValue);
            try (var paths = Files.walk(result.projectRoot())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(new String(Files.readAllBytes(path), UTF_8).contains(sensitiveValue),
                            result.projectRoot().relativize(path) + ": " + sensitiveValue);
                }
            }
            result.archiveEntries().forEach((path, bytes) ->
                    assertFalse((path + new String(bytes, UTF_8)).contains(sensitiveValue),
                            path + ": " + sensitiveValue));
        }
    }

    private void assertOperationalDetailsAbsent(GenerationResult result) throws IOException {
        String userName = System.getProperty("user.name", "");
        assertArtifactValuesAbsent(result, userName);
        assertSensitiveValuesAbsent(result,
                System.getProperty("java.home", ""),
                "BUILD SUCCESSFUL",
                "> Task :",
                "java.specification.version =",
                "Tomcat started on port",
                "-Dorg.gradle.java.installations.paths=",
                "classes test bootJar",
                "--no-daemon");
    }

    private void assertIndependentLiveMcpContract(
            GenerationResult result,
            ProfileCase profile,
            TargetJavaHomes targetJavaHomes) throws Exception {
        Path targetJavaHome = profile.javaFeature() == 17
                ? targetJavaHomes.java17Home()
                : targetJavaHomes.java21Home();
        Path targetJava = javaExecutable(targetJavaHome);
        assertTrue(Files.isRegularFile(targetJava), "target Java executable is unavailable");
        buildBootJar(result.projectRoot(), targetJavaHome);

        int applicationPort = reserveLoopbackPort();
        try (IndependentUpstreamRecorder upstream = IndependentUpstreamRecorder.start();
                ObservedProcess application = ObservedProcess.start(applicationProcess(
                        result.projectRoot(), targetJava, applicationPort, upstream.baseUri()))) {
            awaitApplication(application, applicationPort);
            new RawMcpClient(URI.create("http://127.0.0.1:" + applicationPort + "/mcp"))
                    .validate(expectedInputSchema(), normalizedResult());
            assertIndependentMetrics(applicationPort, profile);
            upstream.sealAndAssert(Duration.ofMillis(250));
        }
    }

    private void assertIndependentMetrics(int applicationPort, ProfileCase profile) throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + applicationPort + "/actuator/prometheus");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(UTF_8));
        assertEquals(200, response.statusCode(), "Prometheus endpoint is not available");
        String scrape = response.body();
        Set<String> helpNames = new TreeSet<>();
        scrape.lines()
                .filter(line -> line.startsWith("# HELP gen2spring_runtime_"))
                .map(line -> line.split(" ", 3)[2].split(" ", 2)[0])
                .forEach(helpNames::add);
        assertEquals(Set.of(
                "gen2spring_runtime_mcp_tool_call_seconds",
                "gen2spring_runtime_mcp_tool_call_seconds_max",
                "gen2spring_runtime_provider_request_seconds",
                "gen2spring_runtime_provider_request_seconds_max",
                "gen2spring_runtime_provider_response_bytes",
                "gen2spring_runtime_provider_response_bytes_max",
                "gen2spring_runtime_provider_executor_active",
                "gen2spring_runtime_provider_executor_queued"), helpNames);

        String toolCount = metricSample(
                scrape, "gen2spring_runtime_mcp_tool_call_seconds_count", "outcome=\"success\"");
        String providerSuccessCount = metricSample(
                scrape, "gen2spring_runtime_provider_request_seconds_count", "http_status_class=\"2xx\"");
        String providerRetryCount = metricSample(
                scrape, "gen2spring_runtime_provider_request_seconds_count", "http_status_class=\"5xx\"");
        String responseSuccessCount = metricSample(
                scrape, "gen2spring_runtime_provider_response_bytes_count", "http_status_class=\"2xx\"");
        String responseRetryCount = metricSample(
                scrape, "gen2spring_runtime_provider_response_bytes_count", "http_status_class=\"5xx\"");
        assertEquals(1.0, metricValue(toolCount));
        assertEquals(2.0, metricValue(providerSuccessCount));
        assertEquals(1.0, metricValue(providerRetryCount));
        assertEquals(2.0, metricValue(responseSuccessCount));
        assertEquals(1.0, metricValue(responseRetryCount));
        assertTrue(toolCount.contains("target_profile=\"" + profile.id() + "\""), toolCount);
        assertTrue(toolCount.contains("error_category=\"none\""), toolCount);
        assertTrue(providerSuccessCount.contains("outcome=\"success\""), providerSuccessCount);
        assertTrue(providerSuccessCount.contains("error_category=\"none\""), providerSuccessCount);
        assertTrue(providerRetryCount.contains("outcome=\"expected_error\""), providerRetryCount);
        assertTrue(providerRetryCount.contains("error_category=\"upstream_server\""), providerRetryCount);
        assertFalse(scrape.contains("gen2spring_runtime_mcp_tool_call_active"), scrape);
        assertFalse(scrape.contains("gen2spring_runtime_provider_request_active"), scrape);
        String custom = scrape.lines()
                .filter(line -> line.contains("gen2spring_runtime_"))
                .collect(Collectors.joining("\n"));
        assertFalse(custom.contains("error=\""), custom);
        assertFalse(custom.contains(LIVE_QUERY_SECRET), custom);
        assertFalse(custom.contains(LIVE_HEADER_SECRET), custom);
        assertFalse(custom.contains(REPRESENTATIVE_STATION_ID), custom);
        assertFalse(custom.contains("NORMAL_SERVICE"), custom);
        assertFalse(custom.contains("sunny"), custom);
        assertFalse(custom.contains("/stations/"), custom);
    }

    private String metricSample(String scrape, String name, String requiredTag) {
        List<String> matches = scrape.lines()
                .filter(line -> line.startsWith(name + "{"))
                .filter(line -> line.contains(requiredTag))
                .toList();
        assertEquals(1, matches.size(), name + " " + requiredTag);
        return matches.get(0);
    }

    private double metricValue(String sample) {
        return Double.parseDouble(sample.substring(sample.lastIndexOf(' ') + 1));
    }

    private ProcessBuilder applicationProcess(
            Path projectRoot,
            Path targetJava,
            int applicationPort,
            URI upstreamBaseUri) {
            ProcessBuilder launch = new ProcessBuilder(
                    targetJava.toString(),
                    "-jar",
                    projectRoot.resolve("build/libs/weather-mcp-server.jar").toString(),
                    "--server.address=127.0.0.1",
                    "--server.port=" + applicationPort,
                    "--management.endpoints.web.exposure.include=health,prometheus",
                    "--management.prometheus.metrics.export.enabled=true",
                    "--management.tracing.sampling.probability=1.0");
        launch.environment().put("PROVIDER_BASE_URL", upstreamBaseUri.toString());
        launch.environment().put("KMA_SERVICE_KEY", LIVE_QUERY_SECRET);
        launch.environment().put("WEATHER_HEADER_KEY", LIVE_HEADER_SECRET);
        launch.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        launch.redirectError(ProcessBuilder.Redirect.DISCARD);
        return launch;
    }

    private void buildBootJar(Path projectRoot, Path targetJavaHome) throws Exception {
        Path gradle = gradleWrapper(projectRoot);
        assertTrue(Files.isRegularFile(gradle), "generated Gradle wrapper is unavailable");
        ProcessBuilder processBuilder = new ProcessBuilder(
                gradle.toString(),
                "-Dorg.gradle.java.installations.auto-detect=false",
                "-Dorg.gradle.java.installations.auto-download=false",
                "-Dorg.gradle.java.installations.paths=" + targetJavaHome,
                "bootJar",
                "--no-daemon",
                "--non-interactive")
                .directory(projectRoot.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try (ObservedProcess build = ObservedProcess.start(processBuilder)) {
            assertEquals(0, build.await(Duration.ofMinutes(5), "generated project build timed out safely"),
                    "generated project build failed safely");
        }
        assertTrue(Files.isRegularFile(projectRoot.resolve("build/libs/weather-mcp-server.jar")),
                "generated boot JAR is unavailable");
    }

    private Path gradleWrapper(Path projectRoot) {
        return projectRoot.resolve(isWindows() ? "gradlew.bat" : "gradlew");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private void awaitApplication(ObservedProcess application, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
            application.observe();
            if (!application.isRootAlive()) {
                throw new AssertionError("generated application exited before readiness");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException unavailable) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("generated application readiness timed out safely");
    }

    private int reserveLoopbackPort() throws IOException {
        try (ServerSocket reservation = new ServerSocket(
                0, 1, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            reservation.setReuseAddress(false);
            return reservation.getLocalPort();
        }
    }

    private JsonNode expectedInputSchema() {
        return jsonLiteral("""
                {
                  "type": "object",
                  "properties": {
                    "clientVersion": {"type": "string", "description": "Calling client version."},
                    "days": {"type": "integer", "format": "int32", "minimum": 1, "maximum": 7,
                      "description": "Number of forecast days."},
                    "latitude": {"type": "number", "minimum": -90, "maximum": 90,
                      "description": "Latitude."},
                    "longitude": {"type": "number", "minimum": -180, "maximum": 180,
                      "description": "Longitude."},
                    "mode": {"type": "string", "enum": ["brief", "full-detail"],
                      "description": "Forecast detail mode."},
                    "stationId": {"type": "string", "minLength": 2, "maxLength": 12,
                      "pattern": "^[A-Z0-9]+$", "description": "Station identifier."},
                    "tags": {"type": "array", "items": {"type": "string"},
                      "description": "Optional forecast tags."}
                  },
                  "required": ["days", "latitude", "longitude", "stationId"]
                }
                """);
    }

    private static JsonNode firstPageResponse() {
        return jsonLiteral("""
                {"response": {
                  "header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE", "rawHeader": true},
                  "body": {"items": {"item": [{"forecast": "sunny"}]},
                    "nextCursor": "page-2", "totalCount": 2, "rawBody": true}
                }, "rawRoot": true}
                """);
    }

    private static JsonNode secondPageResponse() {
        return jsonLiteral("""
                {"response": {
                  "header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE", "rawHeader": true},
                  "body": {"items": {"item": [{"forecast": "rainy"}]},
                    "nextCursor": null, "totalCount": 2, "rawBody": true}
                }, "rawRoot": true}
                """);
    }

    private JsonNode normalizedResult() {
        return jsonLiteral("""
                {"data": [{"forecast": "sunny"}, {"forecast": "rainy"}],
                 "page": {"totalCount": 2},
                 "provider": {"code": "00", "message": "NORMAL_SERVICE"}}
                """);
    }

    private static JsonNode jsonLiteral(String value) {
        try {
            return JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (IOException exception) {
            throw new AssertionError("independent JSON fixture is invalid", exception);
        }
    }

    private void assertArtifactValuesAbsent(GenerationResult result, String... sensitiveValues) throws IOException {
        for (String sensitiveValue : sensitiveValues) {
            if (sensitiveValue == null || sensitiveValue.isBlank()) {
                continue;
            }
            assertFalse(result.manifest().toString().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.report().toString().contains(sensitiveValue), sensitiveValue);
            try (var paths = Files.walk(result.projectRoot())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(new String(Files.readAllBytes(path), UTF_8).contains(sensitiveValue),
                            result.projectRoot().relativize(path) + ": " + sensitiveValue);
                }
            }
            result.archiveEntries().forEach((path, bytes) ->
                    assertFalse((path + new String(bytes, UTF_8)).contains(sensitiveValue),
                            path + ": " + sensitiveValue));
        }
    }

    private void assertFixturesDifferOnlyByProfile() throws Exception {
        assertFixtureDiffersOnlyByProfile(SPRING_AI_1_JAVA_17, SPRING_AI_2_JAVA_17);
        assertFixtureDiffersOnlyByProfile(SPRING_AI_1_JAVA_21, SPRING_AI_2_JAVA_21);
        assertFixtureDiffersOnlyByProfile(SPRING_AI_2_JAVA_17, SPRING_AI_2_JAVA_21);
    }

    private void assertFixtureDiffersOnlyByProfile(ProfileCase actual, ProfileCase reference) throws Exception {
        String actualFixture = Files.readString(resource(actual.configurationResource()), UTF_8);
        String referenceFixture = Files.readString(resource(reference.configurationResource()), UTF_8);
        assertEquals(referenceFixture.replace(reference.id(), actual.id()), actualFixture);
    }

    private Path requiredJava17Home() {
        String configured = System.getenv(JAVA_17_HOME);
        assertNotNull(configured, JAVA_17_HOME + " must be forwarded to the integration test");
        assertFalse(configured.isBlank(), JAVA_17_HOME + " must not be blank");
        Path home = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(javaExecutable(home)), JAVA_17_HOME);
        return home;
    }

    private TargetJavaHomes targetJavaHomes() {
        Path java17Home = requiredJava17Home();
        String configuredJava21Home = System.getenv(JAVA_21_HOME);
        Path java21Home;
        if (configuredJava21Home == null || configuredJava21Home.isBlank()) {
            assertEquals(21, Runtime.version().feature(),
                    JAVA_21_HOME + " is required when the integration-test JVM is not Java 21");
            java21Home = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        } else {
            java21Home = Path.of(configuredJava21Home).toAbsolutePath().normalize();
        }
        assertTrue(Files.isRegularFile(javaExecutable(java21Home)), JAVA_21_HOME);
        return new TargetJavaHomes(java17Home, java21Home);
    }

    private Path javaExecutable(Path javaHome) {
        return javaHome.resolve(isWindows() ? "bin/java.exe" : "bin/java");
    }

    private void assertCanonicalArchiveEntriesEqual(
            Map<String, byte[]> first,
            Map<String, byte[]> second) {
        assertEquals(first.keySet(), second.keySet());
        first.forEach((path, bytes) -> {
            if (!"VALIDATION_REPORT.json".equals(path)) {
                assertArrayEquals(bytes, second.get(path), path);
            }
        });
    }

    private void assertValidationReportsEqualExceptMeasurements(JsonNode first, JsonNode second) {
        assertEquals(
                validationReportWithoutMeasurements(first),
                validationReportWithoutMeasurements(second),
                "validation reports differ outside measured fields");
    }

    private JsonNode validationReportWithoutMeasurements(JsonNode report) {
        JsonNode normalized = report.deepCopy();
        assertTrue(normalized.isObject(), "validation report must be an object");
        assertTrue(normalized.path("stages").isArray(), "validation report stages must be an array");
        for (JsonNode stage : normalized.path("stages")) {
            assertTrue(stage.isObject(), "validation report stage must be an object");
            JsonNode duration = stage.get("durationMillis");
            assertTrue(duration != null && duration.isIntegralNumber() && duration.longValue() >= 0,
                    "validation report durationMillis must be a non-negative integer");
            JsonNode summary = stage.get("summary");
            assertTrue(summary != null && summary.isTextual(),
                    "validation report summary must be a string");
            ((com.fasterxml.jackson.databind.node.ObjectNode) stage).put("durationMillis", 0);
            ((com.fasterxml.jackson.databind.node.ObjectNode) stage).put(
                    "summary",
                    summary.textValue().replaceAll("\\bstdoutBytes=\\d+\\b", "stdoutBytes=<measured>")
                            .replaceAll("\\bstderrBytes=\\d+\\b", "stderrBytes=<measured>"));
        }
        return normalized;
    }

    private void assertTransientBuildOutputsAbsent(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                assertFalse(relative.equals("build") || relative.startsWith("build/"), relative);
                assertFalse(relative.equals(".gradle") || relative.startsWith(".gradle/"), relative);
                assertFalse(relative.equals("process-logs") || relative.startsWith("process-logs/"), relative);
                assertFalse(relative.startsWith(".gradlew-validated-"), relative);
            }
        }
    }

    private Set<String> regularFiles(Path root) throws IOException {
        Set<String> files = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> files.add(root.relativize(path).toString().replace('\\', '/')));
        }
        return files;
    }

    private Map<String, byte[]> readArchive(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(archive)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertFalse(entry.isDirectory());
                assertFalse(entry.getName().startsWith("/") || entry.getName().contains("../"));
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
                assertEquals(null, entries.put(entry.getName(), zip.readAllBytes()), entry.getName());
            }
        }
        return entries;
    }

    private String independentSourceChecksum(Path root) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.equals("GENERATION_MANIFEST.json")
                        || relative.equals("VALIDATION_REPORT.json")
                        || relative.equals("process-logs")
                        || relative.startsWith("process-logs/")) {
                    continue;
                }
                byte[] name = relative.getBytes(UTF_8);
                byte[] content = normalizeLineEndings(Files.readAllBytes(path));
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] normalizeLineEndings(byte[] input) {
        byte[] normalized = new byte[input.length];
        int write = 0;
        for (int read = 0; read < input.length; read++) {
            if (input[read] == '\r') {
                if (read + 1 < input.length && input[read + 1] == '\n') {
                    read++;
                }
                normalized[write++] = '\n';
            } else {
                normalized[write++] = input[read];
            }
        }
        return Arrays.copyOf(normalized, write);
    }

    private int centralDirectoryMode(byte[] archive, String expectedName) {
        int end = findSignatureFromEnd(archive, 0x06054b50);
        int count = unsignedShort(archive, end + 10);
        int offset = littleEndianInt(archive, end + 16);
        for (int index = 0; index < count; index++) {
            assertEquals(0x02014b50, littleEndianInt(archive, offset));
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            String name = new String(archive, offset + 46, nameLength, UTF_8);
            if (name.equals(expectedName)) {
                return littleEndianInt(archive, offset + 38) >>> 16 & 0777;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("Missing central directory entry: " + expectedName);
    }

    private int findSignatureFromEnd(byte[] bytes, int signature) {
        for (int index = bytes.length - 4; index >= 0; index--) {
            if (littleEndianInt(bytes, index) == signature) {
                return index;
            }
        }
        throw new AssertionError("ZIP signature is missing");
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | unsignedShort(bytes, offset + 2) << 16;
    }

    private String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private Path resource(String name) throws URISyntaxException {
        var resource = getClass().getClassLoader().getResource(name);
        assertNotNull(resource, name);
        return Path.of(resource.toURI());
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate the repository root");
        }
        return current;
    }

    private JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(Files.readAllBytes(path));
    }

    private static final class RawMcpClient {
        private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

        private final URI endpoint;
        private final HttpClient client;

        private RawMcpClient(URI endpoint) {
            this.endpoint = endpoint;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        private void validate(JsonNode expectedSchema, JsonNode expectedResult) throws Exception {
            HttpResponse<byte[]> initialize = send("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-03-26","capabilities":{},
                      "clientInfo":{"name":"profile-acceptance-oracle","version":"1"}}}
                    """, null);
            JsonNode initializeResult = rpcResult(initialize, 1);
            assertEquals("2025-03-26", initializeResult.path("protocolVersion").textValue());
            assertTrue(initializeResult.path("capabilities").path("tools").isObject(),
                    "MCP tools capability is missing");
            assertTrue(initializeResult.path("serverInfo").path("name").isTextual(),
                    "MCP server name is missing");
            assertTrue(initializeResult.path("serverInfo").path("version").isTextual(),
                    "MCP server version is missing");
            String session = session(initialize, null);

            HttpResponse<byte[]> initialized = send(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session);
            assertSuccess(initialized);
            session = session(initialized, session);

            JsonNode toolsResult = rpcResult(send(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                    session), 2);
            JsonNode tools = toolsResult.get("tools");
            assertTrue(tools != null && tools.isArray(), "MCP tools payload is invalid");
            assertEquals(1, tools.size(), "MCP tool count does not match the fixture");
            JsonNode tool = tools.get(0);
            assertEquals(TOOL_NAME, tool.path("name").textValue());
            assertEquals(TOOL_DESCRIPTION, tool.path("description").textValue());
            assertEquals(expectedSchema, tool.get("inputSchema"), "MCP Tool input schema mismatch");

            JsonNode callResult = rpcResult(send("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"kma_weather_get_forecast","arguments":{
                        "stationId":"STN01","days":3,"latitude":37.5,"longitude":127.0}}}
                    """, session), 3);
            assertSuccessfulToolCallResult(callResult);
            JsonNode content = callResult.get("content");
            assertTrue(content != null && content.isArray(), "MCP Tool content is invalid");
            assertEquals(1, content.size(), "MCP Tool must return exactly one content item");
            JsonNode text = content.get(0);
            assertEquals("text", text.path("type").textValue());
            assertTrue(text.path("text").isTextual(), "MCP Tool text payload is missing");
            JsonNode payload = strictJson(text.path("text").textValue());
            assertEquals(expectedResult, payload, "normalized Tool result mismatch");
            assertFalse(payload.has("response"), "raw provider envelope leaked into the Tool result");
            assertTrue(payload.findValue("rawRoot") == null, "raw root sentinel leaked into the Tool result");
            assertTrue(payload.findValue("rawHeader") == null, "raw header sentinel leaked into the Tool result");
            assertTrue(payload.findValue("rawBody") == null, "raw body sentinel leaked into the Tool result");
        }

        private void assertSuccessfulToolCallResult(JsonNode callResult) {
            JsonNode isError = callResult.get("isError");
            assertTrue(isError != null, "MCP Tool result isError is missing");
            assertTrue(isError.isBoolean(), "MCP Tool result isError must be boolean");
            assertFalse(isError.booleanValue(), "MCP Tool call reported an error");
        }

        private HttpResponse<byte[]> send(String body, String session) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8));
            if (session != null) {
                request.header("Mcp-Session-Id", session);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        private JsonNode rpcResult(HttpResponse<byte[]> response, long expectedId) throws IOException {
            assertSuccess(response);
            JsonNode root = responseJson(response);
            assertTrue(root.isObject(), "JSON-RPC response is invalid");
            assertEquals("2.0", root.path("jsonrpc").textValue());
            assertFalse(root.hasNonNull("error"), "JSON-RPC response reported an error");
            assertTrue(root.path("id").isIntegralNumber(), "JSON-RPC response ID is invalid");
            assertEquals(expectedId, root.path("id").longValue());
            JsonNode result = root.get("result");
            assertTrue(result != null && result.isObject(), "JSON-RPC result is invalid");
            return result;
        }

        private void assertSuccess(HttpResponse<?> response) {
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                    "MCP response status is unsuccessful");
        }

        private String session(HttpResponse<?> response, String fallback) {
            String value = response.headers().firstValue("Mcp-Session-Id").orElse(fallback);
            assertTrue(value != null && !value.isBlank(), "MCP session identifier is missing");
            return value;
        }

        private JsonNode responseJson(HttpResponse<byte[]> response) throws IOException {
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String payload;
            if ("application/json".equals(contentType)) {
                payload = new String(response.body(), UTF_8);
            } else if ("text/event-stream".equals(contentType)) {
                payload = firstSseData(response.body());
            } else {
                throw new AssertionError("MCP response content type is unsupported");
            }
            return strictJson(payload);
        }

        private String firstSseData(byte[] body) {
            List<String> data = new ArrayList<>();
            String text = new String(body, UTF_8).replace("\r\n", "\n");
            for (String line : text.split("\n", -1)) {
                if (line.isEmpty() && !data.isEmpty()) {
                    return String.join("\n", data);
                }
                if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    data.add(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            if (!data.isEmpty()) {
                return String.join("\n", data);
            }
            throw new AssertionError("MCP SSE response contains no data event");
        }

        private JsonNode strictJson(String value) throws IOException {
            return JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        }
    }

    private static final class IndependentUpstreamRecorder implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private int requestCount;
        private final List<RecordedRequest> requests = new ArrayList<>();
        private boolean captureFailed;
        private boolean sealed;
        private boolean lateRequest;

        private IndependentUpstreamRecorder(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static IndependentUpstreamRecorder start() throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            IndependentUpstreamRecorder recorder = new IndependentUpstreamRecorder(server, executor);
            server.createContext("/", recorder::handle);
            server.setExecutor(executor);
            server.start();
            return recorder;
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) {
            int status;
            byte[] body;
            try (exchange) {
                try {
                    int sequence;
                    byte[] requestBody = exchange.getRequestBody().readAllBytes();
                    RecordedRequest captured = new RecordedRequest(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getRawPath(),
                            query(exchange.getRequestURI().getRawQuery()),
                            exchange.getRequestHeaders().getFirst("X-Weather-Key"),
                            List.copyOf(exchange.getRequestHeaders().getOrDefault("traceparent", List.of())),
                            propagationHeaders(exchange),
                            requestBody.length == 0 ? null : JSON.reader()
                                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .readTree(requestBody));
                    synchronized (this) {
                        requestCount++;
                        sequence = requestCount;
                        lateRequest |= sealed;
                        requests.add(captured);
                        status = sequence == 1 ? 503 : sequence <= 3 ? 200 : 500;
                    }
                    body = sequence == 1
                            ? "{\"retryable\":true}".getBytes(UTF_8)
                            : sequence == 2
                                    ? JSON.writeValueAsBytes(firstPageResponse())
                                    : sequence == 3
                                            ? JSON.writeValueAsBytes(secondPageResponse())
                                            : "{}".getBytes(UTF_8);
                } catch (IOException | RuntimeException failure) {
                    synchronized (this) {
                        requestCount++;
                        lateRequest |= sealed;
                        captureFailed = true;
                    }
                    status = 500;
                    body = "{}".getBytes(UTF_8);
                }
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    synchronized (this) {
                        captureFailed = true;
                    }
                }
            }
        }

        private Set<String> propagationHeaders(HttpExchange exchange) {
            Set<String> result = new TreeSet<>();
            exchange.getRequestHeaders().keySet().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(name -> name.equals("traceparent")
                            || name.equals("tracestate")
                            || name.equals("baggage")
                            || name.equals("b3")
                            || name.startsWith("x-b3-"))
                    .forEach(result::add);
            return Set.copyOf(result);
        }

        private Map<String, List<String>> query(String rawQuery) {
            if (rawQuery == null || rawQuery.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String pair : rawQuery.split("&", -1)) {
                int separator = pair.indexOf('=');
                String name = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator), UTF_8);
                String value = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1), UTF_8);
                result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
            result.replaceAll((ignored, values) -> List.copyOf(values));
            return Map.copyOf(result);
        }

        private void sealAndAssert(Duration lateRequestWindow) throws Exception {
            synchronized (this) {
                sealed = true;
            }
            Thread.sleep(lateRequestWindow.toMillis());
            List<RecordedRequest> captured;
            int count;
            boolean failed;
            boolean late;
            synchronized (this) {
                captured = List.copyOf(requests);
                count = requestCount;
                failed = captureFailed;
                late = lateRequest;
            }
            assertFalse(failed, "upstream request capture failed safely");
            assertFalse(late, "upstream request arrived after observation was sealed");
            assertEquals(3, count, "upstream request count must be exactly three");
            assertEquals(3, captured.size(), "upstream request sequence is incomplete");
            for (int index = 0; index < captured.size(); index++) {
                RecordedRequest request = captured.get(index);
                assertEquals("GET", request.method(), "upstream request method mismatch at " + index);
                assertEquals("/stations/STN01/forecast", request.rawPath());
                assertEquals(Set.of("cursor", "days", "latitude", "longitude", "serviceKey"),
                        request.query().keySet());
                assertEquals(List.of("3"), request.query().get("days"));
                assertEquals(List.of("37.5"), request.query().get("latitude"));
                assertEquals(List.of("127.0"), request.query().get("longitude"));
                assertEquals(List.of(index < 2 ? "page-1" : "page-2"), request.query().get("cursor"));
                assertTrue(List.of(LIVE_QUERY_SECRET).equals(request.query().get("serviceKey")),
                        "upstream query secret does not match");
                assertTrue(LIVE_HEADER_SECRET.equals(request.weatherHeader()),
                        "upstream header secret does not match");
                assertEquals(Set.of("traceparent"), request.propagationHeaders());
                assertEquals(1, request.traceparent().size(), "upstream traceparent must occur exactly once");
                String traceparent = request.traceparent().get(0);
                assertTrue(traceparent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"),
                        "upstream traceparent is invalid");
                assertFalse(traceparent.contains("00000000000000000000000000000000"),
                        "upstream trace ID is invalid");
                assertFalse(traceparent.contains("-0000000000000000-"),
                        "upstream span ID is invalid");
                assertNull(request.body(), "GET request body must be absent");
            }
        }

        @Override
        public void close() throws InterruptedException {
            server.stop(0);
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("upstream recorder cleanup timed out safely");
            }
            synchronized (this) {
                if (sealed && (lateRequest || requestCount != 3)) {
                    throw new AssertionError("upstream request count changed after observation was sealed");
                }
            }
        }
    }

    private static final class ObservedProcess implements AutoCloseable {
        private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
        private static final Duration DESCENDANT_GRACE = Duration.ofSeconds(2);
        private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
        private static final Duration FORCED_TERMINATION_GRACE = Duration.ofSeconds(10);

        private final Process process;
        private final Map<Long, ProcessHandle> observed = new LinkedHashMap<>();

        private ObservedProcess(Process process) {
            this.process = process;
            observe();
        }

        private static ObservedProcess start(ProcessBuilder builder) throws IOException {
            return new ObservedProcess(builder.start());
        }

        private int await(Duration timeout, String timeoutMessage) throws InterruptedException {
            long deadline = deadline(timeout);
            while (process.isAlive() && System.nanoTime() < deadline) {
                observe();
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            observe();
            if (process.isAlive()) {
                terminate();
                throw new AssertionError(timeoutMessage);
            }
            if (!awaitObservedExit(DESCENDANT_GRACE)) {
                terminate();
                throw new AssertionError("process left a running descendant safely");
            }
            return process.exitValue();
        }

        private void observe() {
            observed.put(process.pid(), process.toHandle());
            if (process.isAlive()) {
                process.descendants().forEach(handle -> observed.put(handle.pid(), handle));
            }
        }

        private boolean isRootAlive() {
            return process.isAlive();
        }

        private InputStream stdout() {
            return process.getInputStream();
        }

        private InputStream stderr() {
            return process.getErrorStream();
        }

        private void terminate() throws InterruptedException {
            observe();
            destroyObserved(false);
            if (awaitObservedExit(TERMINATION_GRACE)) {
                return;
            }
            observe();
            destroyObserved(true);
            if (!awaitObservedExit(FORCED_TERMINATION_GRACE)) {
                throw new AssertionError("process tree cleanup timed out safely");
            }
        }

        private void destroyObserved(boolean forcibly) {
            List<ProcessHandle> handles = new ArrayList<>(observed.values());
            handles.sort(Comparator.comparingInt(handle -> handle.pid() == process.pid() ? 1 : 0));
            for (ProcessHandle handle : handles) {
                if (handle.isAlive()) {
                    if (forcibly) {
                        handle.destroyForcibly();
                    } else {
                        handle.destroy();
                    }
                }
            }
        }

        private boolean awaitObservedExit(Duration timeout) throws InterruptedException {
            long deadline = deadline(timeout);
            while (System.nanoTime() < deadline) {
                observe();
                if (observed.values().stream().noneMatch(ProcessHandle::isAlive)) {
                    return true;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            observe();
            return observed.values().stream().noneMatch(ProcessHandle::isAlive);
        }

        private static long deadline(Duration timeout) {
            long now = System.nanoTime();
            long nanos = timeout.toNanos();
            return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
        }

        @Override
        public void close() throws Exception {
            if (observed.values().stream().anyMatch(ProcessHandle::isAlive)) {
                terminate();
            }
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    private record RecordedRequest(
            String method,
            String rawPath,
            Map<String, List<String>> query,
            String weatherHeader,
            List<String> traceparent,
            Set<String> propagationHeaders,
            JsonNode body) {}

    private record GenerationResult(
            Path projectRoot,
            Path archive,
            String sourceChecksum,
            JsonNode manifest,
            JsonNode report,
            Map<String, byte[]> archiveEntries,
            String stdout,
            String stderr) {}

    private record InstalledCliResult(int exitCode, String stdout, String stderr) {}

    private record ProfileCase(
            String id,
            String generatorModule,
            String templateVersion,
            String springBootVersion,
            String springAiVersion,
            int javaFeature,
            String buildTool,
            String distributionVersion,
            String wrapperVersion,
            String containerImage,
            String configurationResource) {
        boolean webFlux() {
            return id.contains("-webflux-");
        }

        String webStack() {
            return webFlux() ? "WEBFLUX" : "MVC";
        }

        String programmingModel() {
            return webFlux() ? "ASYNC" : "SYNC";
        }
    }

    private record TargetJavaHomes(Path java17Home, Path java21Home) {}
}

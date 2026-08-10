package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ProjectFileRendererTest {
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

    private final ProjectFileRenderer renderer = new ProjectFileRenderer(CompatibilityProfile.p0());

    @Test
    void rendersPinnedBuildVersions() {
        String build = renderer.buildGradle(projectCoordinates());

        assertTrue(build.contains("id(\"org.springframework.boot\") version \"4.1.0\""));
        assertTrue(build.contains("spring-ai-bom:2.0.0"));
        assertTrue(build.contains("JavaLanguageVersion.of(21)"));
        assertTrue(build.contains("spring-ai-starter-mcp-server-webmvc"));
        assertTrue(build.contains("archiveFileName.set(\"weather-mcp-server.jar\")"));
        assertFalse(build.contains("SNAPSHOT"));
        assertFalse(build.contains("latest"));
    }

    @Test
    void rendersCanonicalMetadataForBothSupportedProfiles() {
        assertProfileMetadata(profile(17), 17, JAVA_17_IMAGE);
        assertProfileMetadata(profile(21), 21, JAVA_21_IMAGE);
    }

    @Test
    void rendersARestrictedNonRootDockerContextForBothSupportedProfiles() {
        assertDockerContext(profile(17), JAVA_17_IMAGE);
        assertDockerContext(profile(21), JAVA_21_IMAGE);
    }

    @Test
    void rejectsProfilesOutsideTheSpringAi2RendererFamily() {
        CompatibilityProfile supported = profile(17);
        var target = supported.target();
        List<CompatibilityProfile> unsupported = List.of(
                copy(supported, "other-generator", target),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "3.5.16", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "4.1.0", "1.1.8", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")),
                new CompatibilityProfile(
                        supported.id(), target, supported.generatorModule(), supported.templateVersion(),
                        supported.runtimeVersion(), "8.14.3", supported.containerImage()),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        11, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP")),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "4.1.0", "2.0.0", "MAVEN", "MVC", "SYNC", "STREAMABLE_HTTP")),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "WEBFLUX", "SYNC", "STREAMABLE_HTTP")),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "ASYNC", "STREAMABLE_HTTP")),
                copy(supported, supported.generatorModule(), new CompatibilityProfile.TargetPlatform(
                        17, "4.1.0", "2.0.0", "GRADLE_KOTLIN", "MVC", "SYNC", "STDIO")));

        for (CompatibilityProfile profile : unsupported) {
            assertThrows(GeneratorException.class, () -> new ProjectFileRenderer(profile), profile.toString());
        }
    }

    @Test
    void rejectsNoncanonicalContainerImagesBeforeGeneratingFiles() {
        CompatibilityProfile supported = profile(17);
        List<CompatibilityProfile> noncanonical = List.of(
                new CompatibilityProfile(
                        supported.id(), supported.target(), supported.generatorModule(), supported.templateVersion(),
                        supported.runtimeVersion(), supported.gradleVersion(),
                        JAVA_17_IMAGE + "\nRUN echo injected"),
                new CompatibilityProfile(
                        supported.id(), supported.target(), supported.generatorModule(), supported.templateVersion(),
                        supported.runtimeVersion(), supported.gradleVersion(), "eclipse-temurin:17-jre"));

        noncanonical.forEach(this::assertGenerationRejectedAsNoncanonical);
    }

    @Test
    void rejectsDriftedTemplateAndRuntimeMetadataBeforeGeneratingFiles() {
        CompatibilityProfile supported = profile(17);
        List<CompatibilityProfile> noncanonical = List.of(
                new CompatibilityProfile(
                        supported.id(), supported.target(), supported.generatorModule(), "spring-ai-2-v2",
                        supported.runtimeVersion(), supported.gradleVersion(), supported.containerImage()),
                new CompatibilityProfile(
                        supported.id(), supported.target(), supported.generatorModule(), supported.templateVersion(),
                        "0.2.0", supported.gradleVersion(), supported.containerImage()));

        noncanonical.forEach(this::assertGenerationRejectedAsNoncanonical);
    }

    @Test
    void rejectsAContextFromAnotherSupportedProfile() {
        var java17Renderer = new ProjectFileRenderer(profile(17));

        assertThrows(GeneratorException.class,
                () -> java17Renderer.readme(contextWithSecrets(profile(21), List.of(
                        tool("service-key", "KMA_SERVICE_KEY")))));
    }

    @Test
    void rejectsKotlinBuildScriptInjectionThroughArtifactId() {
        var unsafe = new GenerationRequest.ProjectCoordinates(
                "com.example", "weather\")\nprintln(\"injected", "com.example.weather");

        assertThrows(GeneratorException.class, () -> renderer.buildGradle(unsafe));
    }

    @Test
    void rejectsKotlinDslInterpolationCharactersInGroupId() {
        var unsafe = new GenerationRequest.ProjectCoordinates(
                "com.$bad", "weather-mcp-server", "com.example.weather");

        assertThrows(GeneratorException.class, () -> renderer.buildGradle(unsafe));
    }

    @Test
    void rendersApplicationSecretsAsYamlScalars() {
        String yaml = renderer.applicationYaml(contextWithSecret("service-key"));

        assertTrue(yaml.contains("name: weather-mcp-server"));
        assertTrue(yaml.contains("mcp-endpoint: /mcp"));
        assertTrue(yaml.contains("response-max-bytes: 1048576"));
        assertTrue(yaml.contains("connect-timeout-millis: 2000"));
        assertTrue(yaml.contains("read-timeout-millis: 5000"));
        assertTrue(yaml.contains("total-timeout-millis: 10000"));
        assertTrue(yaml.contains("max-concurrent-requests: 16"));
        assertTrue(yaml.contains("max-queued-requests: 64"));
        assertTrue(yaml.contains("service-key: \"${KMA_SERVICE_KEY:}\""));
        assertTrue(yaml.contains("annotation-scanner:\n          enabled: false"));
    }

    @Test
    void disablesAnnotationScanningForConstrainedAndUnconstrainedTools() {
        String unconstrained = renderer.applicationYaml(contextWithSecret("service-key"));
        String constrained = renderer.applicationYaml(JavaSourceRendererTest.contextWithWeatherTool());

        assertTrue(unconstrained.contains("annotation-scanner:\n          enabled: false"));
        assertTrue(constrained.contains("annotation-scanner:\n          enabled: false"));
    }

    @Test
    void emitsDeterministicProjectFilesAndVerifiedWrapper() {
        var files = new SpringAi2ProjectGenerator().generate(contextWithSecret("service-key")).files();

        assertTrue(files.containsKey("build.gradle.kts"));
        assertTrue(files.containsKey("settings.gradle.kts"));
        assertTrue(files.containsKey("gradle.properties"));
        assertTrue(files.containsKey("src/main/resources/application.yml"));
        assertTrue(files.containsKey("README.md"));
        assertTrue(files.containsKey("Dockerfile"));
        assertTrue(files.containsKey(".gitignore"));
        assertTrue(new String(files.get("gradle/wrapper/gradle-wrapper.properties"), UTF_8)
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip"));
        assertTrue(new String(files.get("gradle/wrapper/gradle-wrapper.properties"), UTF_8)
                .contains("distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"));
        assertTrue(files.get("gradle/wrapper/gradle-wrapper.jar").length > 40_000);
        assertTrue(new String(files.get("README.md"), UTF_8).contains("KMA_SERVICE_KEY"));
        assertTrue(new String(files.get("Dockerfile"), UTF_8).contains("weather-mcp-server.jar"));
    }

    @Test
    void rendersTheCompleteGeneratedProjectUsageContractForBothProfiles() {
        for (int javaVersion : List.of(17, 21)) {
            CompatibilityProfile profile = profile(javaVersion);
            var files = new SpringAi2ProjectGenerator()
                    .generate(contextWithSecrets(profile, List.of(tool("service-key", "KMA_SERVICE_KEY"))))
                    .files();
            String readme = new String(files.get("README.md"), UTF_8);

            assertTrue(readme.contains("Requirements: Java " + javaVersion + "."), profile.id());
            assertTrue(readme.contains("./gradlew bootRun"), profile.id());
            assertTrue(readme.contains("KMA_SERVICE_KEY"), profile.id());
            assertTrue(readme.contains("http://localhost:8080/mcp"), profile.id());
            assertTrue(readme.contains("mcpServers"), profile.id());
            assertTrue(readme.contains("weather_get_forecast"), profile.id());
            assertTrue(readme.contains("./gradlew test"), profile.id());
            assertTrue(readme.contains("docker build"), profile.id());
            assertTrue(readme.contains("- Compatibility profile: `" + profile.id() + "`"), profile.id());
            assertTrue(readme.contains("- Template: `spring-ai-2-v3`"), profile.id());
            assertTrue(readme.contains("- Runtime version: `0.3.0`"), profile.id());
            assertTrue(readme.contains("- Gradle 9.6.1"), profile.id());
            assertTrue(readme.contains("- Container image: `" + profile.containerImage() + "`"), profile.id());
            assertTrue(readme.contains("- Spring Boot 4.1.0"), profile.id());
            assertTrue(readme.contains("- Spring AI 2.0.0"), profile.id());
            assertTrue(readme.contains("## Response handling"), profile.id());
            assertTrue(readme.contains(
                    "Operations without response normalization return the provider's successful JSON body unchanged."));
            assertTrue(readme.contains(
                    "Configured operations return `data`, optional `page.totalCount`, and optional `provider` metadata."));
            assertTrue(readme.contains("Expected provider, HTTP, timeout, availability, protocol, and local-capacity "
                    + "failures return one MCP Tool error JSON payload with a local trace ID."));
            assertTrue(readme.contains(
                    "Provider responses remain bounded to 1 MiB. Retry and pagination are not executed automatically."));
            assertFalse(readme.contains("Known P0 limits"), profile.id());
            assertFalse(readme.contains("Spring AI 1.x"), profile.id());
            assertFalse(readme.contains("OpenTelemetry"), profile.id());
            assertTrue(readme.contains("-e PROVIDER_BASE_URL=https://api.example.test -e KMA_SERVICE_KEY"));
            assertTrue(new String(files.get("Dockerfile"), UTF_8).contains("USER 10001:10001"), profile.id());
            assertTrue(files.containsKey(".dockerignore"), profile.id());
        }
    }

    @Test
    void rendersConfiguredResponsePoliciesInDeterministicOperationOrder() {
        var zulu = normalized(tool("getZulu", "zulu", "zulu-key", "ZULU_KEY"),
                new ResponseNormalizationPolicy("/zulu/data", "/zulu/code", List.of("OK"),
                        "/zulu/message", "/zulu/count"));
        var alpha = normalized(tool("getAlpha", "alpha", "alpha-key", "ALPHA_KEY"),
                new ResponseNormalizationPolicy("/response/body/items/item", "/response/header/resultCode",
                        List.of("00", new BigDecimal("1.50"), true), "/response/header/resultMsg",
                        "/response/body/totalCount"));

        String readme = renderer.readme(contextWithSecrets(List.of(zulu, alpha)));

        assertTrue(readme.indexOf("`getAlpha`") < readme.indexOf("`getZulu`"));
        assertTrue(readme.contains("`dataPath`: `/response/body/items/item`"));
        assertTrue(readme.contains("`successCodePath`: `/response/header/resultCode`"));
        assertTrue(readme.contains("`successValues`: `[\"00\",1.50,true]`"));
        assertTrue(readme.contains("`errorMessagePath`: `/response/header/resultMsg`"));
        assertTrue(readme.contains("`totalCountPath`: `/response/body/totalCount`"));
    }

    @Test
    void rendersBackticksInResponsePolicyValuesWithCommonMarkSafeCodeSpans() {
        var hostile = normalized(tool("getBackticks", "backticks", "backtick-key", "BACKTICK_KEY"),
                new ResponseNormalizationPolicy(
                        "/path/one`two``",
                        "/header/`code",
                        List.of("one`tick", "two``ticks"),
                        "/header/message`",
                        "/count``"));

        String readme = renderer.readme(contextWithSecrets(List.of(hostile)));

        assertTrue(readme.contains("`dataPath`: ``` /path/one`two`` ```"));
        assertTrue(readme.contains("`successCodePath`: ``/header/`code``"));
        assertTrue(readme.contains("`successValues`: ```[\"one`tick\",\"two``ticks\"]```"));
        assertTrue(readme.contains("`errorMessagePath`: `` /header/message` ``"));
        assertTrue(readme.contains("`totalCountPath`: ``` /count`` ```"));
        assertFalse(readme.contains("\\`"));
    }

    @Test
    void rendersRequiredDockerSecretsDeterministicallyAndEscapesToolDescriptionsAsPlainMarkdown() {
        var first = tool("getZulu", "zulu", "zulu-key", "ZULU_KEY");
        var second = tool("getAlpha", "alpha", "alpha-key", "ALPHA_KEY");
        var hostile = new McpToolDefinition(
                "getMarkdown", "markdown", "<tag> & `code` \\ [link](url) ~~removed~~", List.of(),
                new HttpExecutionDefinition(HttpMethod.GET, URI.create("https://api.example.test"), "/markdown", List.of()),
                List.of(), McpToolDefinition.OutputKind.GENERIC_JSON);

        String readme = renderer.readme(contextWithSecrets(List.of(first, second, hostile)));

        assertTrue(readme.contains("-e ALPHA_KEY -e ZULU_KEY"));
        assertTrue(readme.contains("&lt;tag&gt; &amp; \\`code\\` \\\\ \\[link\\]\\(url\\) \\~\\~removed\\~\\~"));
        assertTrue(renderer.readme(contextWithSecrets(List.of(hostile)))
                .contains("docker run --rm -p 8080:8080 -e PROVIDER_BASE_URL=https://api.example.test weather-mcp-server"));
    }

    @Test
    void emitsByteIdenticalFilesForEquivalentGenerationContexts() {
        var generator = new SpringAi2ProjectGenerator();

        assertFilesEqual(
                generator.generate(contextWithSecrets(List.of(tool("service-key", "KMA_SERVICE_KEY")))).files(),
                generator.generate(contextWithSecrets(List.of(tool("service-key", "KMA_SERVICE_KEY")))).files());
    }

    @Test
    void emitsByteIdenticalFilesWhenToolInputOrderChanges() {
        var first = tool("getForecast", "weather_get_forecast", "service-key", "KMA_SERVICE_KEY");
        var second = tool("getAlerts", "weather_get_alerts", "client-token", "CLIENT_TOKEN");
        var generator = new SpringAi2ProjectGenerator();

        assertFilesEqual(
                generator.generate(contextWithSecrets(List.of(first, second))).files(),
                generator.generate(contextWithSecrets(List.of(second, first))).files());
    }

    @Test
    void embedsByteIdenticalRootGradleWrapperAssets() throws IOException {
        var renderer = new ProjectFileRenderer(CompatibilityProfile.p0());
        Path root = repositoryRoot();

        for (String wrapperFile : List.of("gradlew", "gradlew.bat", "gradle-wrapper.jar", "gradle-wrapper.properties")) {
            Path rootAsset = wrapperFile.startsWith("gradle-wrapper")
                    ? root.resolve("gradle/wrapper").resolve(wrapperFile)
                    : root.resolve(wrapperFile);
            assertArrayEquals(Files.readAllBytes(rootAsset), renderer.wrapperAsset(wrapperFile));
        }
    }

    private GenerationRequest.ProjectCoordinates projectCoordinates() {
        return new GenerationRequest.ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather");
    }

    private GenerationContext contextWithSecret(String propertyName) {
        return contextWithSecrets(List.of(tool(propertyName, "KMA_SERVICE_KEY")));
    }

    private GenerationContext contextWithSecrets(List<McpToolDefinition> tools) {
        return contextWithSecrets(CompatibilityProfile.p0(), tools);
    }

    private GenerationContext contextWithSecrets(
            CompatibilityProfile profile,
            List<McpToolDefinition> tools) {
        var request = new GenerationRequest(projectCoordinates(), "weather", "weather",
                profile.id(), GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", Map.of("nx", 60, "ny", 127))),
                List.of());
        return new GenerationContext(null, tools, request, profile, new byte[0]);
    }

    private void assertProfileMetadata(CompatibilityProfile profile, int javaVersion, String containerImage) {
        var files = new SpringAi2ProjectGenerator()
                .generate(contextWithSecrets(profile, List.of(tool("service-key", "KMA_SERVICE_KEY"))))
                .files();
        String build = new String(files.get("build.gradle.kts"), UTF_8);
        String readme = new String(files.get("README.md"), UTF_8);

        assertTrue(build.contains("id(\"org.springframework.boot\") version \"4.1.0\""), profile.id());
        assertTrue(build.contains("spring-ai-bom:2.0.0"), profile.id());
        assertTrue(build.contains("JavaLanguageVersion.of(" + javaVersion + ")"), profile.id());
        assertTrue(readme.contains("- Compatibility profile: `" + profile.id() + "`"), profile.id());
        assertTrue(readme.contains("- Template: `spring-ai-2-v3`"), profile.id());
        assertTrue(readme.contains("- Runtime version: `0.3.0`"), profile.id());
        assertTrue(readme.contains("- Gradle 9.6.1"), profile.id());
        assertTrue(readme.contains("- Container image: `" + containerImage + "`"), profile.id());
        assertTrue(new String(files.get("gradle/wrapper/gradle-wrapper.properties"), UTF_8)
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip"));
    }

    private void assertDockerContext(CompatibilityProfile profile, String containerImage) {
        var files = new SpringAi2ProjectGenerator()
                .generate(contextWithSecrets(profile, List.of(tool("service-key", "KMA_SERVICE_KEY"))))
                .files();
        String dockerfile = new String(files.get("Dockerfile"), UTF_8);

        assertEquals("""
                FROM %s
                WORKDIR /app
                COPY build/libs/weather-mcp-server.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]
                """.formatted(containerImage), dockerfile);
        assertTrue(dockerfile.indexOf("USER 10001:10001") < dockerfile.indexOf("ENTRYPOINT"));
        assertEquals("""
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/weather-mcp-server.jar
                """, new String(files.get(".dockerignore"), UTF_8));
        assertFalse(dockerfile.contains(":latest"));
    }

    private CompatibilityProfile profile(int javaVersion) {
        return io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    private CompatibilityProfile copy(
            CompatibilityProfile source,
            String generatorModule,
            CompatibilityProfile.TargetPlatform target) {
        return new CompatibilityProfile(
                source.id(), target, generatorModule, source.templateVersion(), source.runtimeVersion(),
                source.gradleVersion(), source.containerImage());
    }

    private void assertGenerationRejectedAsNoncanonical(CompatibilityProfile profile) {
        GeneratorException exception = assertThrows(
                GeneratorException.class,
                () -> new SpringAi2ProjectGenerator().generate(contextWithSecrets(
                        profile, List.of(tool("service-key", "KMA_SERVICE_KEY")))));

        assertEquals(SOURCE_GENERATION_FAILED, exception.code());
        assertEquals("The compatibility profile is not supported by the Spring AI 2 renderer",
                exception.safeMessage());
    }

    private McpToolDefinition tool(String propertyName, String environmentVariable) {
        return tool("getForecast", "weather_get_forecast", propertyName, environmentVariable);
    }

    private McpToolDefinition tool(
            String operationId,
            String toolName,
            String propertyName,
            String environmentVariable) {
        return new McpToolDefinition(
                operationId, toolName, "Get forecast", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/forecast", List.of()),
                List.of(new SecretBinding(environmentVariable, propertyName, ParameterLocation.QUERY, "serviceKey", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    private McpToolDefinition normalized(
            McpToolDefinition tool,
            ResponseNormalizationPolicy normalization) {
        HttpExecutionDefinition execution = tool.execution();
        return new McpToolDefinition(
                tool.operationId(), tool.name(), tool.description(), tool.inputs(),
                new HttpExecutionDefinition(
                        execution.method(), execution.baseUrl(), execution.path(), execution.bindings(),
                        execution.objectRequestBody(), execution.requestBodyRequired(), normalization),
                tool.secretBindings(), tool.outputKind());
    }

    private void assertFilesEqual(java.util.Map<String, byte[]> first, java.util.Map<String, byte[]> second) {
        assertEquals(new TreeSet<>(first.keySet()), new TreeSet<>(second.keySet()));
        for (String path : new TreeSet<>(first.keySet())) {
            assertArrayEquals(first.get(path), second.get(path), path);
        }
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
}

package io.gen2spring.mcp.springai1;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationContext;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.HttpExecutionDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.SecretBinding;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectFileRendererTest {
    private static final String SAFE_PROFILE_FAILURE =
            "The compatibility profile is not supported by the Spring AI 1 renderer";
    private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
            + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
    private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
            + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

    @Test
    void rendersThePinnedBoot35ProjectContractForBothProfiles() {
        assertProjectContract(profile(17), 17, JAVA_17_IMAGE);
        assertProjectContract(profile(21), 21, JAVA_21_IMAGE);
    }

    @Test
    void rendersDeterministicProviderSecretsAndResponsePolicyMetadata() {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile(17));
        McpToolDefinition normalized = normalizedTool();
        GenerationContext context = context(profile(17), List.of(normalized));

        String yaml = renderer.applicationYaml(context);
        String readme = renderer.readme(context);

        assertTrue(yaml.contains("base-url: \"${PROVIDER_BASE_URL:https://api.example.test}\""));
        assertTrue(yaml.contains("response-max-bytes: 1048576"));
        assertTrue(yaml.contains("connect-timeout-millis: 2000"));
        assertTrue(yaml.contains("read-timeout-millis: 5000"));
        assertTrue(yaml.contains("total-timeout-millis: 10000"));
        assertTrue(yaml.contains("max-concurrent-requests: 16"));
        assertTrue(yaml.contains("max-queued-requests: 64"));
        assertTrue(yaml.contains("service-key: \"${KMA_SERVICE_KEY:}\""));
        assertTrue(yaml.contains("logging:\n  level:\n"
                + "    org.springframework.ai.tool.method.MethodToolCallback: ERROR"));
        assertTrue(readme.contains("`dataPath`: `/response/body/items/item`"));
        assertTrue(readme.contains("`successCodePath`: `/response/header/resultCode`"));
        assertTrue(readme.contains("`successValues`: `[\"00\",1.50,true]`"));
        assertTrue(readme.contains("`errorMessagePath`: `/response/header/resultMsg`"));
        assertTrue(readme.contains("`totalCountPath`: `/response/body/totalCount`"));
    }

    @Test
    void copiesTheSpringAi2WrapperAssetsByteForByte() throws Exception {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile(21));
        Path source = repositoryRoot().resolve("generator-spring-ai-2/src/main/resources/wrapper");

        for (String file : List.of("gradlew", "gradlew.bat", "gradle-wrapper.jar", "gradle-wrapper.properties")) {
            assertArrayEquals(Files.readAllBytes(source.resolve(file)), renderer.wrapperAsset(file), file);
        }
        assertTrue(new String(renderer.wrapperAsset("gradle-wrapper.properties"), UTF_8)
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip"));
    }

    @Test
    void rejectsEveryNoncanonicalProfileBeforeRenderingWithOneSafeFailure() {
        CompatibilityProfile canonical = profile(17);
        CompatibilityProfile.TargetPlatform target = canonical.target();
        List<CompatibilityProfile> unsupported = List.of(
                profileFrom("spring-ai-2.0-java17-mvc-streamable"),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), canonical.templateVersion(),
                        canonical.runtimeVersion(), canonical.gradleVersion(), JAVA_17_IMAGE + "\nRUN injected"),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), "spring-ai-1-v2",
                        canonical.runtimeVersion(), canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), canonical.templateVersion(),
                        "0.3.0", canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), new CompatibilityProfile.TargetPlatform(
                                17, "3.5.15", "1.1.8", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                        canonical.generatorModule(), canonical.templateVersion(), canonical.runtimeVersion(),
                        canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), new CompatibilityProfile.TargetPlatform(
                                17, "3.5.16", "1.1.7", "GRADLE_KOTLIN", "MVC", "SYNC", "STREAMABLE_HTTP"),
                        canonical.generatorModule(), canonical.templateVersion(), canonical.runtimeVersion(),
                        canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), new CompatibilityProfile.TargetPlatform(
                                17, "3.5.16", "1.1.8", "GRADLE_KOTLIN", "MVC", "SYNC", "STDIO"),
                        canonical.generatorModule(), canonical.templateVersion(), canonical.runtimeVersion(),
                        canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), target, "generator-spring-ai-2", canonical.templateVersion(),
                        canonical.runtimeVersion(), canonical.gradleVersion(), canonical.containerImage()));

        for (CompatibilityProfile candidate : unsupported) {
            assertUnsupported(() -> new ProjectFileRenderer(candidate));
        }
    }

    @Test
    void rejectsAContextForAnotherCanonicalProfile() {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile(17));

        GeneratorException failure = assertThrows(
                GeneratorException.class,
                () -> renderer.applicationYaml(context(profile(21), List.of(normalizedTool()))));

        assertEquals(SOURCE_GENERATION_FAILED, failure.code());
        assertEquals("spring-ai-1-render", failure.stage());
        assertEquals("A generation context for the renderer compatibility profile is required", failure.safeMessage());
    }

    private void assertProjectContract(CompatibilityProfile profile, int javaVersion, String image) {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile);
        String build = renderer.buildGradle(coordinates());
        String yaml = renderer.applicationYaml(context(profile, List.of(normalizedTool())));
        String readme = renderer.readme(context(profile, List.of(normalizedTool())));

        assertTrue(build.contains("id(\"org.springframework.boot\") version \"3.5.16\""), profile.id());
        assertTrue(build.contains("spring-boot-dependencies:3.5.16"), profile.id());
        assertTrue(build.contains("spring-ai-bom:1.1.8"), profile.id());
        assertTrue(build.contains("spring-ai-starter-mcp-server-webmvc"), profile.id());
        assertTrue(build.contains("spring-boot-starter-web\""), profile.id());
        assertTrue(build.contains("spring-boot-starter-validation"), profile.id());
        assertFalse(build.contains("spring-boot-restclient"), profile.id());
        assertTrue(build.contains("JavaLanguageVersion.of(" + javaVersion + ")"), profile.id());
        assertTrue(build.contains("archiveFileName.set(\"weather-mcp-server.jar\")"), profile.id());
        assertTrue(yaml.contains("type: SYNC"), profile.id());
        assertTrue(yaml.contains("protocol: STREAMABLE"), profile.id());
        assertTrue(yaml.contains("annotation-scanner:\n          enabled: false"), profile.id());
        assertTrue(yaml.contains("mcp-endpoint: /mcp"), profile.id());
        assertTrue(readme.contains("- Compatibility profile: `" + profile.id() + "`"), profile.id());
        assertTrue(readme.contains("- Template: `spring-ai-1-v1`"), profile.id());
        assertTrue(readme.contains("- Generator module: `generator-spring-ai-1`"), profile.id());
        assertTrue(readme.contains("- Runtime version: `0.2.0`"), profile.id());
        assertTrue(readme.contains("- Gradle 9.6.1"), profile.id());
        assertTrue(readme.contains("- Container image: `" + image + "`"), profile.id());
        assertEquals("rootProject.name = \"weather-mcp-server\"\n", renderer.settingsGradle(coordinates()));
        assertEquals("org.gradle.caching=true\norg.gradle.configuration-cache=true\n", renderer.gradleProperties());
        assertEquals("/.gradle/\n/build/\n", renderer.gitignore());
        assertEquals("""
                FROM %s
                WORKDIR /app
                COPY build/libs/weather-mcp-server.jar /app/app.jar
                USER 10001:10001
                ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]
                """.formatted(image), renderer.dockerfile(coordinates()));
        assertEquals("""
                **
                !Dockerfile
                !build/
                !build/libs/
                !build/libs/weather-mcp-server.jar
                """, renderer.dockerignore(coordinates()));
    }

    private void assertUnsupported(org.junit.jupiter.api.function.Executable action) {
        GeneratorException failure = assertThrows(GeneratorException.class, action);
        assertEquals(SOURCE_GENERATION_FAILED, failure.code());
        assertEquals("spring-ai-1-render", failure.stage());
        assertEquals(SAFE_PROFILE_FAILURE, failure.safeMessage());
    }

    private CompatibilityProfile profile(int javaVersion) {
        return profileFrom("spring-ai-1.1-java" + javaVersion + "-mvc-streamable");
    }

    private CompatibilityProfile profileFrom(String id) {
        return CompatibilityProfileRegistry.defaults().find(id).orElseThrow();
    }

    private CompatibilityProfile copy(
            CompatibilityProfile source,
            String id,
            CompatibilityProfile.TargetPlatform target,
            String module,
            String template,
            String runtime,
            String gradle,
            String image) {
        return new CompatibilityProfile(id, target, module, template, runtime, gradle, image);
    }

    private GenerationRequest.ProjectCoordinates coordinates() {
        return new GenerationRequest.ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather");
    }

    private GenerationContext context(CompatibilityProfile profile, List<McpToolDefinition> tools) {
        GenerationRequest request = new GenerationRequest(
                coordinates(), "kma", "weather", profile.id(), GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", Map.of("stationId", "STN01"))),
                List.of());
        return new GenerationContext(null, tools, request, profile, new byte[0]);
    }

    private McpToolDefinition normalizedTool() {
        ResponseNormalizationPolicy normalization = new ResponseNormalizationPolicy(
                "/response/body/items/item", "/response/header/resultCode",
                List.of("00", new BigDecimal("1.50"), true), "/response/header/resultMsg",
                "/response/body/totalCount");
        return new McpToolDefinition(
                "getForecast", "kma_weather_get_forecast", "Get forecast", List.of(),
                new HttpExecutionDefinition(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/forecast", List.of(),
                        false, false, normalization),
                List.of(new SecretBinding(
                        "KMA_SERVICE_KEY", "service-key", ParameterLocation.QUERY, "serviceKey", true)),
                McpToolDefinition.OutputKind.GENERIC_JSON);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate repository root");
        }
        return current;
    }
}

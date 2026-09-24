package io.gen2spring.mcp.adapter.emitter.springai1.render;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.gen2spring.mcp.application.generation.command.GenerationCommand;
import io.gen2spring.mcp.application.generation.model.GenerationContext;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.execution.PaginationPolicy;
import io.gen2spring.mcp.domain.execution.RetryPolicy;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.OutputKind;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import io.gen2spring.mcp.domain.tool.ToolOutput;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectFileRendererTest {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
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
    void rendersTheExactBoot35ObservabilityDependenciesWithoutUnmanagedVersions() {
        String build = new ProjectFileRenderer(profile(17)).buildGradle(coordinates());

        assertEquals(List.of(
                        "implementation(platform(\"org.springframework.boot:spring-boot-dependencies:3.5.16\"))",
                        "implementation(platform(\"org.springframework.ai:spring-ai-bom:1.1.8\"))",
                        "implementation(\"org.springframework.ai:spring-ai-starter-mcp-server-webmvc\")",
                        "implementation(\"org.springframework.boot:spring-boot-starter-web\")",
                        "implementation(\"org.springframework.boot:spring-boot-starter-validation\")",
                        "implementation(\"org.springframework.boot:spring-boot-starter-actuator\")",
                        "implementation(\"io.micrometer:micrometer-registry-prometheus\")",
                        "implementation(\"io.micrometer:micrometer-registry-otlp\")",
                        "implementation(\"io.micrometer:micrometer-tracing-bridge-otel\")",
                        "implementation(\"io.opentelemetry:opentelemetry-exporter-otlp\")"),
                implementationLines(build));
        assertFalse(build.contains("spring-boot-starter-opentelemetry"));
        assertFalse(build.contains("latest"));
        assertFalse(build.contains("SNAPSHOT"));
        assertFalse(build.contains("+"));
    }

    @Test
    void rendersTheExactBoot35SafeObservabilityDefaultsWithoutCommonTagsOrEndpoints() throws Exception {
        JsonNode root = YAML.readTree(new ProjectFileRenderer(profile(17))
                .applicationYaml(context(profile(17), List.of(normalizedTool()))));
        JsonNode expectedManagement = YAML.readTree("""
                management:
                  endpoints:
                    web:
                      exposure:
                        include: ${MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE:health}
                  endpoint:
                    health:
                      show-details: never
                  tracing:
                    propagation:
                      type: W3C
                    sampling:
                      probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}
                  otlp:
                    metrics:
                      export:
                        enabled: ${MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED:false}
                    tracing:
                      export:
                        enabled: ${MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED:false}
                """).path("management");

        assertEquals(expectedManagement, root.path("management"));
        assertTrue(root.at("/spring/ai/tools").isMissingNode());
        assertTrue(root.path("otel").isMissingNode());
        assertTrue(root.path("management").path("server").isMissingNode());
        assertTrue(root.path("management").path("metrics").path("tags").isMissingNode());
    }

    @Test
    void rendersDeterministicProviderSecretsAndResponsePolicyMetadata() {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile(17));
        ToolDefinition normalized = normalizedTool();
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
    void copiesTheSharedWrapperAssetsByteForByte() throws Exception {
        ProjectFileRenderer renderer = new ProjectFileRenderer(profile(21));
        Path source = repositoryRoot().resolve(
                "modules/adapters/emitters/support/src/main/resources/wrapper");

        for (String file : List.of("gradlew", "gradlew.bat", "gradle-wrapper.jar", "gradle-wrapper.properties")) {
            assertArrayEquals(Files.readAllBytes(source.resolve(file)), renderer.wrapperAsset(file), file);
        }
        assertTrue(new String(renderer.wrapperAsset("gradle-wrapper.properties"), UTF_8)
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.6.1-bin.zip"));
        assertTrue(new String(renderer.wrapperAsset("gradle-wrapper.properties"), UTF_8)
                .contains("distributionSha256Sum=9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14"));
    }

    @Test
    void rejectsEveryNoncanonicalProfileBeforeRenderingWithOneSafeFailure() {
        CompatibilityProfile canonical = profile(17);
        CompatibilityProfile.TargetPlatform target = canonical.target();
        List<CompatibilityProfile> unsupported = List.of(
                profileFrom("spring-ai-2.0-java17-mvc-streamable"),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), canonical.templateVersion(),
                        canonical.runtimeVersion(), canonical.gradleVersion(), JAVA_17_IMAGE + "\nRUN injected"),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), "spring-ai-1-v1",
                        canonical.runtimeVersion(), canonical.gradleVersion(), canonical.containerImage()),
                copy(canonical, canonical.id(), target, canonical.generatorModule(), canonical.templateVersion(),
                        "0.2.0", canonical.gradleVersion(), canonical.containerImage()),
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

    @Test
    void documentsFinalOutputRetryAndPaginationPoliciesWithoutInitialCursorValues() {
        String readme = new ProjectFileRenderer(profile(17))
                .readme(context(profile(17), List.of(policyTool())));

        assertTrue(readme.contains("`output.mode`: `TYPED`"));
        assertTrue(readme.contains("`retry.statusCodes`: `[429,503]`"));
        assertTrue(readme.contains("`retry.networkErrors`: `true`"));
        assertTrue(readme.contains("`retry.maxRetries`: `2`"));
        assertTrue(readme.contains("`retry.initialBackoffMillis`: `100`"));
        assertTrue(readme.contains("`retry.maxBackoffMillis`: `1000`"));
        assertTrue(readme.contains("`retry.respectRetryAfter`: `true`"));
        assertTrue(readme.contains("`pagination.requestParameter`: `cursor`"));
        assertTrue(readme.contains("`pagination.itemsPath`: `/items`"));
        assertTrue(readme.contains("`pagination.nextValuePath`: `/next`"));
        assertTrue(readme.contains("`pagination.maxPages`: `10`"));
        assertTrue(readme.contains("`pagination.maxItems`: `1000`"));
        assertTrue(readme.contains("Limit violations fail the Tool call without returning partial items."));
        assertFalse(readme.contains("initial-private-cursor"));
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
        assertTrue(readme.contains("- Template: `spring-ai-1-v2`"), profile.id());
        assertTrue(readme.contains("- Generator module: `generator-spring-ai-1`"), profile.id());
        assertTrue(readme.contains("- Runtime version: `0.3.0`"), profile.id());
        assertTrue(readme.contains("- Gradle 9.6.1"), profile.id());
        assertTrue(readme.contains("- Container image: `" + image + "`"), profile.id());
        assertTrue(readme.contains("Requirements: Java " + javaVersion), profile.id());
        assertTrue(readme.contains("Streamable HTTP MCP endpoint is `http://localhost:8080/mcp`"), profile.id());
        assertTrue(readme.contains("## Observability"), profile.id());
        assertTrue(readme.contains("`gen2spring.runtime.mcp.tool.call`"), profile.id());
        assertTrue(readme.contains("`gen2spring.runtime.provider.request`"), profile.id());
        assertTrue(readme.contains("`gen2spring.runtime.provider.response.bytes`"), profile.id());
        assertTrue(readme.contains("`gen2spring.runtime.provider.executor.active`"), profile.id());
        assertTrue(readme.contains("`gen2spring.runtime.provider.executor.queued`"), profile.id());
        assertTrue(readme.contains("`target.profile`, `outcome`, `error.category`, and `http.status.class`"),
                profile.id());
        assertTrue(readme.contains("MANAGEMENT_SERVER_ADDRESS=127.0.0.1"), profile.id());
        assertTrue(readme.contains("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true"), profile.id());
        assertTrue(readme.contains("MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=true"), profile.id());
        assertTrue(readme.contains("MANAGEMENT_OTLP_TRACING_ENDPOINT"), profile.id());
        assertTrue(readme.contains("active OpenTelemetry trace ID"), profile.id());
        assertTrue(readme.contains("- Java " + javaVersion), profile.id());
        assertTrue(readme.contains("- Spring Boot 3.5.16"), profile.id());
        assertTrue(readme.contains("- Spring AI 1.1.8"), profile.id());
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

    private List<String> implementationLines(String build) {
        return build.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("implementation("))
                .toList();
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

    private GenerationCommand.ProjectCoordinates coordinates() {
        return new GenerationCommand.ProjectCoordinates("com.example", "weather-mcp-server", "com.example.weather");
    }

    private GenerationContext context(CompatibilityProfile profile, List<ToolDefinition> tools) {
        GenerationCommand request = new GenerationCommand(
                coordinates(), "kma", "weather", profile.id(), GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new GenerationCommand.ValidationConfiguration(new GenerationCommand.ToolCallValidation(
                        "getForecast", Map.of("stationId", "STN01"))),
                List.of());
        return new GenerationContext(null, tools, request, profile, new byte[0]);
    }

    private ToolDefinition normalizedTool() {
        ResponseNormalizationPolicy normalization = new ResponseNormalizationPolicy(
                "/response/body/items/item", "/response/header/resultCode",
                List.of("00", new BigDecimal("1.50"), true), "/response/header/resultMsg",
                "/response/body/totalCount");
        return new ToolDefinition(
                "getForecast", "kma_weather_get_forecast", "Get forecast", List.of(),
                new HttpExecution(
                        HttpMethod.POST, URI.create("https://api.example.test"), "/forecast", List.of(),
                        false, false, normalization),
                List.of(new SecretBinding(
                        "KMA_SERVICE_KEY", "service-key", ParameterLocation.QUERY, "serviceKey", true)),
                OutputKind.GENERIC_JSON);
    }

    private ToolDefinition policyTool() {
        ApiSchema result = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null, null, null,
                Map.of(), List.of(), null, true, List.of());
        return new ToolDefinition(
                "getForecast", "weather_get_forecast", "Get forecast", List.of(),
                new HttpExecution(
                        HttpMethod.GET, URI.create("https://api.example.test"), "/forecast", List.of(),
                        false, false, null,
                        new RetryPolicy(List.of(503, 429), true, 2, 100, 1_000, true),
                        new PaginationPolicy(
                                "cursor", "initial-private-cursor", "/items", "/next", 10, 1_000)),
                List.of(), new ToolOutput(OutputKind.TYPED_DTO, result, result));
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

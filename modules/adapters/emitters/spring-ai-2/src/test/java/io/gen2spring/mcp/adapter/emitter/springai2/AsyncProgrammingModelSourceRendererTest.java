package io.gen2spring.mcp.adapter.emitter.springai2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.BuildToolchain;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AsyncProgrammingModelSourceRendererTest {
    @Test
    void rendersTypedAsyncToolsAndExplicitSpecifications() {
        CompatibilityProfile profile = asyncProfile("GRADLE_KOTLIN");
        var context = JavaSourceRendererTest.contextWithWeatherTool(profile);
        var request = new ProgrammingModelRenderRequest(
                context,
                "com.example.weather",
                "com/example/weather",
                "Weather",
                context.tools(),
                Map.of("kma_weather_get_forecast", "{\"type\":\"object\"}"),
                false,
                false,
                false);

        Map<String, String> sources = new AsyncProgrammingModelSourceRenderer(profile).render(request);
        String tools = sources.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java");
        String specifications = sources.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpToolSpecifications.java");
        String executor = sources.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java");

        assertTrue(tools.contains("Mono<JsonNode>"), tools);
        assertTrue(tools.contains("Map<String, Object> rawArguments"), tools);
        assertTrue(specifications.contains("McpServerFeatures.AsyncToolSpecification"), specifications);
        assertTrue(specifications.contains("Mono<McpSchema.CallToolResult>"), specifications);
        assertTrue(specifications.contains("RuntimeTelemetry.Outcome.CANCELLED"), specifications);
        assertFalse(specifications.contains("toAsyncToolSpecification"), specifications);
        assertFalse(specifications.contains("boundedElastic"), specifications);
        assertFalse(specifications.contains("ToolArgumentContext"), specifications);
        assertTrue(executor.contains("WebClient"), executor);
        assertFalse(executor.contains(".block("), executor);
    }

    @Test
    void rendersWebFluxAsyncDependenciesAndConfiguration() {
        CompatibilityProfile profile = asyncProfile("GRADLE_KOTLIN");
        var context = JavaSourceRendererTest.contextWithWeatherTool(profile);
        var renderer = new ProjectFileRenderer(profile);

        String yaml = renderer.applicationYaml(context);
        String build = renderer.buildGradle(context.request().project());

        assertTrue(yaml.contains("type: ASYNC"), yaml);
        assertTrue(yaml.contains("protocol: STREAMABLE"), yaml);
        assertTrue(build.contains("spring-ai-starter-mcp-server-webflux"), build);
        assertTrue(build.contains("spring-boot-starter-webflux"), build);
        assertFalse(build.contains("spring-ai-starter-mcp-server-webmvc"), build);
        assertFalse(build.contains("spring-boot-restclient"), build);
    }

    static CompatibilityProfile asyncProfile(String buildTool) {
        CompatibilityProfile sync = CompatibilityProfile.p0();
        boolean maven = "MAVEN".equals(buildTool);
        return new CompatibilityProfile(
                "spring-ai-2.0-java21-" + (maven ? "maven-" : "")
                        + "webflux-async-streamable",
                new CompatibilityProfile.TargetPlatform(
                        21,
                        "4.1.0",
                        "2.0.0",
                        buildTool,
                        "WEBFLUX",
                        "ASYNC",
                        "STREAMABLE_HTTP"),
                "generator-spring-ai-2",
                "spring-ai-2-v3",
                "0.3.0",
                maven ? new BuildToolchain("3.9.16", "3.3.4")
                        : new BuildToolchain("9.6.1", "9.6.1"),
                sync.containerImage());
    }
}

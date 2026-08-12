package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.ToolInput;
import io.gen2spring.mcp.domain.tool.ParameterBinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Task5ReviewRegressionTest {
    private final ProjectFileRenderer projectRenderer = new ProjectFileRenderer(CompatibilityProfile.p0());
    private final JavaSourceRenderer sourceRenderer = new JavaSourceRenderer();

    @Test
    void importsThePinnedSpringBootBomForVersionlessBootDependencies() {
        String build = projectRenderer.buildGradle(
                new io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates(
                        "com.example", "weather-mcp-server", "com.example.weather"));

        assertTrue(build.contains(
                "implementation(platform(\"org.springframework.boot:spring-boot-dependencies:4.1.0\"))"));
    }

    @Test
    void includesTheBootRestClientAutoConfigurationModule() {
        String build = projectRenderer.buildGradle(
                new io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates(
                        "com.example", "weather-mcp-server", "com.example.weather"));

        assertTrue(build.contains("implementation(\"org.springframework.boot:spring-boot-restclient\")"));
    }

    @Test
    void catchesTheJackson3RuntimeParseExceptionContract() {
        String runtime = utf8(sourceRenderer.render(JavaSourceRendererTest.contextWithWeatherTool()).get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));

        assertTrue(runtime.contains("import tools.jackson.core.JacksonException;"));
        assertTrue(runtime.contains("catch (JacksonException exception)"));
        assertFalse(runtime.contains("return jsonMapper.readTree(response.body());\n"
                + "        } catch (IOException exception)"));
    }

    @Test
    void keepsValidatedToolBeansProxyable() {
        String tool = utf8(sourceRenderer.render(JavaSourceRendererTest.contextWithWeatherTool()).get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));

        assertTrue(tool.contains("public class WeatherMcpTools"));
        assertFalse(tool.contains("public final class WeatherMcpTools"));
    }

    @Test
    void emitsBoundedConnectReadAndTotalTimeoutsWithASlowBodyRegression() {
        var files = sourceRenderer.render(JavaSourceRendererTest.contextWithWeatherTool());
        String runtime = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));
        String contextTest = utf8(files.get(
                "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java"));

        assertTrue(runtime.contains("HttpClient.newBuilder()"));
        assertTrue(runtime.contains(".connectTimeout(Duration.ofMillis(connectTimeoutMillis))"));
        assertTrue(runtime.contains("requestFactory.setReadTimeout"));
        assertTrue(runtime.contains("request.get(totalTimeoutMillis, TimeUnit.MILLISECONDS)"));
        assertTrue(runtime.contains("request.cancel(true)"));
        assertTrue(runtime.contains("new ThreadPoolExecutor("));
        assertTrue(runtime.contains("new ArrayBlockingQueue<>(maxQueuedRequests)"));
        assertTrue(contextTest.contains("slowUpstreamBodyTimesOutAndCancelsTheRequest"));
    }

    @Test
    void rejectsNestedModelNamesThatCollideWithTheOperationInputRecord() {
        ApiSchema city = JavaSourceRendererTest.schema(
                SchemaType.STRING, null, null, null, null, null, null, List.of());
        ApiSchema object = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null, null, null,
                null, null, Map.of("city", city), List.of("city"), null, true, List.of());
        var tool = JavaSourceRendererTest.weatherTool(
                List.of(new ToolInput("input", "input", "Input", true, object)),
                List.of(new ParameterBinding("input", ParameterLocation.BODY, "body")));

        assertThrows(GeneratorException.class,
                () -> sourceRenderer.render(JavaSourceRendererTest.context(List.of(tool))));
    }

    @Test
    void rejectsFlatInputNamesThatSpringAiCannotPreserveInToolSchema() {
        ApiSchema string = JavaSourceRendererTest.schema(
                SchemaType.STRING, null, null, null, null, null, null, List.of());
        var tool = JavaSourceRendererTest.weatherTool(
                List.of(new ToolInput("postal-code", "postal-code", "Postal code", true, string)),
                List.of(new ParameterBinding("postal-code", ParameterLocation.QUERY, "postal-code")));

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> sourceRenderer.render(JavaSourceRendererTest.context(List.of(tool))));

        assertTrue(exception.safeMessage().contains("Spring AI Tool schema"));
    }

    @Test
    void keepsGeneratedRuntimeAndSecuritySourcesEqualAcrossJavaProfiles() {
        CompatibilityProfile java17 = profile(17);
        CompatibilityProfile java21 = profile(21);
        var java17Files = new JavaSourceRenderer(java17)
                .render(JavaSourceRendererTest.contextWithWeatherTool(java17));
        var java21Files = new JavaSourceRenderer(java21)
                .render(JavaSourceRendererTest.contextWithWeatherTool(java21));

        assertTrue(java17Files.keySet().equals(java21Files.keySet()));
        for (String path : java17Files.keySet()) {
            if (path.endsWith("/GeneratedJavaRuntimeTest.java")) {
                continue;
            }
            if (path.endsWith("/RuntimeTelemetry.java")) {
                String java17Source = utf8(java17Files.get(path));
                String java21Source = utf8(java21Files.get(path));
                assertTrue(java17Source.contains("spring-ai-2.0-java17-mvc-streamable"));
                assertTrue(java21Source.contains("spring-ai-2.0-java21-mvc-streamable"));
                assertEquals(
                        normalizeTelemetryProfile(java17Source),
                        normalizeTelemetryProfile(java21Source),
                        path);
            } else {
                assertArrayEquals(java17Files.get(path), java21Files.get(path), path);
            }
        }
    }

    private String normalizeTelemetryProfile(String source) {
        return source
                .replace("spring-ai-2.0-java17-mvc-streamable", "<spring-ai-2-java-profile>")
                .replace("spring-ai-2.0-java21-mvc-streamable", "<spring-ai-2-java-profile>");
    }

    private CompatibilityProfile profile(int javaVersion) {
        return io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
    }

    private String utf8(byte[] value) {
        return new String(value, UTF_8);
    }
}

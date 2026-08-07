package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterBinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Task5ReviewRegressionTest {
    private final ProjectFileRenderer projectRenderer = new ProjectFileRenderer(CompatibilityProfile.p0());
    private final JavaSourceRenderer sourceRenderer = new JavaSourceRenderer();

    @Test
    void importsThePinnedSpringBootBomForVersionlessBootDependencies() {
        String build = projectRenderer.buildGradle(
                new io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates(
                        "com.example", "weather-mcp-server", "com.example.weather"));

        assertTrue(build.contains(
                "implementation(platform(\"org.springframework.boot:spring-boot-dependencies:4.1.0\"))"));
    }

    @Test
    void includesTheBootRestClientAutoConfigurationModule() {
        String build = projectRenderer.buildGradle(
                new io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates(
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
        assertTrue(runtime.contains("UPSTREAM_REQUEST_SATURATED"));
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
                List.of(new McpInputDefinition("input", "input", "Input", true, object)),
                List.of(new ParameterBinding("input", ParameterLocation.BODY, "body")));

        assertThrows(GeneratorException.class,
                () -> sourceRenderer.render(JavaSourceRendererTest.context(List.of(tool))));
    }

    @Test
    void rejectsFlatInputNamesThatSpringAiCannotPreserveInToolSchema() {
        ApiSchema string = JavaSourceRendererTest.schema(
                SchemaType.STRING, null, null, null, null, null, null, List.of());
        var tool = JavaSourceRendererTest.weatherTool(
                List.of(new McpInputDefinition("postal-code", "postal-code", "Postal code", true, string)),
                List.of(new ParameterBinding("postal-code", ParameterLocation.QUERY, "postal-code")));

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> sourceRenderer.render(JavaSourceRendererTest.context(List.of(tool))));

        assertTrue(exception.safeMessage().contains("Spring AI Tool schema"));
    }

    private String utf8(byte[] value) {
        return new String(value, UTF_8);
    }
}

package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedSecretSafetyTest {
    @Test
    void doesNotExposeServiceKeyAsAToolParameterOrInputField() {
        for (CompatibilityProfile profile : profiles()) {
            var files = new JavaSourceRenderer(profile).render(JavaSourceRendererTest.contextWithWeatherTool(profile));
            String toolSource = utf8(files.get(
                    "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
            String inputSource = utf8(files.get(
                    "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));

            assertFalse(toolSource.contains("serviceKey"), profile.id());
            assertFalse(toolSource.contains("KMA_SERVICE_KEY"), profile.id());
            assertFalse(inputSource.contains("serviceKey"), profile.id());
            assertFalse(inputSource.contains("KMA_SERVICE_KEY"), profile.id());
        }
    }

    @Test
    void resolvesSecretsOnlyThroughEnvironmentBackedProviderProperties() {
        for (CompatibilityProfile profile : profiles()) {
            var files = new JavaSourceRenderer(profile).render(JavaSourceRendererTest.contextWithWeatherTool(profile));
            String metadata = utf8(files.get(
                    "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
            String runtime = utf8(files.get(
                    "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));

            assertFalse(metadata.contains("KMA_SERVICE_KEY"), profile.id());
            assertTrue(metadata.contains("provider.secrets.service-key"), profile.id());
            assertTrue(runtime.contains("environment.getProperty(binding.propertyName())"), profile.id());
            assertFalse(runtime.contains("System.out"), profile.id());
            assertFalse(runtime.contains("printStackTrace"), profile.id());
        }
    }

    @Test
    void emitsErrorTelemetryWithoutExceptionEventsOrSensitiveInputs() {
        for (CompatibilityProfile profile : profiles()) {
            var files = new JavaSourceRenderer(profile).render(JavaSourceRendererTest.contextWithWeatherTool(profile));
            String telemetry = utf8(files.get(
                    "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java"));

            assertTrue(telemetry.contains(".setStatus(StatusCode.ERROR, category)"), profile.id());
            assertFalse(telemetry.contains("observation.error("), profile.id());
            assertFalse(telemetry.contains("recordException("), profile.id());
            assertFalse(telemetry.contains("exception.message"), profile.id());
            assertFalse(telemetry.contains("exception.stacktrace"), profile.id());
            assertFalse(telemetry.contains("serviceKey"), profile.id());
            assertFalse(telemetry.contains("provider.base-url"), profile.id());
        }
    }

    private List<CompatibilityProfile> profiles() {
        var profiles = io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry.defaults();
        return List.of(
                profiles.find("spring-ai-2.0-java17-mvc-streamable").orElseThrow(),
                profiles.find("spring-ai-2.0-java21-mvc-streamable").orElseThrow());
    }

    private String utf8(byte[] value) {
        return new String(value, UTF_8);
    }
}

package io.gen2spring.mcp.springai2;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeneratedSecretSafetyTest {
    @Test
    void doesNotExposeServiceKeyAsAToolParameterOrInputField() {
        var files = new JavaSourceRenderer().render(JavaSourceRendererTest.contextWithWeatherTool());
        String toolSource = utf8(files.get(
                "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java"));
        String inputSource = utf8(files.get(
                "src/main/java/com/example/weather/generated/model/GetForecastInput.java"));

        assertFalse(toolSource.contains("serviceKey"));
        assertFalse(toolSource.contains("KMA_SERVICE_KEY"));
        assertFalse(inputSource.contains("serviceKey"));
        assertFalse(inputSource.contains("KMA_SERVICE_KEY"));
    }

    @Test
    void resolvesSecretsOnlyThroughEnvironmentBackedProviderProperties() {
        var files = new JavaSourceRenderer().render(JavaSourceRendererTest.contextWithWeatherTool());
        String metadata = utf8(files.get(
                "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java"));
        String runtime = utf8(files.get(
                "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java"));

        assertFalse(metadata.contains("KMA_SERVICE_KEY"));
        assertTrue(metadata.contains("provider.secrets.service-key"));
        assertTrue(runtime.contains("environment.getProperty(binding.propertyName())"));
        assertFalse(runtime.contains("System.out"));
        assertFalse(runtime.contains("printStackTrace"));
    }

    private String utf8(byte[] value) {
        return new String(value, UTF_8);
    }
}

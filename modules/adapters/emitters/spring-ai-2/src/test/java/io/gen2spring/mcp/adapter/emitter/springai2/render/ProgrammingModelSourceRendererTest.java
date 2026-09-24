package io.gen2spring.mcp.adapter.emitter.springai2.render;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gen2spring.mcp.adapter.emitter.springai2.fixture.RendererFixtures;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProgrammingModelSourceRendererTest {
    private static final List<String> EXPECTED_SOURCE_PATHS = List.of(
            "src/main/java/com/example/weather/application/WeatherMcpApplication.java",
            "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java",
            "src/main/java/com/example/weather/generated/model/GetForecastInput.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpToolCallbacks.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java",
            "src/main/java/com/example/weather/runtime/NormalizedSuccess.java",
            "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java",
            "src/main/java/com/example/weather/runtime/OperationDefinition.java",
            "src/main/java/com/example/weather/runtime/OperationOutcome.java",
            "src/main/java/com/example/weather/runtime/ParameterBinding.java",
            "src/main/java/com/example/weather/runtime/ParameterLocation.java",
            "src/main/java/com/example/weather/runtime/ProviderError.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorCategory.java",
            "src/main/java/com/example/weather/runtime/ProviderErrorException.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizationPolicy.java",
            "src/main/java/com/example/weather/runtime/ResponseNormalizer.java",
            "src/main/java/com/example/weather/runtime/RuntimeTelemetry.java",
            "src/main/java/com/example/weather/runtime/SchemaValueValidator.java",
            "src/main/java/com/example/weather/runtime/SecretBinding.java",
            "src/main/java/com/example/weather/runtime/ToolArgumentContext.java",
            "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java",
            "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java");

    @Test
    void preservesTheExactSyncSourceContractForJava17AndJava21() {
        assertContract(17, "cd99e088a59a80372b030b714838998936b70c7a5972d5c0566afd187e22d51a");
        assertContract(21, "c32c90ac932f6f3586a4cf4a81776b86ac0fa8323a70228720bfffbbd16e0a6b");
    }

    private void assertContract(int javaVersion, String expectedDigest) {
        var profile = CompatibilityProfileRegistry.defaults()
                .find("spring-ai-2.0-java" + javaVersion + "-mvc-streamable")
                .orElseThrow();
        Map<String, byte[]> sources = new JavaSourceRenderer(profile)
                .render(RendererFixtures.contextWithWeatherTool(profile));

        assertEquals(EXPECTED_SOURCE_PATHS, List.copyOf(sources.keySet()));
        assertEquals(expectedDigest, checksum(sources));
    }

    private String checksum(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue());
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

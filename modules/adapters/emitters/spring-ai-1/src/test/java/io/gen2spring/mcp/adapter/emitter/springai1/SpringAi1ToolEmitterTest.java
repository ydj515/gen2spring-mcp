package io.gen2spring.mcp.adapter.emitter.springai1;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SpringAi1ToolEmitterTest {
    private static final List<String> EXPECTED_SCAFFOLD_PATHS = List.of(
            ".dockerignore",
            ".gitignore",
            "Dockerfile",
            "README.md",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "settings.gradle.kts",
            "src/main/resources/application.yml");
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
            "src/main/java/com/example/weather/runtime/SecretBinding.java",
            "src/test/java/com/example/weather/application/GeneratedJavaRuntimeTest.java",
            "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java");
    private static final String EXPECTED_SOURCE_DIGEST =
            "bc79685cc468fbba0e9be596f5b4b7eb1d19e9ed35cf159eeddd1a3b29f7f09d";
    private static final String EXPECTED_PROJECT_DIGEST =
            "e6311f351f029cb08976f68a4834255b6f32d4b074a11f20200f49233b517730";

    @Test
    void emitsTheCharacterizedSpringAi1ToolSources() {
        Map<String, byte[]> files = new SpringAi1ToolEmitter()
                .emit(JavaSourceRendererTest.contextWithWeatherTool())
                .files();

        assertEquals(EXPECTED_SOURCE_PATHS, new ArrayList<>(files.keySet()));
        assertEquals(EXPECTED_SOURCE_DIGEST, checksum(files));

        Map<String, byte[]> project = new SpringAi1ProjectGenerator()
                .generate(JavaSourceRendererTest.contextWithWeatherTool())
                .files();
        assertEquals(expectedProjectPaths(), project.keySet().stream().sorted().toList());
        assertEquals(EXPECTED_PROJECT_DIGEST, checksum(project));
    }

    private List<String> expectedProjectPaths() {
        return Stream.concat(EXPECTED_SCAFFOLD_PATHS.stream(), EXPECTED_SOURCE_PATHS.stream()).sorted().toList();
    }

    private String checksum(Map<String, byte[]> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String path = entry.getKey();
                byte[] bytes = entry.getValue();
                digest.update(path.getBytes(UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
                digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

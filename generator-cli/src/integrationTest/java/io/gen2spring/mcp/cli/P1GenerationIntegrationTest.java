package io.gen2spring.mcp.cli;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P1GenerationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_NAME = "kma_weather_get_forecast";
    private static final String TOOL_DESCRIPTION = "Get the public weather forecast for a grid location.";
    private static final String REPRESENTATIVE_STATION_ID = "STN01";
    private static final Set<String> REQUIRED_OUTPUTS = Set.of(
            ".gitignore",
            "Dockerfile",
            "GENERATION_MANIFEST.json",
            "README.md",
            "VALIDATION_REPORT.json",
            "build.gradle.kts",
            "gradle.properties",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "gradlew",
            "gradlew.bat",
            "openapi/source.yaml",
            "settings.gradle.kts",
            "src/main/java/com/example/weather/application/WeatherMcpApplication.java",
            "src/main/java/com/example/weather/generated/metadata/WeatherOperations.java",
            "src/main/java/com/example/weather/generated/model/GetForecastInput.java",
            "src/main/java/com/example/weather/generated/model/GetForecastLocation.java",
            "src/main/java/com/example/weather/generated/model/GetForecastModeValue.java",
            "src/main/java/com/example/weather/generated/tool/WeatherMcpTools.java",
            "src/main/java/com/example/weather/runtime/OpenApiOperationExecutor.java",
            "src/main/resources/application.yml",
            "src/test/java/com/example/weather/application/WeatherMcpApplicationTest.java");

    @TempDir
    Path tempDir;

    @Test
    void installedCliGeneratesCallsAndPackagesTheSelectedToolDeterministically() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        Path configuration = resource("config/weather-generation.yaml");

        GenerationResult first = generate(specification, configuration, tempDir.resolve("weather-mcp-server"));
        assertReleaseContract(first, specification);

        GenerationResult second = generate(
                specification, configuration, tempDir.resolve("weather-mcp-server-second"));
        assertReleaseContract(second, specification);
        assertEquals(first.sourceChecksum(), second.sourceChecksum());
        assertEquals(first.manifest(), second.manifest());
        assertCanonicalArchiveEntriesEqual(first.archiveEntries(), second.archiveEntries());
        assertSensitiveValuesAbsent(first, REPRESENTATIVE_STATION_ID,
                "mcp-validation-secret-1", "mcp-validation-secret-2");
        assertSensitiveValuesAbsent(second, REPRESENTATIVE_STATION_ID,
                "mcp-validation-secret-1", "mcp-validation-secret-2");
    }

    @Test
    void installedCliRejectsInvalidRepresentativeArgumentWithoutPublishingOrLeakingIt() throws Exception {
        Path specification = resource("openapi/weather-api.yaml");
        String configuredValue = "configured-invalid-representative-value";
        Path configuration = Files.writeString(tempDir.resolve("invalid-weather-generation.yaml"),
                Files.readString(resource("config/weather-generation.yaml"), UTF_8)
                        .replace("days: 3", "days: " + configuredValue), UTF_8);
        Path output = tempDir.resolve("invalid-weather-mcp-server");

        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        assertEquals(3, result.exitCode(), result.stderr() + result.stdout());
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling(output.getFileName() + ".zip")));
        assertFalse(result.stdout().contains(configuredValue));
        assertFalse(result.stderr().contains(configuredValue));
    }

    private GenerationResult generate(Path specification, Path configuration, Path output) throws Exception {
        InstalledCliResult result = runInstalledCli(specification, configuration, output);

        String validationReport = Files.exists(output.resolve("VALIDATION_REPORT.json"))
                ? Files.readString(output.resolve("VALIDATION_REPORT.json"), UTF_8)
                : "";
        assertEquals(0, result.exitCode(), result.stderr() + result.stdout() + validationReport);
        assertEquals("", result.stderr());
        JsonNode response = JSON.readTree(result.stdout());
        assertNotNull(response);
        assertEquals("VALIDATED", response.path("status").asText());
        assertEquals(output.toAbsolutePath().normalize().toString(), response.path("project").asText());
        assertEquals(output.resolve("VALIDATION_REPORT.json").toAbsolutePath().normalize().toString(),
                response.path("report").asText());
        Path archive = output.resolveSibling(output.getFileName() + ".zip");
        assertEquals(archive.toAbsolutePath().normalize().toString(), response.path("archive").asText());
        return new GenerationResult(
                output,
                archive,
                response.path("sourceChecksum").asText(),
                readJson(output.resolve("GENERATION_MANIFEST.json")),
                readJson(output.resolve("VALIDATION_REPORT.json")),
                readArchive(archive),
                result.stdout(),
                result.stderr());
    }

    private InstalledCliResult runInstalledCli(Path specification, Path configuration, Path output) throws Exception {
        Path executable = Path.of(System.getProperty("openapiMcp.executable"));
        assertTrue(Files.isExecutable(executable));
        Process process = new ProcessBuilder(
                executable.toString(),
                "generate",
                "--spec", specification.toString(),
                "--config", configuration.toString(),
                "--output", output.toString())
                .start();
        boolean finished = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("installed CLI timed out");
        }
        return new InstalledCliResult(
                process.exitValue(),
                new String(process.getInputStream().readAllBytes(), UTF_8),
                new String(process.getErrorStream().readAllBytes(), UTF_8));
    }

    private void assertReleaseContract(GenerationResult result, Path specification) throws Exception {
        assertTrue(Files.isDirectory(result.projectRoot()));
        assertTrue(Files.isRegularFile(result.archive()));
        assertTrue(Files.isExecutable(result.projectRoot().resolve("gradlew")));
        if (Files.getFileStore(result.projectRoot()).supportsFileAttributeView("posix")) {
            assertTrue(Files.getPosixFilePermissions(result.projectRoot().resolve("gradlew"))
                    .contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTransientBuildOutputsAbsent(result.projectRoot());

        Set<String> outputPaths = regularFiles(result.projectRoot());
        assertTrue(outputPaths.containsAll(REQUIRED_OUTPUTS));
        assertEquals(outputPaths, result.archiveEntries().keySet());
        assertEquals(new ArrayList<>(new TreeSet<>(result.archiveEntries().keySet())),
                new ArrayList<>(result.archiveEntries().keySet()));
        assertEquals(0755, centralDirectoryMode(Files.readAllBytes(result.archive()), "gradlew"));
        assertArrayEquals(Files.readAllBytes(specification),
                Files.readAllBytes(result.projectRoot().resolve("openapi/source.yaml")));

        JsonNode manifest = result.manifest();
        assertEquals("0.1.0", manifest.path("generatorVersion").asText());
        assertEquals("spring-ai-2-v1", manifest.path("templateVersion").asText());
        assertEquals("0.1.0", manifest.path("runtimeVersion").asText());
        assertEquals("spring-ai-2.0-java21-mvc-streamable", manifest.path("targetProfileId").asText());
        assertEquals("4.1.0", manifest.path("springBootVersion").asText());
        assertEquals("2.0.0", manifest.path("springAiVersion").asText());
        assertEquals(21, manifest.path("javaVersion").asInt());
        assertEquals("9.6.1", manifest.path("gradleVersion").asText());
        assertEquals(sha256(Files.readAllBytes(specification)),
                manifest.path("originalSpecificationChecksum").asText());
        assertEquals(result.sourceChecksum(), manifest.path("sourceChecksum").asText());
        assertEquals(independentSourceChecksum(result.projectRoot()), result.sourceChecksum());
        assertEquals(List.of("getForecast"), manifest.path("operationMappings").findValuesAsText("operationId"));
        assertEquals(List.of(TOOL_NAME), manifest.path("operationMappings").findValuesAsText("toolName"));

        JsonNode report = result.report();
        assertEquals("VALIDATED", report.path("status").asText());
        assertEquals(List.of(
                "COMPILE",
                "APPLICATION_CONTEXT",
                "MCP_INITIALIZE",
                "MCP_TOOLS_LIST",
                "MCP_TOOL_CALL"), stageNames(report));
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                report.path("stages").findValuesAsText("status"));
        assertEquals("SUCCESS", stage(report, "MCP_TOOL_CALL").path("status").textValue());
        assertEquals(List.of(TOOL_NAME), report.path("tools").findValuesAsText("name"));
        assertEquals(List.of(TOOL_DESCRIPTION), report.path("tools").findValuesAsText("description"));
        assertTrue(report.path("tools").get(0).path("inputSchemaPresent").asBoolean());

        String applicationYaml = Files.readString(
                result.projectRoot().resolve("src/main/resources/application.yml"), UTF_8);
        assertTrue(applicationYaml.contains("${KMA_SERVICE_KEY:}"));
        assertTrue(applicationYaml.contains("${WEATHER_HEADER_KEY:}"));
        assertFalse(applicationYaml.contains("secretValue"));
        result.archiveEntries().forEach((path, bytes) -> {
            assertFalse(path.equals("build") || path.startsWith("build/"));
            assertFalse(path.equals(".gradle") || path.startsWith(".gradle/"));
            assertFalse(path.equals("process-logs") || path.startsWith("process-logs/"));
        });
    }

    private List<String> stageNames(JsonNode report) {
        return report.path("stages").findValuesAsText("stage");
    }

    private JsonNode stage(JsonNode report, String stageName) {
        for (JsonNode stage : report.path("stages")) {
            if (stageName.equals(stage.path("stage").textValue())) {
                return stage;
            }
        }
        throw new AssertionError("Missing validation stage: " + stageName);
    }

    private void assertSensitiveValuesAbsent(GenerationResult result, String... sensitiveValues) throws IOException {
        for (String sensitiveValue : sensitiveValues) {
            assertFalse(result.stdout().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.stderr().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.manifest().toString().contains(sensitiveValue), sensitiveValue);
            assertFalse(result.report().toString().contains(sensitiveValue), sensitiveValue);
            try (var paths = Files.walk(result.projectRoot())) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    assertFalse(new String(Files.readAllBytes(path), UTF_8).contains(sensitiveValue),
                            result.projectRoot().relativize(path) + ": " + sensitiveValue);
                }
            }
            result.archiveEntries().forEach((path, bytes) ->
                    assertFalse((path + new String(bytes, UTF_8)).contains(sensitiveValue),
                            path + ": " + sensitiveValue));
        }
    }

    private void assertCanonicalArchiveEntriesEqual(
            Map<String, byte[]> first,
            Map<String, byte[]> second) {
        assertEquals(first.keySet(), second.keySet());
        first.forEach((path, bytes) -> {
            if (!"VALIDATION_REPORT.json".equals(path)) {
                assertArrayEquals(bytes, second.get(path), path);
            }
        });
    }

    private void assertTransientBuildOutputsAbsent(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                assertFalse(relative.equals("build") || relative.startsWith("build/"), relative);
                assertFalse(relative.equals(".gradle") || relative.startsWith(".gradle/"), relative);
                assertFalse(relative.equals("process-logs") || relative.startsWith("process-logs/"), relative);
                assertFalse(relative.startsWith(".gradlew-validated-"), relative);
            }
        }
    }

    private Set<String> regularFiles(Path root) throws IOException {
        Set<String> files = new TreeSet<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> files.add(root.relativize(path).toString().replace('\\', '/')));
        }
        return files;
    }

    private Map<String, byte[]> readArchive(Path archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(archive)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertFalse(entry.isDirectory());
                assertFalse(entry.getName().startsWith("/") || entry.getName().contains("../"));
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
                assertEquals(null, entries.put(entry.getName(), zip.readAllBytes()), entry.getName());
            }
        }
        return entries;
    }

    private String independentSourceChecksum(Path root) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.equals("GENERATION_MANIFEST.json")
                        || relative.equals("VALIDATION_REPORT.json")
                        || relative.equals("process-logs")
                        || relative.startsWith("process-logs/")) {
                    continue;
                }
                byte[] name = relative.getBytes(UTF_8);
                byte[] content = normalizeLineEndings(Files.readAllBytes(path));
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(name.length).array());
                digest.update(name);
                digest.update(ByteBuffer.allocate(Long.BYTES).putLong(content.length).array());
                digest.update(content);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private byte[] normalizeLineEndings(byte[] input) {
        byte[] normalized = new byte[input.length];
        int write = 0;
        for (int read = 0; read < input.length; read++) {
            if (input[read] == '\r') {
                if (read + 1 < input.length && input[read + 1] == '\n') {
                    read++;
                }
                normalized[write++] = '\n';
            } else {
                normalized[write++] = input[read];
            }
        }
        return Arrays.copyOf(normalized, write);
    }

    private int centralDirectoryMode(byte[] archive, String expectedName) {
        int end = findSignatureFromEnd(archive, 0x06054b50);
        int count = unsignedShort(archive, end + 10);
        int offset = littleEndianInt(archive, end + 16);
        for (int index = 0; index < count; index++) {
            assertEquals(0x02014b50, littleEndianInt(archive, offset));
            int nameLength = unsignedShort(archive, offset + 28);
            int extraLength = unsignedShort(archive, offset + 30);
            int commentLength = unsignedShort(archive, offset + 32);
            String name = new String(archive, offset + 46, nameLength, UTF_8);
            if (name.equals(expectedName)) {
                return littleEndianInt(archive, offset + 38) >>> 16 & 0777;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new AssertionError("Missing central directory entry: " + expectedName);
    }

    private int findSignatureFromEnd(byte[] bytes, int signature) {
        for (int index = bytes.length - 4; index >= 0; index--) {
            if (littleEndianInt(bytes, index) == signature) {
                return index;
            }
        }
        throw new AssertionError("ZIP signature is missing");
    }

    private int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private int littleEndianInt(byte[] bytes, int offset) {
        return unsignedShort(bytes, offset) | unsignedShort(bytes, offset + 2) << 16;
    }

    private String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private Path resource(String name) throws URISyntaxException {
        var resource = getClass().getClassLoader().getResource(name);
        assertNotNull(resource, name);
        return Path.of(resource.toURI());
    }

    private JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(Files.readAllBytes(path));
    }

    private record GenerationResult(
            Path projectRoot,
            Path archive,
            String sourceChecksum,
            JsonNode manifest,
            JsonNode report,
            Map<String, byte[]> archiveEntries,
            String stdout,
            String stderr) {}

    private record InstalledCliResult(int exitCode, String stdout, String stderr) {}
}

package io.gen2spring.mcp.web;

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
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalOperationEditorIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKEN_META = Pattern.compile(
            "<meta name=\"generator-api-token\" content=\"([a-f0-9]{64})\">");
    private static final List<String> STAGES = List.of(
            "ANALYZE", "GENERATE", "COMPILE", "APPLICATION_CONTEXT",
            "MCP_INITIALIZE", "MCP_TOOLS_LIST", "MCP_TOOL_CALL", "PACKAGE");
    private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TempDir
    Path tempDir;

    @Test
    void installedEditorGeneratesValidatesDownloadsAndDeletesAProject() throws Exception {
        Path executable = Path.of(System.getProperty("gen2springWeb.executable"));
        assertTrue(Files.isExecutable(executable));
        byte[] specification = resource("/openapi/weather.yaml");
        byte[] configuration = resource("/config/weather.json");
        String java17Home = requiredEnvironment("GEN2SPRING_JAVA_17_HOME");
        String java21Home = requiredEnvironment("GEN2SPRING_JAVA_21_HOME");

        InstalledServer server = InstalledServer.start(
                executable, tempDir.toRealPath(), java17Home, java21Home);
        String apiToken;
        try (server) {
            URI base = server.awaitReady();
            HttpResponse<byte[]> root = request(base, "/", "GET", null, null, null);
            assertEquals(200, root.statusCode());
            Matcher tokenMatch = TOKEN_META.matcher(new String(root.body(), UTF_8));
            assertTrue(tokenMatch.find(), "installed editor token metadata is missing");
            String token = tokenMatch.group(1);
            apiToken = token;

            HttpResponse<byte[]> profiles = request(base, "/api/profiles", "GET", null, token, null);
            assertEquals(200, profiles.statusCode());
            assertEquals(List.of(
                            "spring-ai-1.1-java17-mvc-streamable",
                            "spring-ai-1.1-java21-mvc-streamable",
                            "spring-ai-2.0-java17-mvc-streamable",
                            "spring-ai-2.0-java21-mvc-streamable"),
                    strings(JSON.readTree(profiles.body()).path("profiles"), "id"));

            HttpResponse<byte[]> upload = request(
                    base, "/api/specifications", "POST", specification, token, "weather.yaml");
            assertEquals(201, upload.statusCode());
            JsonNode uploaded = JSON.readTree(upload.body());
            assertEquals(1, uploaded.path("operationCount").intValue());
            assertEquals("getForecast", uploaded.path("operations").get(0).path("operationId").textValue());
            String specificationId = uploaded.path("id").textValue();
            assertTrue(specificationId.matches("[a-f0-9]{64}"));

            HttpResponse<byte[]> preview = request(
                    base, "/api/specifications/" + specificationId + "/preview",
                    "POST", configuration, token, null);
            assertEquals(200, preview.statusCode());
            JsonNode previewJson = JSON.readTree(preview.body());
            assertEquals("spring-ai-2.0-java21-mvc-streamable",
                    previewJson.path("profile").path("id").textValue());
            assertEquals("spring-ai-2-v3", previewJson.path("profile").path("templateVersion").textValue());
            assertEquals("0.3.0", previewJson.path("profile").path("runtimeVersion").textValue());
            assertEquals(1, previewJson.path("tools").size());
            JsonNode tool = previewJson.path("tools").get(0);
            assertEquals("getForecast", tool.path("operationId").textValue());
            assertEquals("weather_get_forecast", tool.path("name").textValue());
            assertEquals("Get forecast", tool.path("description").textValue());
            assertEquals(JSON.readTree("""
                    {"type":"object","properties":{"city":{"type":"string",
                    "description":"Forecast city.","minLength":1,"maxLength":80}},"required":["city"]}
                    """), tool.path("inputSchema"));
            assertFalse(new String(preview.body(), UTF_8).contains("browser-private-value"));

            HttpResponse<byte[]> accepted = request(
                    base, "/api/specifications/" + specificationId + "/jobs",
                    "POST", configuration, token, null);
            assertEquals(202, accepted.statusCode());
            JsonNode acceptedJson = JSON.readTree(accepted.body());
            assertEquals("QUEUED", acceptedJson.path("state").textValue());
            String jobId = acceptedJson.path("id").textValue();
            assertTrue(jobId.matches("[a-f0-9]{64}"));

            JsonNode terminal = awaitTerminalJob(base, jobId, token);
            assertEquals("VALIDATED", terminal.path("state").textValue());
            assertEquals("VALIDATED", terminal.path("validationStatus").textValue());
            assertTrue(terminal.path("error").isNull());
            assertEquals(STAGES, strings(terminal.path("stages"), "stage"));
            assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS",
                            "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                    strings(terminal.path("stages"), "status"));
            assertEquals(List.of("archive", "manifest", "report"), strings(terminal.path("downloads")));

            HttpResponse<byte[]> manifestDownload = request(
                    base, "/api/jobs/" + jobId + "/manifest", "GET", null, token, null);
            HttpResponse<byte[]> reportDownload = request(
                    base, "/api/jobs/" + jobId + "/report", "GET", null, token, null);
            HttpResponse<byte[]> archiveDownload = request(
                    base, "/api/jobs/" + jobId + "/archive", "GET", null, token, null);
            assertDownload(manifestDownload, jobId + "-manifest.json", "application/json; charset=utf-8");
            assertDownload(reportDownload, jobId + "-report.json", "application/json; charset=utf-8");
            assertDownload(archiveDownload, jobId + ".zip", "application/zip");

            JsonNode manifest = JSON.readTree(manifestDownload.body());
            assertEquals("0.1.0", manifest.path("generatorVersion").textValue());
            assertEquals("spring-ai-2-v3", manifest.path("templateVersion").textValue());
            assertEquals("0.3.0", manifest.path("runtimeVersion").textValue());
            assertEquals("spring-ai-2.0-java21-mvc-streamable", manifest.path("targetProfileId").textValue());
            assertEquals(21, manifest.path("javaVersion").intValue());
            assertEquals("9.6.1", manifest.path("gradleVersion").textValue());
            assertEquals(uploaded.path("checksum").textValue(),
                    manifest.path("originalSpecificationChecksum").textValue());
            assertTrue(manifest.path("sourceChecksum").textValue().matches("[a-f0-9]{64}"));
            assertEquals("getForecast", manifest.path("operationMappings").get(0).path("operationId").textValue());
            assertEquals("weather_get_forecast", manifest.path("operationMappings").get(0).path("toolName").textValue());

            JsonNode report = JSON.readTree(reportDownload.body());
            assertEquals("VALIDATED", report.path("status").textValue());
            assertEquals(List.of("COMPILE", "APPLICATION_CONTEXT", "MCP_INITIALIZE",
                            "MCP_TOOLS_LIST", "MCP_TOOL_CALL"),
                    strings(report.path("stages"), "stage"));
            assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS", "SUCCESS"),
                    strings(report.path("stages"), "status"));
            assertEquals("weather_get_forecast", report.path("tools").get(0).path("name").textValue());
            assertTrue(report.path("tools").get(0).path("inputSchemaPresent").booleanValue());

            Map<String, byte[]> entries = archiveEntries(archiveDownload.body());
            assertArrayEquals(manifestDownload.body(), entries.get("GENERATION_MANIFEST.json"));
            assertArrayEquals(reportDownload.body(), entries.get("VALIDATION_REPORT.json"));
            assertNotNull(entries.get("README.md"));
            assertNotNull(entries.get("build.gradle.kts"));
            assertNotNull(entries.get("openapi/source.yaml"));
            assertTrue(entries.keySet().stream().noneMatch(name -> name.startsWith("build/")
                    || name.startsWith(".gradle/") || name.startsWith("process-logs/")));
            assertNoPrivateValue(entries, "browser-private-value");
            assertNoPrivateValue(entries, token);
            assertNoPrivateValue(entries, java17Home);
            assertNoPrivateValue(entries, java21Home);
            assertNoPrivateValue(entries, "/Users/");

            HttpResponse<byte[]> deleted = request(
                    base, "/api/jobs/" + jobId, "DELETE", null, token, null);
            assertEquals(204, deleted.statusCode());
            assertEquals(404, request(
                    base, "/api/jobs/" + jobId, "GET", null, token, null).statusCode());
        }

        String processOutput = server.output();
        assertFalse(processOutput.contains("browser-private-value"));
        assertFalse(processOutput.contains(apiToken));
        assertFalse(processOutput.contains(java17Home));
        assertFalse(processOutput.contains(java21Home));
        assertFalse(processOutput.contains("Authorization:"));
        assertFalse(processOutput.contains("Bearer "));
        assertTrue(server.observedProcessesStopped());
    }

    private JsonNode awaitTerminalJob(URI base, String jobId, String token) throws Exception {
        long deadline = System.nanoTime() + JOB_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<byte[]> response = request(
                    base, "/api/jobs/" + jobId, "GET", null, token, null);
            assertEquals(200, response.statusCode());
            JsonNode status = JSON.readTree(response.body());
            if (List.of("VALIDATED", "UNVERIFIED", "FAILED").contains(status.path("state").textValue())) {
                return status;
            }
            Thread.sleep(POLL_INTERVAL);
        }
        throw new AssertionError("installed editor generation timed out");
    }

    private HttpResponse<byte[]> request(
            URI base,
            String path,
            String method,
            byte[] body,
            String token,
            String specificationName) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("X-Gen2Spring-Token", token);
        }
        if (specificationName != null) {
            request.header("X-Specification-Name", specificationName);
            request.header("Content-Type", "application/octet-stream");
        } else if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if ("POST".equals(method)) {
            request.header("Origin", origin(base));
            request.POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else if ("DELETE".equals(method)) {
            request.header("Origin", origin(base));
            request.DELETE();
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String origin(URI base) {
        return base.getScheme() + "://" + base.getHost() + ":" + base.getPort();
    }

    private void assertDownload(HttpResponse<byte[]> response, String name, String contentType) {
        assertEquals(200, response.statusCode());
        assertEquals(contentType, response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("attachment; filename=\"" + name + "\"",
                response.headers().firstValue("Content-Disposition").orElseThrow());
        assertTrue(response.body().length > 0);
    }

    private Map<String, byte[]> archiveEntries(byte[] archive) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                assertFalse(entry.isDirectory());
                assertFalse(entry.getName().startsWith("/") || entry.getName().contains("../"));
                assertEquals(LocalDateTime.of(1980, 1, 1, 0, 0), entry.getTimeLocal());
                order.add(entry.getName());
                assertEquals(null, entries.put(entry.getName(), input.readAllBytes()));
            }
        }
        assertEquals(order.stream().sorted().toList(), order);
        return entries;
    }

    private void assertNoPrivateValue(Map<String, byte[]> entries, String value) {
        byte[] needle = value.getBytes(UTF_8);
        assertTrue(entries.values().stream().noneMatch(bytes -> contains(bytes, needle)),
                "archive contains a private value");
    }

    private boolean contains(byte[] source, byte[] target) {
        if (target.length == 0 || target.length > source.length) {
            return false;
        }
        outer:
        for (int index = 0; index <= source.length - target.length; index++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (source[index + offset] != target[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private List<String> strings(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.path(field).textValue()));
        return List.copyOf(values);
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.textValue()));
        return List.copyOf(values);
    }

    private byte[] resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) {
                throw new AssertionError("integration fixture is missing");
            }
            return input.readAllBytes();
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("required test JDK home is unavailable");
        }
        return value;
    }

    private static final class InstalledServer implements AutoCloseable {
        private final Process process;
        private final Path stdout;
        private final Path stderr;
        private final Map<Long, ProcessHandle> observed = new LinkedHashMap<>();
        private final AtomicBoolean observing = new AtomicBoolean(true);
        private final Thread observer;

        private InstalledServer(Process process, Path stdout, Path stderr) {
            this.process = process;
            this.stdout = stdout;
            this.stderr = stderr;
            observeTree();
            observer = Thread.ofPlatform().daemon().name("installed-editor-process-observer").start(() -> {
                while (observing.get()) {
                    observeTree();
                    try {
                        Thread.sleep(25);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        static InstalledServer start(
                Path executable, Path temporaryRoot, String java17Home, String java21Home) throws IOException {
            Path stdout = temporaryRoot.resolve("installed-editor.stdout");
            Path stderr = temporaryRoot.resolve("installed-editor.stderr");
            Path runtimeTemp = Files.createDirectory(temporaryRoot.resolve("installed-editor-tmp"));
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--port", "0")
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile());
            builder.environment().put("GEN2SPRING_JAVA_17_HOME", java17Home);
            builder.environment().put("GEN2SPRING_JAVA_21_HOME", java21Home);
            builder.environment().put("JAVA_OPTS", "-Djava.io.tmpdir=" + runtimeTemp);
            return new InstalledServer(builder.start(), stdout, stderr);
        }

        URI awaitReady() throws Exception {
            long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                observeTree();
                if (Files.isRegularFile(stdout) && Files.size(stdout) <= 1024 * 1024) {
                    for (String line : Files.readAllLines(stdout, UTF_8)) {
                        if (line.startsWith("{") && line.contains("\"status\":\"READY\"")) {
                            JsonNode ready = JSON.readTree(line);
                            assertEquals("READY", ready.path("status").textValue());
                            URI uri = URI.create(ready.path("url").textValue());
                            assertEquals("http", uri.getScheme());
                            assertEquals("127.0.0.1", InetAddress.getByName(uri.getHost()).getHostAddress());
                            assertTrue(uri.getPort() > 0 && uri.getPort() <= 65535);
                            assertEquals("/", uri.getPath());
                            assertEquals(null, uri.getRawQuery());
                            assertEquals(null, uri.getRawFragment());
                            assertEquals(null, uri.getUserInfo());
                            return uri;
                        }
                    }
                }
                if (!process.isAlive()) {
                    throw new AssertionError("installed editor stopped before startup");
                }
                Thread.sleep(50);
            }
            throw new AssertionError("installed editor startup timed out");
        }

        String output() throws IOException {
            return boundedRead(stdout) + boundedRead(stderr);
        }

        boolean observedProcessesStopped() {
            synchronized (observed) {
                return observed.values().stream().noneMatch(ProcessHandle::isAlive);
            }
        }

        @Override
        public void close() {
            observing.set(false);
            observer.interrupt();
            joinObserver();
            observeTree();
            terminate(false);
            awaitStopped(Duration.ofSeconds(3));
            terminate(true);
            awaitStopped(Duration.ofSeconds(3));
            if (!observedProcessesStopped()) {
                throw new AssertionError("installed editor process tree did not stop");
            }
        }

        private void observeTree() {
            synchronized (observed) {
                observed.putIfAbsent(process.pid(), process.toHandle());
                if (process.isAlive()) {
                    process.descendants().forEach(handle -> observed.putIfAbsent(handle.pid(), handle));
                }
            }
        }

        private void terminate(boolean forcibly) {
            List<ProcessHandle> handles;
            synchronized (observed) {
                handles = observed.values().stream()
                        .filter(ProcessHandle::isAlive)
                        .sorted(Comparator.comparingInt(InstalledServer::depth).reversed())
                        .toList();
            }
            handles.forEach(handle -> {
                if (forcibly) {
                    handle.destroyForcibly();
                } else {
                    handle.destroy();
                }
            });
        }

        private void awaitStopped(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && !observedProcessesStopped()) {
                observeTree();
                try {
                    TimeUnit.MILLISECONDS.sleep(25);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void joinObserver() {
            try {
                observer.join(Duration.ofSeconds(1).toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private static int depth(ProcessHandle handle) {
            int depth = 0;
            ProcessHandle current = handle;
            while (current.parent().isPresent() && depth < 128) {
                depth++;
                current = current.parent().orElseThrow();
            }
            return depth;
        }

        private String boundedRead(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                return "";
            }
            byte[] bytes = Files.readAllBytes(path);
            assertTrue(bytes.length <= 1024 * 1024, "installed editor output exceeded the test bound");
            return new String(bytes, UTF_8);
        }
    }
}

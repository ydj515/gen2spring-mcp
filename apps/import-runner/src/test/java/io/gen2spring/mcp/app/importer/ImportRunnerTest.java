package io.gen2spring.mcp.app.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportRunnerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void unwiredApplicationEntryPointFailsClosed() {
        assertRunnerFailure(() -> ImportRunnerApplication.main(new String[0]));
    }

    @Test
    void readsOneProtectedTargetCallsOnlyTheGatewayAndValidatesOpenApi() throws Exception {
        Path targetFile = readOnlyTarget("https://api.example.com/private/openapi.yaml?marker=secret");
        AtomicReference<ImportTarget> observed = new AtomicReference<>();
        byte[] source = validOpenApi();
        ImportRunner runner = new ImportRunner(
                new SwaggerOpenApiAnalyzer(),
                target -> {
                    observed.set(target);
                    return new ImportGatewayClient.Fetched(source, "application/yaml");
                },
                10 * 1024 * 1024);

        ImportRunner.ImportResult result = runner.run(targetFile, temporaryDirectory.resolve("workspace"));

        assertEquals("api.example.com", observed.get().host());
        assertArrayEquals(source, result.source());
        assertEquals("application/yaml", result.mediaType());
        assertFalse(result.toString().contains("api.example.com"));
        assertFalse(result.toString().contains("marker=secret"));
        assertFalse(Files.exists(temporaryDirectory.resolve("workspace")));
    }

    @Test
    void rejectsWritableSymlinkedAndInvalidInputsWithOneFixedFailure() throws Exception {
        Path writable = temporaryDirectory.resolve("writable-target");
        Files.writeString(writable, "https://private-marker.example.com/openapi.yaml");
        if (supportsPosix(writable)) {
            Files.setPosixFilePermissions(writable, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            assertRunnerFailure(() -> runner(validOpenApi()).run(
                    writable, temporaryDirectory.resolve("writable-workspace")));
        }

        Path readOnly = readOnlyTarget("https://api.example.com/openapi.yaml");
        Path symlink = temporaryDirectory.resolve("private-marker-link");
        try {
            Files.createSymbolicLink(symlink, readOnly);
            assertRunnerFailure(() -> runner(validOpenApi()).run(
                    symlink, temporaryDirectory.resolve("link-workspace")));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows CI may not grant symlink creation to the test process.
        }

        assertRunnerFailure(() -> runner("not-openapi".getBytes(StandardCharsets.UTF_8)).run(
                readOnly, temporaryDirectory.resolve("invalid-workspace")));
        assertRunnerFailure(() -> new ImportRunner(
                        new SwaggerOpenApiAnalyzer(),
                        target -> new ImportGatewayClient.Fetched(validOpenApi(), "text/html"),
                        10 * 1024 * 1024)
                .run(readOnly, temporaryDirectory.resolve("media-workspace")));
    }

    private ImportRunner runner(byte[] source) {
        return new ImportRunner(
                new SwaggerOpenApiAnalyzer(),
                target -> new ImportGatewayClient.Fetched(source, "application/yaml"),
                10 * 1024 * 1024);
    }

    private Path readOnlyTarget(String target) throws Exception {
        Path path = temporaryDirectory.resolve("target-" + java.util.UUID.randomUUID());
        Files.writeString(path, target, StandardCharsets.UTF_8);
        if (supportsPosix(path)) {
            Files.setPosixFilePermissions(path, EnumSet.of(PosixFilePermission.OWNER_READ));
        }
        return path;
    }

    private boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView("posix");
    }

    private byte[] validOpenApi() {
        return """
                openapi: 3.0.3
                info:
                  title: Weather
                  version: 1.0.0
                paths:
                  /weather:
                    get:
                      operationId: getWeather
                      responses:
                        '200':
                          description: OK
                """.getBytes(StandardCharsets.UTF_8);
    }

    private void assertRunnerFailure(Runnable action) {
        ImportRunnerFailure failure = assertThrows(ImportRunnerFailure.class, action::run);
        assertEquals("Specification import runner failed", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("private-marker"));
    }
}

package io.gen2spring.mcp.app.importer.application.imports;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.app.importer.application.imports.port.out.ImportGatewayClient;
import io.gen2spring.mcp.app.importer.infrastructure.analysis.WorkspaceSpecificationAnalyzer;
import io.gen2spring.mcp.app.importer.presentation.job.ImportJobProtocol;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ImportRunnerTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void jobProtocolPublishesOneSourceAndStrictResultWithoutPrivateData() throws Exception {
        Path target = readOnlyTarget("https://api.example.com/private/openapi.yaml?marker=secret");
        Path output = Files.createDirectory(temporaryDirectory.resolve("output"));
        Path work = temporaryDirectory.resolve("protocol-work");
        byte[] source = validOpenApi();
        ImportJobProtocol protocol = new ImportJobProtocol(runner(source, work));

        assertEquals(0, protocol.run(target, output));

        try (var files = Files.list(output)) {
            assertEquals(Set.of("source.yaml", "result.json"),
                    files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }
        String result = Files.readString(output.resolve("result.json"));
        assertTrue(result.contains("\"outcome\":\"SUCCESS\""));
        assertTrue(result.contains("\"mediaType\":\"application/yaml\""));
        assertFalse(result.contains("api.example.com"));
        assertFalse(result.contains("marker=secret"));
        assertFalse(Files.exists(work));
    }

    @Test
    void callsOnlyTheGatewayAndValidatesOpenApi() {
        AtomicReference<ImportTarget> observed = new AtomicReference<>();
        byte[] source = validOpenApi();
        Path work = temporaryDirectory.resolve("workspace");
        ImportRunner runner = new ImportRunner(
                new WorkspaceSpecificationAnalyzer(new SwaggerOpenApiAnalyzer(), work, 10 * 1024 * 1024),
                target -> {
                    observed.set(target);
                    return new ImportGatewayClient.Fetched(source, "application/yaml");
                },
                10 * 1024 * 1024);

        ImportRunner.ImportResult result = runner.run(
                ImportTarget.parse("https://api.example.com/private/openapi.yaml?marker=secret"));

        assertEquals("api.example.com", observed.get().host());
        assertArrayEquals(source, result.source());
        assertEquals("application/yaml", result.mediaType());
        assertFalse(result.toString().contains("api.example.com"));
        assertFalse(result.toString().contains("marker=secret"));
        assertFalse(Files.exists(work));
    }

    @Test
    void rejectsWritableAndSymlinkedTargetsAtTheDeliveryBoundary() throws Exception {
        Path writable = temporaryDirectory.resolve("writable-target");
        Files.writeString(writable, "https://private-marker.example.com/openapi.yaml");
        if (supportsPosix(writable)) {
            Files.setPosixFilePermissions(writable, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assertEquals(5, new ImportJobProtocol(runner(validOpenApi(), temporaryDirectory.resolve("writable-work")))
                    .run(writable, output("writable-output")));
        }

        Path readOnly = readOnlyTarget("https://api.example.com/openapi.yaml");
        Path symlink = temporaryDirectory.resolve("private-marker-link");
        try {
            Files.createSymbolicLink(symlink, readOnly);
            assertEquals(5, new ImportJobProtocol(runner(validOpenApi(), temporaryDirectory.resolve("link-work")))
                    .run(symlink, output("link-output")));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows CI may not grant symlink creation to the test process.
        }
    }

    @Test
    void rejectsInvalidSpecificationsAndMediaTypesWithOneFixedFailure() {
        ImportTarget target = ImportTarget.parse("https://api.example.com/openapi.yaml");
        assertRunnerFailure(() -> runner("not-openapi".getBytes(StandardCharsets.UTF_8),
                temporaryDirectory.resolve("invalid-work")).run(target));
        assertRunnerFailure(() -> new ImportRunner(
                        new WorkspaceSpecificationAnalyzer(new SwaggerOpenApiAnalyzer(),
                                temporaryDirectory.resolve("media-work"), 10 * 1024 * 1024),
                        ignored -> new ImportGatewayClient.Fetched(validOpenApi(), "text/html"),
                        10 * 1024 * 1024)
                .run(target));
    }

    @Test
    void leavesAnExistingWorkspaceUntouchedOnFailure() throws Exception {
        Path existing = Files.createDirectory(temporaryDirectory.resolve("existing-work"));
        assertRunnerFailure(() -> runner(validOpenApi(), existing).run(
                ImportTarget.parse("https://api.example.com/openapi.yaml")));
        assertTrue(Files.isDirectory(existing));
    }

    private ImportRunner runner(byte[] source, Path work) {
        return new ImportRunner(
                new WorkspaceSpecificationAnalyzer(new SwaggerOpenApiAnalyzer(), work, 10 * 1024 * 1024),
                target -> new ImportGatewayClient.Fetched(source, "application/yaml"),
                10 * 1024 * 1024);
    }

    private Path output(String name) throws IOException {
        return Files.createDirectory(temporaryDirectory.resolve(name));
    }

    private Path readOnlyTarget(String target) throws Exception {
        Path path = temporaryDirectory.resolve("target-" + UUID.randomUUID());
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

package io.gen2spring.mcp.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.ProjectCoordinates;
import io.gen2spring.mcp.domain.config.GenerationRequest.ToolCallValidation;
import io.gen2spring.mcp.domain.config.GenerationRequest.ValidationConfiguration;
import io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationOutcome;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ValidationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactHandlerTest {
    @TempDir
    Path tempDir;

    @Test
    void readsOnlyPinnedArtifactsAndRejectsAReplacedPath() throws Exception {
        try (GenerationJobManager jobs = new GenerationJobManager(
                tempDir, this::validated, Clock.systemUTC(), Duration.ofHours(1), ignored -> {})) {
            String id = jobs.submit(specification(), request()).id();
            jobs.await(id, Duration.ofSeconds(5));
            ArtifactHandler handler = new ArtifactHandler(jobs);

            ArtifactHandler.Download archive = handler.download(id, "archive");
            assertEquals("application/zip", archive.contentType());
            assertEquals("attachment; filename=\"" + id + ".zip\"", archive.contentDisposition());
            assertArrayEquals("zip".getBytes(java.nio.charset.StandardCharsets.UTF_8), archive.bytes());

            GenerationJobManager.Artifact manifest = jobs.artifact(id, "manifest");
            Files.delete(manifest.path());
            Path outside = Files.writeString(tempDir.resolve("outside.json"), "private");
            Files.createSymbolicLink(manifest.path(), outside);
            assertThrows(GenerationJobManager.ArtifactUnavailableException.class,
                    () -> handler.download(id, "manifest"));
        }
    }

    private GenerationOutcome validated(
            Path specification,
            GenerationRequest request,
            Path output,
            io.gen2spring.mcp.domain.generation.GenerationContracts.GenerationProgressListener progress)
            throws Exception {
        Files.createDirectory(output);
        Files.writeString(output.resolve("GENERATION_MANIFEST.json"), "manifest");
        Files.writeString(output.resolve("VALIDATION_REPORT.json"), "report");
        Path archive = Files.writeString(output.resolveSibling(output.getFileName() + ".zip"), "zip");
        return new GenerationOutcome(output, archive, ValidationStatus.VALIDATED, "checksum");
    }

    private Path specification() throws Exception {
        return Files.writeString(tempDir.resolve("weather.yaml"), "openapi: 3.0.3");
    }

    private GenerationRequest request() {
        return new GenerationRequest(
                new ProjectCoordinates("com.example", "weather-mcp", "com.example.weather"),
                "weather", "forecast", "spring-ai-2.0-java21-mvc-streamable",
                GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation("getForecast", Map.of("city", "Seoul"))),
                List.of());
    }
}

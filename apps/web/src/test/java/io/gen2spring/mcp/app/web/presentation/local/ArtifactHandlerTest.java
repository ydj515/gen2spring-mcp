package io.gen2spring.mcp.app.web.presentation.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.gen2spring.mcp.app.web.application.local.exception.LocalJobFailure;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationConfigurationDecoder;
import io.gen2spring.mcp.app.web.application.local.port.out.SpecificationStorage;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import io.gen2spring.mcp.app.web.infrastructure.local.job.GenerationJobManager;
import io.gen2spring.mcp.app.web.infrastructure.local.job.JobWorkspace;
import io.gen2spring.mcp.application.generation.command.GenerationCommand;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.ToolCallValidation;
import io.gen2spring.mcp.application.generation.command.GenerationCommand.ValidationConfiguration;
import io.gen2spring.mcp.application.generation.port.out.GenerationProgressListener;
import io.gen2spring.mcp.application.generation.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.generation.usecase.GenerationPipeline;
import io.gen2spring.mcp.application.generation.validation.ValidationStatus;
import java.nio.charset.StandardCharsets;
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
            ArtifactHandler handler = new ArtifactHandler(new LocalGenerationService(
                    mock(SpecificationStorage.class), jobs, mock(GenerationPipeline.class),
                    mock(GenerationConfigurationDecoder.class)));

            ArtifactHandler.Download archive = handler.download(id, "archive");
            assertEquals("application/zip", archive.contentType());
            assertEquals("attachment; filename=\"" + id + ".zip\"", archive.contentDisposition());
            assertArrayEquals("zip".getBytes(StandardCharsets.UTF_8), archive.bytes());

            JobWorkspace.Artifact manifest = jobs.artifact(id, "manifest");
            Files.delete(manifest.path());
            Path outside = Files.writeString(tempDir.resolve("outside.json"), "private");
            Files.createSymbolicLink(manifest.path(), outside);
            assertThrows(LocalJobFailure.class,
                    () -> handler.download(id, "manifest"));
        }
    }

    private GenerationOutcome validated(
            Path specification,
            GenerationCommand request,
            Path output,
            GenerationProgressListener progress)
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

    private GenerationCommand request() {
        return new GenerationCommand(
                new ProjectCoordinates("com.example", "weather-mcp", "com.example.weather"),
                "weather", "forecast", "spring-ai-2.0-java21-mvc-streamable",
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation("getForecast", Map.of("city", "Seoul"))),
                List.of());
    }
}

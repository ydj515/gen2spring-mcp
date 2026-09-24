package io.gen2spring.mcp.app.web.infrastructure.local.job;

import static io.gen2spring.mcp.application.validation.ValidationStatus.VALIDATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JobWorkspaceTest {
    @TempDir
    Path temporaryParent;

    @Test
    void pinsArtifactsAndDeletesOnlyItsOwnedJobFiles() throws Exception {
        Path outside = Files.writeString(temporaryParent.resolve("outside.txt"), "keep");
        try (var workspace = new JobWorkspace(temporaryParent)) {
            String jobId = "a".repeat(64);
            Path project = Files.createDirectory(workspace.root().resolve(jobId));
            Path manifest = Files.writeString(project.resolve("GENERATION_MANIFEST.json"), "{}");
            Path report = Files.writeString(project.resolve("VALIDATION_REPORT.json"), "{}");
            Path archive = Files.writeString(project.resolveSibling(jobId + ".zip"), "zip");

            var artifacts = workspace.capture(
                    jobId, new GenerationOutcome(project, archive, VALIDATED, "checksum"));

            assertEquals(Set.of("archive", "manifest", "report"), artifacts.keySet());
            assertTrue(artifacts.get("manifest").stable());
            assertEquals(Files.size(manifest), artifacts.get("manifest").size());
            assertEquals(Files.size(report), artifacts.get("report").size());

            workspace.deleteJob(project);

            assertFalse(Files.exists(project));
            assertFalse(Files.exists(archive));
            assertTrue(Files.exists(outside));
        }
        assertTrue(Files.exists(outside));
    }
}

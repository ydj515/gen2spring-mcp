package io.gen2spring.mcp.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.ProjectCoordinates;
import io.gen2spring.mcp.application.command.GenerationCommand.ToolCallValidation;
import io.gen2spring.mcp.application.command.GenerationCommand.ValidationConfiguration;
import io.gen2spring.mcp.domain.error.GeneratorErrorCode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.application.usecase.GenerationOutcome;
import io.gen2spring.mcp.application.usecase.GenerationProgress;
import io.gen2spring.mcp.application.usecase.ProgressStatus;
import io.gen2spring.mcp.application.validation.ValidationStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationJobManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void runsOneJobQueuesOneRejectsTheThirdAndSealsOrderedProgress() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GenerationExecutor executor = (specification, request, output, progress) -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            for (String stage : GenerationProgress.STAGES) {
                progress.onProgress(new GenerationProgress(stage, ProgressStatus.RUNNING));
                progress.onProgress(new GenerationProgress(stage, ProgressStatus.SUCCESS));
            }
            progress.onProgress(new GenerationProgress("ANALYZE", ProgressStatus.RUNNING));
            return validatedOutput(output);
        };

        try (GenerationJobManager jobs = manager(executor)) {
            JobSnapshot first = jobs.submit(specification(), request());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            JobSnapshot second = jobs.submit(specification(), request());
            assertEquals(JobSnapshot.State.QUEUED, second.state());
            assertThrows(GenerationJobManager.GenerationCapacityException.class,
                    () -> jobs.submit(specification(), request()));

            release.countDown();
            JobSnapshot complete = jobs.await(first.id(), Duration.ofSeconds(5));
            assertEquals(JobSnapshot.State.VALIDATED, complete.state());
            assertEquals(GenerationProgress.STAGES,
                    complete.stages().stream().map(JobSnapshot.JobStage::stage).toList());
            assertTrue(complete.stages().stream().allMatch(stage -> stage.status() == ProgressStatus.SUCCESS));
            assertEquals(List.of("archive", "manifest", "report"), complete.downloads());
            assertEquals("application/zip", jobs.artifact(first.id(), "archive").contentType());
            assertEquals(JobSnapshot.State.VALIDATED,
                    jobs.await(second.id(), Duration.ofSeconds(5)).state());
        }
    }

    @Test
    void rejectedActiveWorkDoesNotEvictRetainedArtifacts() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GenerationExecutor executor = (specification, request, output, progress) -> {
            if (executions.incrementAndGet() > 6) {
                running.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return validatedOutput(output);
        };

        try (GenerationJobManager jobs = manager(executor)) {
            List<String> retained = new java.util.ArrayList<>();
            for (int index = 0; index < 6; index++) {
                String id = jobs.submit(specification(), request()).id();
                jobs.await(id, Duration.ofSeconds(5));
                retained.add(id);
            }
            String active = jobs.submit(specification(), request()).id();
            assertTrue(running.await(5, TimeUnit.SECONDS));
            String queued = jobs.submit(specification(), request()).id();

            assertThrows(GenerationJobManager.GenerationCapacityException.class,
                    () -> jobs.submit(specification(), request()));

            assertEquals(JobSnapshot.State.VALIDATED, jobs.snapshot(retained.getFirst()).state());
            assertEquals("application/zip", jobs.artifact(retained.getFirst(), "archive").contentType());
            release.countDown();
            assertEquals(JobSnapshot.State.VALIDATED,
                    jobs.await(active, Duration.ofSeconds(5)).state());
            assertEquals(JobSnapshot.State.VALIDATED,
                    jobs.await(queued, Duration.ofSeconds(5)).state());
        }
    }

    @Test
    void recordsOnlySafeFailuresAndPreservesFatalIdentity() throws Exception {
        AtomicReference<Error> fatal = new AtomicReference<>();
        GeneratorException expected = GeneratorException.user(
                GeneratorErrorCode.OPERATION_UNSUPPORTED, "TOOL_POLICY", "The operation is unsupported");
        try (GenerationJobManager jobs = manager((specification, request, output, progress) -> {
            Files.createDirectory(output);
            Files.writeString(output.resolve("GENERATION_MANIFEST.json"), "{}");
            Files.writeString(output.resolve("VALIDATION_REPORT.json"), "{}");
            throw expected;
        })) {
            JobSnapshot failed = jobs.await(jobs.submit(specification(), request()).id(), Duration.ofSeconds(5));
            assertEquals(JobSnapshot.State.FAILED, failed.state());
            assertEquals("OPERATION_UNSUPPORTED", failed.error().code());
            assertEquals("TOOL_POLICY", failed.error().stage());
            assertEquals("The operation is unsupported", failed.error().message());
            assertEquals(List.of("manifest", "report"), failed.downloads());
        }

        AssertionError expectedFatal = new AssertionError("private-fatal-marker");
        try (GenerationJobManager jobs = new GenerationJobManager(
                tempDir, (specification, request, output, progress) -> { throw expectedFatal; },
                Clock.systemUTC(), Duration.ofHours(1), fatal::set)) {
            JobSnapshot failed = jobs.await(jobs.submit(specification(), request()).id(), Duration.ofSeconds(5));
            assertEquals(JobSnapshot.State.FAILED, failed.state());
            assertEquals("INTERNAL_ERROR", failed.error().code());
            assertFalse(failed.error().message().contains("private-fatal-marker"));
            for (int index = 0; index < 50 && fatal.get() == null; index++) {
                Thread.sleep(10);
            }
            assertSame(expectedFatal, fatal.get());
        }
    }

    @Test
    void removesTerminalJobsExplicitlyAndAfterTheConfiguredTtl() throws Exception {
        Instant started = Instant.parse("2026-08-11T00:00:00Z");
        MutableClock clock = new MutableClock(started);
        try (GenerationJobManager jobs = new GenerationJobManager(
                tempDir, this::validatedOutput, clock, Duration.ofMinutes(1), ignored -> {})) {
            String deleted = jobs.submit(specification(), request()).id();
            jobs.await(deleted, Duration.ofSeconds(5));
            jobs.delete(deleted);
            assertThrows(GenerationJobManager.JobNotFoundException.class, () -> jobs.snapshot(deleted));

            String expired = jobs.submit(specification(), request()).id();
            jobs.await(expired, Duration.ofSeconds(5));
            clock.advance(Duration.ofMinutes(2));
            jobs.cleanupExpired();
            assertThrows(GenerationJobManager.JobNotFoundException.class, () -> jobs.snapshot(expired));

            List<String> retained = new java.util.ArrayList<>();
            for (int index = 0; index < 9; index++) {
                String id = jobs.submit(specification(), request()).id();
                jobs.await(id, Duration.ofSeconds(5));
                retained.add(id);
                clock.advance(Duration.ofSeconds(1));
            }
            assertThrows(GenerationJobManager.JobNotFoundException.class,
                    () -> jobs.snapshot(retained.getFirst()));
            assertEquals(JobSnapshot.State.VALIDATED,
                    jobs.snapshot(retained.getLast()).state());
        }
    }

    @Test
    void closeInterruptsRunningWorkAndDeletesOnlyItsOwnedWorkspace() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Path unrelated = Files.writeString(tempDir.resolve("unrelated.txt"), "keep");
        GenerationJobManager jobs = manager((specification, request, output, progress) -> {
            entered.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("private-worker-marker");
        });
        Path root = jobs.root();
        jobs.submit(specification(), request());
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        jobs.close();

        assertTrue(interrupted.get());
        assertFalse(Files.exists(root));
        assertTrue(Files.exists(unrelated));
    }

    private GenerationJobManager manager(GenerationExecutor executor) {
        return new GenerationJobManager(
                tempDir, executor, Clock.systemUTC(), Duration.ofHours(1), ignored -> {});
    }

    private GenerationOutcome validatedOutput(
            Path specification,
            GenerationCommand request,
            Path output,
            io.gen2spring.mcp.application.usecase.GenerationProgressListener progress)
            throws Exception {
        return validatedOutput(output);
    }

    private GenerationOutcome validatedOutput(Path output) throws Exception {
        Files.createDirectory(output);
        Files.writeString(output.resolve("GENERATION_MANIFEST.json"), "{}");
        Files.writeString(output.resolve("VALIDATION_REPORT.json"), "{}");
        Path archive = Files.writeString(output.resolveSibling(output.getFileName() + ".zip"), "zip");
        return new GenerationOutcome(output, archive, ValidationStatus.VALIDATED, "checksum");
    }

    private Path specification() throws Exception {
        Path specification = tempDir.resolve("weather.yaml");
        if (!Files.exists(specification)) {
            Files.writeString(specification, "openapi: 3.0.3");
        }
        return specification;
    }

    private GenerationCommand request() {
        return new GenerationCommand(
                new ProjectCoordinates("com.example", "weather-mcp", "com.example.weather"),
                "weather",
                "forecast",
                "spring-ai-2.0-java21-mvc-streamable",
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new ValidationConfiguration(new ToolCallValidation("getForecast", Map.of("city", "Seoul"))),
                List.of());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

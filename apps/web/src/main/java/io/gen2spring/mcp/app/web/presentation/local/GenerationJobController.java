package io.gen2spring.mcp.app.web.presentation.local;

import io.gen2spring.mcp.app.web.application.local.result.VersionedJobSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import io.gen2spring.mcp.app.web.presentation.stream.JobEventStream;
import io.gen2spring.mcp.app.web.application.local.job.JobSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class GenerationJobController {
    private static final Set<JobSnapshot.State> TERMINAL = EnumSet.of(
            JobSnapshot.State.VALIDATED, JobSnapshot.State.UNVERIFIED, JobSnapshot.State.FAILED);

    private final JobHandler jobs;
    private final LocalGenerationService generation;
    private final JobEventStream streams;

    GenerationJobController(JobHandler jobs, LocalGenerationService generation, JobEventStream streams) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.streams = Objects.requireNonNull(streams, "streams");
    }

    @PostMapping(
            path = "/api/specifications/{id}/jobs",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> start(
            @PathVariable String id,
            @RequestHeader("Content-Type") String contentType,
            HttpServletRequest request) throws IOException {
        WebContentTypes.requireJson(contentType);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(jobs.start(WebApiRoutes.requireIdentifier(id), request.getInputStream()));
    }

    @GetMapping("/api/jobs/{id}")
    JsonNode status(@PathVariable String id) {
        return jobs.status(WebApiRoutes.requireIdentifier(id));
    }

    @GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(@PathVariable String id) {
        String jobId = WebApiRoutes.requireIdentifier(id);
        // Resolving up front makes an unknown id fail as the ordinary not-found
        // response instead of opening a stream that would die on its first read.
        VersionedJobSnapshot initial = generation.current(jobId);
        return streams.open((since, timeout) -> {
            if (since < initial.version()) {
                return Optional.of(change(initial));
            }
            return generation.awaitChange(jobId, since, timeout).map(this::change);
        });
    }

    private JobEventStream.Change change(VersionedJobSnapshot versioned) {
        return new JobEventStream.Change(
                versioned.version(),
                jobs.snapshotPayload(versioned.snapshot()),
                TERMINAL.contains(versioned.snapshot().state()));
    }

    @DeleteMapping("/api/jobs/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        jobs.delete(WebApiRoutes.requireIdentifier(id));
        return ResponseEntity.noContent().build();
    }
}

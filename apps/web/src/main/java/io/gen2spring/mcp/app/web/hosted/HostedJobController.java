package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.app.web.job.JobEventStream;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
public final class HostedJobController {
    private final HostedAccountResolver accounts;
    private final HostedSubmissionService submissions;
    private final HostedJobService jobs;
    private final HostedResourceStore resources;
    private final ObjectMapper json;
    private final JobEventStream streams;

    HostedJobController(
            HostedAccountResolver accounts,
            HostedSubmissionService submissions,
            HostedJobService jobs,
            HostedResourceStore resources,
            ObjectMapper json,
            JobEventStream streams) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.json = Objects.requireNonNull(json, "json");
        this.streams = Objects.requireNonNull(streams, "streams");
    }

    @PostMapping(path = "/api/jobs", consumes = "application/json")
    ResponseEntity<JsonNode> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2
                || !body.path("specificationId").isTextual() || !body.path("configuration").isObject()) {
            throw new HostedSubmissionService.HostedSubmissionFailure();
        }
        try {
            var result = submissions.generate(
                    accounts.resolve(authentication).accountId(),
                    SpecificationId.parse(body.path("specificationId").textValue()),
                    idempotencyKey,
                    json.writeValueAsBytes(body.path("configuration")));
            return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(json.createObjectNode()
                            .put("jobId", result.job().id().value().toString())
                            .put("status", result.job().status().name())
                            .put("replayed", result.replayed()));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new HostedSubmissionService.HostedSubmissionFailure();
        }
    }

    @GetMapping("/api/jobs/{id}")
    JsonNode get(Authentication authentication, @PathVariable String id) {
        return payload(accounts.resolve(authentication).accountId(), jobId(id)).node();
    }

    @GetMapping(path = "/api/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(Authentication authentication, @PathVariable String id) {
        var owner = accounts.resolve(authentication).accountId();
        JobId jobId = jobId(id);
        // Settle ownership before a stream exists. Opening first would let the
        // stream's own behaviour disclose whether another account's job is real.
        payload(owner, jobId);
        HostedJobEventFeed feed = new HostedJobEventFeed(() -> payload(owner, jobId));
        return streams.open(feed::awaitChange);
    }

    /**
     * Builds the job record once for both transports, so the polling endpoint and
     * the event stream can never drift into different payload shapes.
     */
    private HostedJobEventFeed.Payload payload(AccountId owner, JobId jobId) {
        var job = resources.job(owner, jobId).orElseThrow(HostedResourceNotFound::new);
        var result = json.createObjectNode()
                .put("id", job.id().value().toString())
                .put("kind", job.kind().name())
                .put("status", job.status().name())
                .put("attempt", job.attempt())
                .put("cancellationRequested", job.cancellationRequested())
                .put("createdAt", job.createdAt().toString())
                .put("updatedAt", job.updatedAt().toString());
        if (job.specificationId().isPresent()) result.put("specificationId", job.specificationId().get().value().toString());
        else result.putNull("specificationId");
        var events = result.putArray("events");
        long highestSequence = 0;
        for (var event : resources.events(owner, jobId, 100)) {
            highestSequence = Math.max(highestSequence, event.sequence());
            events.addObject()
                    .put("sequence", event.sequence()).put("status", event.toStatus().name())
                    .put("stage", event.stage()).put("code", event.safeCode())
                    .put("summary", event.safeSummary()).put("createdAt", event.createdAt().toString());
        }
        var artifacts = result.putArray("artifacts");
        resources.artifacts(owner, jobId).forEach(artifact -> artifacts.addObject()
                .put("id", artifact.id().toString()).put("type", artifact.type())
                .put("byteSize", artifact.byteSize()).put("contentType", artifact.contentType())
                .put("expiresAt", artifact.expiresAt().toString()));
        return new HostedJobEventFeed.Payload(result, highestSequence, job.status());
    }

    @PostMapping("/api/jobs/{id}/cancellation")
    ResponseEntity<Void> cancel(Authentication authentication, @PathVariable String id) {
        var owner = accounts.resolve(authentication).accountId();
        JobId jobId = jobId(id);
        jobs.require(owner, jobId);
        jobs.cancel(owner, jobId);
        return ResponseEntity.accepted().build();
    }

    private JobId jobId(String value) {
        try { return new JobId(UUID.fromString(value)); }
        catch (RuntimeException failure) { throw new HostedResourceNotFound(); }
    }

    public static final class HostedResourceNotFound extends RuntimeException {
        public HostedResourceNotFound() { super("Hosted resource was not found", null, false, false); }
    }
}

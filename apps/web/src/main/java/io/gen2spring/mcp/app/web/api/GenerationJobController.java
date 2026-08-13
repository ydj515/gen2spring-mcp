package io.gen2spring.mcp.app.web.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "gen2spring.mode", havingValue = "local", matchIfMissing = true)
final class GenerationJobController {
    private final JobHandler jobs;

    GenerationJobController(JobHandler jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
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

    @DeleteMapping("/api/jobs/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        jobs.delete(WebApiRoutes.requireIdentifier(id));
        return ResponseEntity.noContent().build();
    }
}

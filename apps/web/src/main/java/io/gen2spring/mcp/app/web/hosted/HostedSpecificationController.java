package io.gen2spring.mcp.app.web.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.app.web.api.GenerationPreviewPresenter;
import io.gen2spring.mcp.app.web.api.SpecificationAnalysisPresenter;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedSpecificationController {
    private final HostedAccountResolver accounts;
    private final HostedSubmissionService submissions;
    private final HostedResourceStore resources;
    private final ObjectMapper json;
    private final SpecificationAnalysisPresenter analysisPresenter;
    private final GenerationPreviewPresenter previewPresenter;
    private final HostedCursorCodec cursors = new HostedCursorCodec();

    HostedSpecificationController(
            HostedAccountResolver accounts,
            HostedSubmissionService submissions,
            HostedResourceStore resources,
            ObjectMapper json) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.json = Objects.requireNonNull(json, "json");
        this.analysisPresenter = new SpecificationAnalysisPresenter(this.json);
        this.previewPresenter = new GenerationPreviewPresenter(this.json);
    }

    @PostMapping(path = "/api/specifications/uploads", consumes = {
            "application/json", "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"
    })
    ResponseEntity<JsonNode> upload(
            Authentication authentication,
            @RequestHeader("Content-Type") String contentType,
            @RequestHeader("X-Specification-Name") String specificationName,
            HttpServletRequest request) throws IOException {
        var owner = accounts.resolve(authentication).accountId();
        var result = submissions.upload(
                owner, request.getInputStream(), contentType, specificationName);
        return ResponseEntity.status(HttpStatus.CREATED).body(analysis(result));
    }

    @GetMapping("/api/specifications/{id}/analysis")
    JsonNode analysis(Authentication authentication, @PathVariable String id) {
        var result = submissions.analysis(
                accounts.resolve(authentication).accountId(), specificationId(id));
        return analysis(result);
    }

    @PostMapping(path = "/api/specifications/{id}/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    JsonNode preview(
            Authentication authentication,
            @PathVariable String id,
            @RequestBody byte[] configuration) {
        return previewPresenter.present(submissions.preview(
                accounts.resolve(authentication).accountId(), specificationId(id), configuration));
    }

    @PostMapping(path = "/api/specifications/imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> importUrl(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("url").isTextual()) {
            throw new HostedSubmissionService.HostedSubmissionFailure();
        }
        var result = submissions.importUrl(
                accounts.resolve(authentication).accountId(), idempotencyKey, body.path("url").textValue());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(json.createObjectNode()
                        .put("jobId", result.job().id().value().toString())
                        .put("status", result.job().status().name())
                        .put("replayed", result.replayed()));
    }

    @GetMapping("/api/specifications")
    JsonNode specifications(
            Authentication authentication,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 50) throw new HostedSubmissionService.HostedSubmissionFailure();
        var owner = accounts.resolve(authentication).accountId();
        var result = json.createObjectNode();
        var values = result.putArray("items");
        java.util.Optional<HostedResourceStore.ResourceCursor> decoded;
        try { decoded = cursors.decode(cursor); }
        catch (IllegalArgumentException failure) { throw new HostedSubmissionService.HostedSubmissionFailure(); }
        var page = resources.specifications(owner, limit + 1, decoded);
        page.stream().limit(limit).forEach(specification -> values.addObject()
                .put("id", specification.id().value().toString())
                .put("sourceType", specification.sourceType())
                .put("label", specification.label())
                .put("byteSize", specification.byteSize())
                .put("createdAt", specification.createdAt().toString()));
        if (page.size() > limit) {
            var last = page.get(limit - 1);
            result.put("nextCursor", cursors.encode(new HostedResourceStore.ResourceCursor(
                    last.createdAt(), last.id().value())));
        } else {
            result.putNull("nextCursor");
        }
        return result;
    }

    private JsonNode analysis(HostedSubmissionService.HostedSpecificationAnalysis result) {
        return analysisPresenter.present(
                result.id().value().toString(),
                result.displayLabel(),
                result.byteSize(),
                result.analysis());
    }

    private SpecificationId specificationId(String value) {
        try {
            return new SpecificationId(UUID.fromString(value));
        } catch (RuntimeException failure) {
            throw new HostedSubmissionService.HostedSpecificationNotFound();
        }
    }
}

package io.gen2spring.mcp.app.web.presentation.hosted;

import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedArtifactDownloadService;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedArtifactDownloadService.HostedArtifactUnavailable;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
public final class HostedArtifactController {
    private final HostedAccountResolver accounts;
    private final HostedArtifactDownloadService artifacts;

    HostedArtifactController(HostedAccountResolver accounts, HostedArtifactDownloadService artifacts) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    @GetMapping("/api/artifacts/{id}/content")
    void download(Authentication authentication, @PathVariable String id, HttpServletResponse response) {
        UUID artifactId;
        try { artifactId = UUID.fromString(id); }
        catch (RuntimeException failure) { throw new HostedResourceNotFound(); }
        var owner = accounts.resolve(authentication).accountId();
        var artifact = artifacts.download(owner, artifactId);
        try (artifact) {
            response.setStatus(200);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(HttpHeaders.CONTENT_TYPE, artifact.contentType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + artifact.downloadName() + "\"");
            response.setContentLengthLong(artifact.byteSize());
            artifact.writeTo(response.getOutputStream());
        } catch (IOException | RuntimeException failure) {
            throw new HostedArtifactUnavailable(failure);
        }
    }
}

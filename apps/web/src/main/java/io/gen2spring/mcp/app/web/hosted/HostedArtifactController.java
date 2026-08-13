package io.gen2spring.mcp.app.web.hosted;

import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import jakarta.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
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
    private final HostedResourceStore resources;
    private final ObjectStorage storage;

    HostedArtifactController(
            HostedAccountResolver accounts,
            HostedResourceStore resources,
            ObjectStorage storage) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @GetMapping("/api/artifacts/{id}/content")
    void download(Authentication authentication, @PathVariable String id, HttpServletResponse response) {
        UUID artifactId;
        try { artifactId = UUID.fromString(id); }
        catch (RuntimeException failure) { throw new HostedJobController.HostedResourceNotFound(); }
        var owner = accounts.resolve(authentication).accountId();
        var artifact = resources.artifact(owner, artifactId)
                .orElseThrow(HostedJobController.HostedResourceNotFound::new);
        Path verified = null;
        try (var content = storage.get(artifact.objectKey()); var input = content.body()) {
            if (content.size() != artifact.byteSize() || !content.sha256().equals(artifact.sha256())
                    || !content.contentType().equals(artifact.contentType())) {
                throw new IllegalStateException();
            }
            verified = Files.createTempFile("gen2spring-artifact-", ".download");
            restrict(verified);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            try (var output = Files.newOutputStream(verified)) {
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > artifact.byteSize()) throw new IllegalStateException();
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total != artifact.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
                throw new IllegalStateException();
            }
            response.setStatus(200);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(HttpHeaders.CONTENT_TYPE, artifact.contentType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + artifact.type().toLowerCase(java.util.Locale.ROOT) + "\"");
            response.setContentLengthLong(artifact.byteSize());
            Files.copy(verified, response.getOutputStream());
        } catch (HostedJobController.HostedResourceNotFound notFound) {
            throw notFound;
        } catch (Exception failure) {
            throw new HostedArtifactFailure();
        } finally {
            if (verified != null) {
                try { Files.deleteIfExists(verified); }
                catch (Exception ignored) { /* The bounded temporary file is reclaimed by the host. */ }
            }
        }
    }

    private void restrict(Path file) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(file, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows uses the temporary directory ACL.
        }
    }

    public static final class HostedArtifactFailure extends RuntimeException {
        public HostedArtifactFailure() { super("Hosted artifact download failed", null, false, false); }
    }
}

package io.gen2spring.mcp.app.web.application.hosted.service;

import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader;
import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader.ArtifactSource;
import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader.VerifiedArtifact;
import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class HostedArtifactDownloadService {
    private final HostedResourceStore resources;
    private final VerifiedArtifactReader reader;

    public HostedArtifactDownloadService(HostedResourceStore resources, VerifiedArtifactReader reader) {
        this.resources = Objects.requireNonNull(resources, "resources");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public ArtifactDownload download(AccountId owner, UUID id) {
        var artifact = resources.artifact(owner, id).orElseThrow(HostedResourceNotFound::new);
        try {
            return new ArtifactDownload(reader.open(new ArtifactSource(
                    artifact.objectKey(), artifact.sha256(), artifact.byteSize(), artifact.contentType())),
                    artifact.contentType(),
                    artifact.type().toLowerCase(Locale.ROOT), artifact.byteSize());
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new HostedArtifactUnavailable(failure);
        }
    }

    public record ArtifactDownload(VerifiedArtifact content, String contentType, String downloadName, long byteSize)
            implements AutoCloseable {
        public ArtifactDownload {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(downloadName, "downloadName");
        }

        public void writeTo(OutputStream output) throws IOException {
            content.writeTo(output);
        }

        @Override
        public void close() {
            content.close();
        }
    }

    public static final class HostedArtifactUnavailable extends RuntimeException {
        public HostedArtifactUnavailable(Throwable cause) {
            super("Hosted artifact download failed", cause);
        }
    }
}

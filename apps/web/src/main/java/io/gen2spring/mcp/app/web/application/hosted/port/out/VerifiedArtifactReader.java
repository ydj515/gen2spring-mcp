package io.gen2spring.mcp.app.web.application.hosted.port.out;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public interface VerifiedArtifactReader {
    VerifiedArtifact open(ArtifactSource artifact);

    record ArtifactSource(ObjectKey objectKey, String sha256, long byteSize, String contentType) {
        public ArtifactSource {
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(contentType, "contentType");
        }
    }

    interface VerifiedArtifact extends AutoCloseable {
        void writeTo(OutputStream output) throws IOException;

        @Override
        void close();
    }
}

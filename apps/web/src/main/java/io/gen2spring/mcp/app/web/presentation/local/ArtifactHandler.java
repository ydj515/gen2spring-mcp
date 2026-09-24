package io.gen2spring.mcp.app.web.presentation.local;

import io.gen2spring.mcp.app.web.application.local.result.ArtifactDownload;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import java.util.Objects;

public final class ArtifactHandler {
    private final LocalGenerationService generation;

    public ArtifactHandler(LocalGenerationService generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    Download download(String jobId, String name) {
        ArtifactDownload artifact = generation.download(jobId, name);
        return new Download(artifact.bytes(), artifact.contentType(),
                "attachment; filename=\"" + artifact.downloadName() + "\"");
    }

    record Download(byte[] bytes, String contentType, String contentDisposition) {
        Download {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}

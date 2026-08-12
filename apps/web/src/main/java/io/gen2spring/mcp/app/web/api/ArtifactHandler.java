package io.gen2spring.mcp.app.web.api;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Objects;
import io.gen2spring.mcp.app.web.job.GenerationJobManager;
import io.gen2spring.mcp.app.web.job.JobWorkspace;
import io.gen2spring.mcp.app.web.error.WebErrorMapper;

public final class ArtifactHandler {
    private final GenerationJobManager jobs;

    public ArtifactHandler(GenerationJobManager jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    Download download(String jobId, String name) {
        JobWorkspace.Artifact artifact = jobs.artifact(jobId, name);
        try (var input = Files.newInputStream(artifact.path())) {
            byte[] bytes = new BoundedBodyReader(Math.toIntExact(artifact.size())).read(input);
            if (bytes.length != artifact.size()
                    || !MessageDigest.isEqual(artifact.digest(), JobWorkspace.digest(bytes))
                    || !jobs.artifact(jobId, name).equals(artifact)) {
                throw new GenerationJobManager.ArtifactUnavailableException();
            }
            return new Download(
                    bytes,
                    artifact.contentType(),
                    "attachment; filename=\"" + artifact.downloadName() + "\"");
        } catch (IOException | WebErrorMapper.WebException | BoundedBodyReader.BodyReadException exception) {
            throw new GenerationJobManager.ArtifactUnavailableException();
        }
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

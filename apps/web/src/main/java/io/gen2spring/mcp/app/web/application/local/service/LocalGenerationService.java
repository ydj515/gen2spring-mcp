package io.gen2spring.mcp.app.web.application.local.service;

import io.gen2spring.mcp.app.web.application.local.job.JobSnapshot;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationJobs;
import io.gen2spring.mcp.app.web.application.local.port.out.SpecificationStorage;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationConfigurationDecoder;
import io.gen2spring.mcp.app.web.application.local.result.ArtifactDownload;
import io.gen2spring.mcp.app.web.application.local.result.StoredSpecification;
import io.gen2spring.mcp.app.web.application.local.result.VersionedJobSnapshot;
import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.usecase.GenerationPipeline;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Coordinates local specification retention, preview, and asynchronous generation. */
public final class LocalGenerationService {
    private final SpecificationStorage specifications;
    private final GenerationJobs jobs;
    private final GenerationPipeline pipeline;
    private final GenerationConfigurationDecoder configuration;

    public LocalGenerationService(
            SpecificationStorage specifications,
            GenerationJobs jobs,
            GenerationPipeline pipeline,
            GenerationConfigurationDecoder configuration) {
        this.specifications = Objects.requireNonNull(specifications, "specifications");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public StoredSpecification upload(String name, InputStream source) {
        return specifications.store(name, source);
    }

    public GenerationPreview preview(String specificationId, byte[] configurationBytes) {
        StoredSpecification stored = specifications.retain(specificationId);
        try {
            return pipeline.preview(stored.path(), configuration.decode(configurationBytes));
        } finally {
            specifications.release(specificationId);
        }
    }

    public JobSnapshot submit(String specificationId, byte[] configurationBytes) {
        StoredSpecification stored = specifications.retain(specificationId);
        try {
            GenerationCommand command = configuration.decode(configurationBytes);
            return jobs.submit(stored.path(), command, () -> specifications.release(specificationId));
        } catch (Error | RuntimeException failure) {
            specifications.release(specificationId);
            throw failure;
        }
    }

    public JobSnapshot snapshot(String identifier) {
        return jobs.snapshot(identifier);
    }

    public VersionedJobSnapshot current(String identifier) {
        return jobs.current(identifier);
    }

    public Optional<VersionedJobSnapshot> awaitChange(
            String identifier, long sinceVersion, Duration timeout) throws InterruptedException {
        return jobs.awaitChange(identifier, sinceVersion, timeout);
    }

    public ArtifactDownload download(String identifier, String name) {
        return jobs.download(identifier, name);
    }

    public void delete(String identifier) {
        jobs.delete(identifier);
    }
}

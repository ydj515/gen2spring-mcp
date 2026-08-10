package io.gen2spring.mcp.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.core.DeterministicZipPackager;
import io.gen2spring.mcp.core.GenerationManifestWriter;
import io.gen2spring.mcp.core.GenerationPipeline;
import io.gen2spring.mcp.core.ProjectGeneratorRegistry;
import io.gen2spring.mcp.core.SafeProjectWriter;
import io.gen2spring.mcp.core.SourceTreeChecksum;
import io.gen2spring.mcp.core.ValidationReportWriter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.policy.ToolModelFactory;
import io.gen2spring.mcp.springai2.SpringAi2ProjectGenerator;
import io.gen2spring.mcp.validation.GradleMcpProjectValidator;
import java.util.Map;

public final class ApplicationFactory {
    private ApplicationFactory() {}

    public static CliApplication create() {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        ObjectMapper json = new ObjectMapper();
        var analyzer = new SwaggerOpenApiAnalyzer();
        var projectGenerators = ProjectGeneratorRegistry.of(Map.of(
                "generator-spring-ai-2", new SpringAi2ProjectGenerator()));
        var pipeline = new GenerationPipeline(
                analyzer,
                new ToolModelFactory(),
                profiles,
                projectGenerators,
                new SafeProjectWriter(),
                new SourceTreeChecksum(),
                new GenerationManifestWriter(json),
                new GradleMcpProjectValidator(),
                new ValidationReportWriter(json),
                new DeterministicZipPackager());
        return new CliApplication(
                new CommandLine(),
                new GenerationConfigurationReader(profiles),
                analyzer,
                pipeline::generate,
                profiles,
                json);
    }
}

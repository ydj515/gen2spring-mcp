package io.gen2spring.mcp.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationParser;
import io.gen2spring.mcp.adapter.filesystem.DeterministicZipPackager;
import io.gen2spring.mcp.adapter.filesystem.GenerationManifestWriter;
import io.gen2spring.mcp.application.usecase.GenerationPipeline;
import io.gen2spring.mcp.application.planning.GenerationPlanner;
import io.gen2spring.mcp.application.planning.ProjectGeneratorRegistry;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.application.runtime.metadata.RuntimeMetadataDocumentFactory;
import io.gen2spring.mcp.adapter.filesystem.SafeProjectWriter;
import io.gen2spring.mcp.adapter.filesystem.SourceTreeChecksum;
import io.gen2spring.mcp.adapter.filesystem.ValidationReportWriter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;
import io.gen2spring.mcp.adapter.openapi.swagger.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
import io.gen2spring.mcp.adapter.emitter.springai1.SpringAi1ProjectGenerator;
import io.gen2spring.mcp.adapter.emitter.springai2.SpringAi2ProjectGenerator;
import io.gen2spring.mcp.adapter.validation.GradleMcpProjectValidator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GeneratorRuntime(
        CompatibilityProfileRegistry profiles,
        SpecificationAnalyzer analyzer,
        GenerationConfigurationParser configurationParser,
        ProjectGeneratorRegistry projectGenerators,
        GenerationPlanner planner,
        GenerationPipeline pipeline) {

    public GeneratorRuntime {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(configurationParser, "configurationParser");
        Objects.requireNonNull(projectGenerators, "projectGenerators");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(pipeline, "pipeline");
    }

    public static GeneratorRuntime defaults() {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        SpecificationAnalyzer analyzer = new SwaggerOpenApiAnalyzer();
        ProjectGeneratorRegistry projectGenerators = ProjectGeneratorRegistry.of(Map.of(
                "generator-spring-ai-1", new SpringAi1ProjectGenerator(),
                "generator-spring-ai-2", new SpringAi2ProjectGenerator()));
        ObjectMapper json = new ObjectMapper();
        GenerationPlanner planner = new GenerationPlanner(new ToolModelFactory(), profiles, projectGenerators);
        GenerationPipeline pipeline = new GenerationPipeline(
                analyzer,
                planner,
                new SafeProjectWriter(),
                new SourceTreeChecksum(),
                new GenerationManifestWriter(json),
                new GradleMcpProjectValidator(),
                new ValidationReportWriter(json),
                new DeterministicZipPackager(),
                new RuntimeMetadataDocumentFactory(),
                new CanonicalRuntimeMetadataCodec());
        return new GeneratorRuntime(
                profiles,
                analyzer,
                new GenerationConfigurationParser(profiles),
                projectGenerators,
                planner,
                pipeline);
    }

    public List<String> generatorModules() {
        return profiles.profiles().stream()
                .map(profile -> profile.generatorModule())
                .distinct()
                .sorted()
                .toList();
    }
}

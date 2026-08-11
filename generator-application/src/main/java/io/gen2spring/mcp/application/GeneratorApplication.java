package io.gen2spring.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.core.DeterministicZipPackager;
import io.gen2spring.mcp.core.GenerationManifestWriter;
import io.gen2spring.mcp.core.GenerationPipeline;
import io.gen2spring.mcp.core.ProjectGeneratorRegistry;
import io.gen2spring.mcp.core.SafeProjectWriter;
import io.gen2spring.mcp.core.SourceTreeChecksum;
import io.gen2spring.mcp.core.ValidationReportWriter;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.openapi.SpecificationAnalyzer;
import io.gen2spring.mcp.openapi.SwaggerOpenApiAnalyzer;
import io.gen2spring.mcp.policy.ToolModelFactory;
import io.gen2spring.mcp.springai1.SpringAi1ProjectGenerator;
import io.gen2spring.mcp.springai2.SpringAi2ProjectGenerator;
import io.gen2spring.mcp.validation.GradleMcpProjectValidator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GeneratorApplication(
        CompatibilityProfileRegistry profiles,
        SpecificationAnalyzer analyzer,
        GenerationConfigurationParser configurationParser,
        ProjectGeneratorRegistry projectGenerators,
        GenerationPipeline pipeline) {

    public GeneratorApplication {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(configurationParser, "configurationParser");
        Objects.requireNonNull(projectGenerators, "projectGenerators");
        Objects.requireNonNull(pipeline, "pipeline");
    }

    public static GeneratorApplication defaults() {
        CompatibilityProfileRegistry profiles = CompatibilityProfileRegistry.defaults();
        SpecificationAnalyzer analyzer = new SwaggerOpenApiAnalyzer();
        ProjectGeneratorRegistry projectGenerators = ProjectGeneratorRegistry.of(Map.of(
                "generator-spring-ai-1", new SpringAi1ProjectGenerator(),
                "generator-spring-ai-2", new SpringAi2ProjectGenerator()));
        ObjectMapper json = new ObjectMapper();
        GenerationPipeline pipeline = new GenerationPipeline(
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
        return new GeneratorApplication(
                profiles,
                analyzer,
                new GenerationConfigurationParser(profiles),
                projectGenerators,
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

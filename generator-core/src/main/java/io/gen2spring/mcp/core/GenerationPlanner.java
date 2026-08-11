package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_COMBINATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.TARGET_PROFILE_NOT_FOUND;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.generation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedToolCall;
import io.gen2spring.mcp.domain.generation.GenerationContracts.ProjectGenerator;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import io.gen2spring.mcp.policy.ToolModelFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

public final class GenerationPlanner {
    private final ToolModelFactory toolModelFactory;
    private final CompatibilityProfileRegistry profiles;
    private final ProjectGeneratorRegistry projectGenerators;
    private final ExpectedToolSchemaFactory expectedToolSchemaFactory = new ExpectedToolSchemaFactory();
    private final ExpectedToolCallFactory expectedToolCallFactory = new ExpectedToolCallFactory();

    public GenerationPlanner(
            ToolModelFactory toolModelFactory,
            CompatibilityProfileRegistry profiles,
            ProjectGeneratorRegistry projectGenerators) {
        this.toolModelFactory = Objects.requireNonNull(toolModelFactory, "toolModelFactory");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.projectGenerators = Objects.requireNonNull(projectGenerators, "projectGenerators");
    }

    public ResolvedTarget resolve(GenerationRequest request) {
        if (request == null) {
            throw GeneratorException.user(
                    TARGET_PROFILE_NOT_FOUND,
                    "TARGET_VALIDATE",
                    "The requested compatibility profile is unavailable");
        }
        CompatibilityProfile profile = profiles.find(request.targetProfileId()).orElseThrow(() ->
                GeneratorException.user(
                        TARGET_PROFILE_NOT_FOUND,
                        "TARGET_VALIDATE",
                        "The requested compatibility profile is unavailable"));
        ProjectGenerator projectGenerator = projectGenerators.require(profile);
        if (request.project() == null || request.validationLevel() == null) {
            throw GeneratorException.user(
                    TARGET_COMBINATION_UNSUPPORTED,
                    "TARGET_VALIDATE",
                    "Generation target configuration is incomplete");
        }
        return new ResolvedTarget(profile, projectGenerator);
    }

    public PlannedGeneration plan(OpenApiDocument document, GenerationRequest request) {
        return plan(document, request, resolve(request));
    }

    public PlannedGeneration plan(
            OpenApiDocument document,
            GenerationRequest request,
            ResolvedTarget target) {
        Objects.requireNonNull(target, "target");
        List<McpToolDefinition> tools = List.copyOf(toolModelFactory.create(document, request));
        Map<String, ExpectedTool> expectedTools = expectedToolSchemaFactory.create(tools);
        ExpectedToolCall expectedToolCall = expectedToolCallFactory.create(tools, request.validation());
        return new PlannedGeneration(
                target.profile(),
                target.projectGenerator(),
                tools,
                expectedTools,
                expectedToolCall,
                secretEnvironmentVariables(tools));
    }

    private List<String> secretEnvironmentVariables(List<McpToolDefinition> tools) {
        TreeSet<String> names = new TreeSet<>();
        tools.forEach(tool -> tool.secretBindings().forEach(binding -> {
            if (binding.environmentVariable() != null && !binding.environmentVariable().isBlank()) {
                names.add(binding.environmentVariable());
            }
        }));
        return List.copyOf(names);
    }

    public record ResolvedTarget(
            CompatibilityProfile profile,
            ProjectGenerator projectGenerator) {
        public ResolvedTarget {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(projectGenerator, "projectGenerator");
        }
    }

    public record PlannedGeneration(
            CompatibilityProfile profile,
            ProjectGenerator projectGenerator,
            List<McpToolDefinition> tools,
            Map<String, ExpectedTool> expectedTools,
            ExpectedToolCall expectedToolCall,
            List<String> secretEnvironmentVariables) {
        public PlannedGeneration {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(projectGenerator, "projectGenerator");
            tools = List.copyOf(tools);
            expectedTools = Collections.unmodifiableMap(new LinkedHashMap<>(expectedTools));
            Objects.requireNonNull(expectedToolCall, "expectedToolCall");
            secretEnvironmentVariables = List.copyOf(secretEnvironmentVariables);
        }
    }
}

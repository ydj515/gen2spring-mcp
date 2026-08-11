package io.gen2spring.mcp.core;

import io.gen2spring.mcp.domain.generation.GenerationContracts.ExpectedTool;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.AnalysisWarning;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record GenerationPreview(
        CompatibilityProfile profile,
        List<Tool> tools,
        List<String> secretEnvironmentVariables,
        List<AnalysisWarning> warnings,
        List<String> generatedFilePaths) {

    public GenerationPreview {
        Objects.requireNonNull(profile, "profile");
        tools = List.copyOf(tools);
        secretEnvironmentVariables = List.copyOf(secretEnvironmentVariables);
        warnings = List.copyOf(warnings);
        generatedFilePaths = List.copyOf(generatedFilePaths);
    }

    public record Tool(
            String operationId,
            String name,
            String description,
            Map<String, Object> inputSchema,
            ResponseNormalization responseNormalization) {
        public Tool {
            if (operationId == null || operationId.isBlank()
                    || name == null || name.isBlank()
                    || description == null || description.isBlank()) {
                throw new IllegalArgumentException("Generation preview Tool is invalid");
            }
            inputSchema = new ExpectedTool(description, inputSchema).inputSchema();
        }
    }

    public record ResponseNormalization(
            String dataPointer,
            String successCodePointer,
            List<Object> successValues,
            String errorMessagePointer,
            String totalCountPointer) {
        public ResponseNormalization {
            successValues = List.copyOf(successValues);
        }

        public static ResponseNormalization from(ResponseNormalizationPolicy policy) {
            if (policy == null) {
                return null;
            }
            return new ResponseNormalization(
                    policy.dataPointer(),
                    policy.successCodePointer(),
                    policy.successValues(),
                    policy.errorMessagePointer(),
                    policy.totalCountPointer());
        }
    }
}

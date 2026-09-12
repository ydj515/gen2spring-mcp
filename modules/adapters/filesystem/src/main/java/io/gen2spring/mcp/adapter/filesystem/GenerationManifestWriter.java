package io.gen2spring.mcp.adapter.filesystem;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.port.outbound.ManifestWriter;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GenerationManifestWriter implements ManifestWriter {
    public static final String MANIFEST_FILE = "GENERATION_MANIFEST.json";
    private static final String STAGE = "REPORT";
    private static final String GENERATOR_VERSION = "0.1.0";

    private final ObjectMapper objectMapper;

    public GenerationManifestWriter() {
        this(new ObjectMapper());
    }

    public GenerationManifestWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Path write(
            Path projectRoot,
            CompatibilityProfile profile,
            OpenApiDocument document,
            String sourceChecksum,
            List<ToolDefinition> tools) {
        return write(projectRoot, profile, document, sourceChecksum, tools, McpImplementation.SPRING_AI_EXPLICIT);
    }

    @Override
    public Path write(Path projectRoot, CompatibilityProfile profile, OpenApiDocument document,
                      String sourceChecksum, List<ToolDefinition> tools, McpImplementation implementation) {
        requireInputs(profile, document, sourceChecksum, tools);
        Path target = outputPath(projectRoot);
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("generatorVersion", GENERATOR_VERSION);
        manifest.put("templateVersion", profile.templateVersion());
        manifest.put("runtimeVersion", profile.runtimeVersion());
        manifest.put("targetProfileId", profile.id());
        manifest.put("springBootVersion", profile.target().springBootVersion());
        if (implementation != McpImplementation.SPRING_AI_EXPLICIT) {
            manifest.put("mcpImplementation", implementation.name());
        }
        if (implementation == McpImplementation.MCP_JAVA_SDK) {
            manifest.put("mcpJavaSdkVersion", "0.18.3");
        } else {
            manifest.put("springAiVersion", profile.target().springAiVersion());
        }
        manifest.put("javaVersion", profile.target().javaVersion());
        ObjectNode buildTool = manifest.putObject("buildTool");
        buildTool.put("type", profile.target().buildTool());
        buildTool.put("distributionVersion", profile.buildToolchain().distributionVersion());
        buildTool.put("wrapperVersion", profile.buildToolchain().wrapperVersion());
        if (profile.gradleVersion() != null) {
            manifest.put("gradleVersion", profile.gradleVersion());
        }
        manifest.put("containerImage", profile.containerImage());
        manifest.put("originalSpecificationChecksum", document.checksum());
        manifest.put("sourceChecksum", sourceChecksum);
        ArrayNode mappings = manifest.putArray("operationMappings");
        tools.stream().sorted(Comparator.comparing(ToolDefinition::operationId)).forEach(tool -> {
            ObjectNode mapping = mappings.addObject();
            mapping.put("operationId", tool.operationId());
            mapping.put("toolName", tool.name());
            GenerationPreview.Output output = GenerationPreview.Output.from(tool);
            ObjectNode outputNode = mapping.putObject("output");
            outputNode.put("mode", output.mode());
            putOptional(outputNode, "schemaChecksum", output.schemaChecksum());
            GenerationPreview.Pagination pagination = GenerationPreview.Pagination.from(tool);
            if (pagination != null) {
                ObjectNode paginationNode = mapping.putObject("pagination");
                paginationNode.put("itemsPath", pagination.itemsPath());
                paginationNode.put("maxItems", pagination.maxItems());
                paginationNode.put("maxPages", pagination.maxPages());
                paginationNode.put("nextValuePath", pagination.nextValuePath());
                paginationNode.put("requestParameter", pagination.requestParameter());
            }
            GenerationPreview.Retry retry = GenerationPreview.Retry.from(tool);
            if (retry != null) {
                ObjectNode retryNode = mapping.putObject("retry");
                retryNode.put("initialBackoffMillis", retry.initialBackoffMillis());
                retryNode.put("maxBackoffMillis", retry.maxBackoffMillis());
                retryNode.put("maxRetries", retry.maxRetries());
                retryNode.put("networkErrors", retry.networkErrors());
                retryNode.put("respectRetryAfter", retry.respectRetryAfter());
                retryNode.set("statusCodes", objectMapper.valueToTree(retry.statusCodes()));
            }
            ResponseNormalizationPolicy policy = tool.execution().responseNormalization();
            if (policy != null) {
                ObjectNode normalization = mapping.putObject("responseNormalization");
                putOptional(normalization, "dataPath", policy.dataPointer());
                putOptional(normalization, "successCodePath", policy.successCodePointer());
                if (!policy.successValues().isEmpty()) {
                    normalization.set("successValues", objectMapper.valueToTree(policy.successValues()));
                }
                putOptional(normalization, "errorMessagePath", policy.errorMessagePointer());
                putOptional(normalization, "totalCountPath", policy.totalCountPointer());
            }
        });
        writeJson(target, manifest);
        return target;
    }

    private void requireInputs(
            CompatibilityProfile profile,
            OpenApiDocument document,
            String sourceChecksum,
            List<ToolDefinition> tools) {
        if (profile == null || document == null || sourceChecksum == null || sourceChecksum.isBlank() || tools == null) {
            throw failure("Generation manifest input is incomplete", null);
        }
    }

    private void putOptional(ObjectNode object, String field, String value) {
        if (value != null) {
            object.put(field, value);
        }
    }

    private Path outputPath(Path projectRoot) {
        if (projectRoot == null || Files.isSymbolicLink(projectRoot)
                || !Files.isDirectory(projectRoot, NOFOLLOW_LINKS)) {
            throw failure("Generation manifest root must be a regular directory", null);
        }
        return projectRoot.toAbsolutePath().normalize().resolve(MANIFEST_FILE);
    }

    private void writeJson(Path target, ObjectNode json) {
        try {
            byte[] bytes = (objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n").getBytes(UTF_8);
            Files.write(target, bytes, CREATE_NEW, WRITE, NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw failure("Generation manifest could not be written", exception);
        }
    }

    private GeneratorException failure(String message, Throwable cause) {
        return cause == null
                ? GeneratorException.user(SOURCE_GENERATION_FAILED, STAGE, message)
                : GeneratorException.system(SOURCE_GENERATION_FAILED, STAGE, message, cause);
    }
}

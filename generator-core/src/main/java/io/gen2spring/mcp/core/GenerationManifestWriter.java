package io.gen2spring.mcp.core;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SOURCE_GENERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.tool.McpToolDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class GenerationManifestWriter {
    public static final String MANIFEST_FILE = "GENERATION_MANIFEST.json";
    private static final String STAGE = "REPORT";
    private static final String GENERATOR_VERSION = "0.1.0";
    private static final String GRADLE_VERSION = "9.6.1";

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
            List<McpToolDefinition> tools) {
        requireInputs(profile, document, sourceChecksum, tools);
        Path target = outputPath(projectRoot);
        ObjectNode manifest = objectMapper.createObjectNode();
        manifest.put("generatorVersion", GENERATOR_VERSION);
        manifest.put("templateVersion", profile.templateVersion());
        manifest.put("runtimeVersion", profile.runtimeVersion());
        manifest.put("targetProfileId", profile.id());
        manifest.put("springBootVersion", profile.target().springBootVersion());
        manifest.put("springAiVersion", profile.target().springAiVersion());
        manifest.put("javaVersion", profile.target().javaVersion());
        manifest.put("gradleVersion", GRADLE_VERSION);
        manifest.put("originalSpecificationChecksum", document.checksum());
        manifest.put("sourceChecksum", sourceChecksum);
        ArrayNode mappings = manifest.putArray("operationMappings");
        tools.stream().sorted(Comparator.comparing(McpToolDefinition::operationId)).forEach(tool -> {
            ObjectNode mapping = mappings.addObject();
            mapping.put("operationId", tool.operationId());
            mapping.put("toolName", tool.name());
        });
        writeJson(target, manifest);
        return target;
    }

    private void requireInputs(
            CompatibilityProfile profile,
            OpenApiDocument document,
            String sourceChecksum,
            List<McpToolDefinition> tools) {
        if (profile == null || document == null || sourceChecksum == null || sourceChecksum.isBlank() || tools == null) {
            throw failure("Generation manifest input is incomplete", null);
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

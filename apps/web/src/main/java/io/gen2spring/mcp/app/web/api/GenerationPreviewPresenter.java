package io.gen2spring.mcp.app.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.Objects;

public final class GenerationPreviewPresenter {
    private final ObjectMapper json;

    public GenerationPreviewPresenter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public ObjectNode present(GenerationPreview preview) {
        Objects.requireNonNull(preview, "preview");
        ObjectNode root = json.createObjectNode();
        root.set("profile", profile(preview.profile()));
        ArrayNode tools = root.putArray("tools");
        preview.tools().forEach(tool -> tools.add(tool(tool)));
        ArrayNode environment = root.putArray("secretEnvironmentVariables");
        preview.secretEnvironmentVariables().forEach(environment::add);
        root.set("warnings", warnings(preview.warnings()));
        ArrayNode paths = root.putArray("generatedFilePaths");
        preview.generatedFilePaths().forEach(paths::add);
        return root;
    }

    ObjectNode profile(CompatibilityProfile profile) {
        ObjectNode node = json.createObjectNode();
        node.put("id", profile.id());
        node.put("javaVersion", profile.target().javaVersion());
        node.put("springBootVersion", profile.target().springBootVersion());
        node.put("springAiVersion", profile.target().springAiVersion());
        ObjectNode buildTool = node.putObject("buildTool");
        buildTool.put("type", profile.target().buildTool());
        buildTool.put("distributionVersion", profile.buildToolchain().distributionVersion());
        buildTool.put("wrapperVersion", profile.buildToolchain().wrapperVersion());
        node.put("webStack", profile.target().webStack());
        node.put("programmingModel", profile.target().programmingModel());
        node.put("transport", profile.target().transport());
        node.put("generatorModule", profile.generatorModule());
        node.put("templateVersion", profile.templateVersion());
        node.put("runtimeVersion", profile.runtimeVersion());
        if (profile.gradleVersion() != null) {
            node.put("gradleVersion", profile.gradleVersion());
        }
        node.put("containerImage", profile.containerImage());
        return node;
    }

    private ObjectNode tool(GenerationPreview.Tool tool) {
        ObjectNode node = json.createObjectNode();
        node.put("operationId", tool.operationId());
        node.put("name", tool.name());
        node.put("description", tool.description());
        node.set("inputSchema", json.valueToTree(tool.inputSchema()));
        ObjectNode output = node.putObject("output");
        output.put("mode", tool.output().mode());
        putOptional(output, "schemaChecksum", tool.output().schemaChecksum());
        if (tool.retry() != null) {
            ObjectNode value = node.putObject("retry");
            value.set("statusCodes", json.valueToTree(tool.retry().statusCodes()));
            value.put("networkErrors", tool.retry().networkErrors());
            value.put("maxRetries", tool.retry().maxRetries());
            value.put("initialBackoffMillis", tool.retry().initialBackoffMillis());
            value.put("maxBackoffMillis", tool.retry().maxBackoffMillis());
            value.put("respectRetryAfter", tool.retry().respectRetryAfter());
        }
        if (tool.pagination() != null) {
            ObjectNode value = node.putObject("pagination");
            value.put("requestParameter", tool.pagination().requestParameter());
            value.put("itemsPath", tool.pagination().itemsPath());
            value.put("nextValuePath", tool.pagination().nextValuePath());
            value.put("maxPages", tool.pagination().maxPages());
            value.put("maxItems", tool.pagination().maxItems());
        }
        if (tool.responseNormalization() != null) {
            ObjectNode value = node.putObject("responseNormalization");
            putNullable(value, "dataPointer", tool.responseNormalization().dataPointer());
            putNullable(value, "successCodePointer", tool.responseNormalization().successCodePointer());
            value.set("successValues", json.valueToTree(tool.responseNormalization().successValues()));
            putNullable(value, "errorMessagePointer", tool.responseNormalization().errorMessagePointer());
            putNullable(value, "totalCountPointer", tool.responseNormalization().totalCountPointer());
        }
        return node;
    }

    private ArrayNode warnings(
            java.util.List<io.gen2spring.mcp.domain.specification.OpenApiDocument.AnalysisWarning> warnings) {
        ArrayNode values = json.createArrayNode();
        warnings.forEach(warning -> {
            ObjectNode node = values.addObject();
            node.put("code", warning.code());
            node.put("message", warning.message());
            putNullable(node, "operationId", warning.operationId());
        });
        return values;
    }

    private void putOptional(ObjectNode node, String name, String value) {
        if (value != null) node.put(name, value);
    }

    private void putNullable(ObjectNode node, String name, String value) {
        if (value == null) node.putNull(name);
        else node.put(name, value);
    }
}

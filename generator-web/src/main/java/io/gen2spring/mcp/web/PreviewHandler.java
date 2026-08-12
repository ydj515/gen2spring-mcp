package io.gen2spring.mcp.web;

import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.GeneratorApplication;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.io.InputStream;
import java.util.Objects;

final class PreviewHandler {
    private final GeneratorApplication application;
    private final SpecificationStore specifications;
    private final ObjectMapper json;
    private final BoundedBodyReader configurationReader;

    PreviewHandler(
            GeneratorApplication application,
            SpecificationStore specifications,
            ObjectMapper json) {
        this.application = Objects.requireNonNull(application, "application");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
        this.json = Objects.requireNonNull(json, "json");
        this.configurationReader = new BoundedBodyReader(
                io.gen2spring.mcp.adapter.configuration.GenerationConfigurationParser.MAX_BYTES);
    }

    ObjectNode profiles() {
        ObjectNode root = json.createObjectNode();
        ArrayNode profiles = root.putArray("profiles");
        application.profiles().profiles().forEach(profile -> profiles.add(profile(profile)));
        return root;
    }

    ObjectNode upload(String specificationName, InputStream body) {
        SpecificationStore.StoredSpecification stored = specifications.store(specificationName, body);
        OpenApiDocument document = stored.analysis().document();
        ObjectNode root = json.createObjectNode();
        root.put("id", stored.id());
        root.put("checksum", document.checksum());
        root.put("openApiVersion", document.openApiVersion());
        root.put("sourceExtension", document.sourceExtension());
        if (document.baseUrl() == null) {
            root.putNull("baseUrl");
        } else {
            root.put("baseUrl", document.baseUrl().toString());
        }
        root.put("operationCount", document.operations().size());
        root.put("warningCount", document.warnings().size());
        ArrayNode operations = root.putArray("operations");
        document.operations().forEach(operation -> operations.add(operation(operation)));
        root.set("warnings", warnings(document.warnings()));
        return root;
    }

    ObjectNode preview(String specificationId, InputStream body) {
        SpecificationStore.StoredSpecification stored = specifications.retain(specificationId);
        GenerationPreview preview;
        try {
            byte[] configuration = configurationReader.read(body);
            preview = application.pipeline().preview(
                    stored.path(), application.configurationParser().parseJson(configuration));
        } finally {
            specifications.release(specificationId);
        }

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

    private ObjectNode profile(CompatibilityProfile profile) {
        ObjectNode node = json.createObjectNode();
        node.put("id", profile.id());
        node.put("javaVersion", profile.target().javaVersion());
        node.put("springBootVersion", profile.target().springBootVersion());
        node.put("springAiVersion", profile.target().springAiVersion());
        node.put("buildTool", profile.target().buildTool());
        node.put("webStack", profile.target().webStack());
        node.put("programmingModel", profile.target().programmingModel());
        node.put("transport", profile.target().transport());
        node.put("generatorModule", profile.generatorModule());
        node.put("templateVersion", profile.templateVersion());
        node.put("runtimeVersion", profile.runtimeVersion());
        node.put("gradleVersion", profile.gradleVersion());
        node.put("containerImage", profile.containerImage());
        return node;
    }

    private ObjectNode operation(OpenApiDocument.ApiOperation operation) {
        ObjectNode node = json.createObjectNode();
        node.put("operationId", operation.operationId());
        node.put("method", operation.method().name());
        node.put("path", operation.path());
        putNullable(node, "summary", operation.summary());
        putNullable(node, "description", operation.description());
        node.put("supported", operation.supported());
        ArrayNode warnings = node.putArray("warnings");
        operation.warnings().forEach(warnings::add);
        ArrayNode parameters = node.putArray("parameters");
        operation.parameters().forEach(parameter -> {
            ObjectNode value = parameters.addObject();
            value.put("name", parameter.name());
            value.put("location", parameter.location().name());
            value.put("required", parameter.required());
            putNullable(value, "description", parameter.description());
            value.put("type", parameter.schema().type().name());
            putNullable(value, "format", parameter.schema().format());
        });
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
        GenerationPreview.Retry retry = tool.retry();
        if (retry != null) {
            ObjectNode value = node.putObject("retry");
            value.set("statusCodes", json.valueToTree(retry.statusCodes()));
            value.put("networkErrors", retry.networkErrors());
            value.put("maxRetries", retry.maxRetries());
            value.put("initialBackoffMillis", retry.initialBackoffMillis());
            value.put("maxBackoffMillis", retry.maxBackoffMillis());
            value.put("respectRetryAfter", retry.respectRetryAfter());
        }
        GenerationPreview.Pagination pagination = tool.pagination();
        if (pagination != null) {
            ObjectNode value = node.putObject("pagination");
            value.put("requestParameter", pagination.requestParameter());
            value.put("itemsPath", pagination.itemsPath());
            value.put("nextValuePath", pagination.nextValuePath());
            value.put("maxPages", pagination.maxPages());
            value.put("maxItems", pagination.maxItems());
        }
        GenerationPreview.ResponseNormalization normalization = tool.responseNormalization();
        if (normalization != null) {
            ObjectNode value = node.putObject("responseNormalization");
            putNullable(value, "dataPointer", normalization.dataPointer());
            putNullable(value, "successCodePointer", normalization.successCodePointer());
            value.set("successValues", json.valueToTree(normalization.successValues()));
            putNullable(value, "errorMessagePointer", normalization.errorMessagePointer());
            putNullable(value, "totalCountPointer", normalization.totalCountPointer());
        }
        return node;
    }

    private void putOptional(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private ArrayNode warnings(java.util.List<OpenApiDocument.AnalysisWarning> warnings) {
        ArrayNode values = json.createArrayNode();
        warnings.forEach(warning -> {
            ObjectNode node = values.addObject();
            node.put("code", warning.code());
            node.put("message", warning.message());
            putNullable(node, "operationId", warning.operationId());
        });
        return values;
    }

    private void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }
}

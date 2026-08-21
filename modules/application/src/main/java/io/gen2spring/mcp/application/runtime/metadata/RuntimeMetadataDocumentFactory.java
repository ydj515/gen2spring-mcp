package io.gen2spring.mcp.application.runtime.metadata;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.RUNTIME_METADATA_INVALID;

import io.gen2spring.mcp.application.toolmodel.schema.ToolJsonSchemaFactory;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeHttp;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.tool.HttpExecution;
import io.gen2spring.mcp.domain.tool.SecretBinding;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RuntimeMetadataDocumentFactory {
    private static final String STAGE = "RUNTIME_METADATA";
    private static final String SAFE_MESSAGE = "Runtime metadata could not be generated";
    private final ToolJsonSchemaFactory schemas;

    public RuntimeMetadataDocumentFactory() {
        this(new ToolJsonSchemaFactory());
    }

    RuntimeMetadataDocumentFactory(ToolJsonSchemaFactory schemas) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    public RuntimeMetadataDocument create(String specificationChecksum, List<ToolDefinition> tools) {
        try {
            List<RuntimeTool> projected = (tools == null ? List.<ToolDefinition>of() : tools).stream()
                    .map(this::tool)
                    .toList();
            return new RuntimeMetadataDocument(
                    RuntimeMetadataDocument.VERSION, specificationChecksum, projected);
        } catch (GeneratorException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw GeneratorException.user(RUNTIME_METADATA_INVALID, STAGE, SAFE_MESSAGE, failure);
        }
    }

    private RuntimeTool tool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool");
        HttpExecution execution = Objects.requireNonNull(tool.execution(), "execution");
        return new RuntimeTool(
                tool.operationId(),
                tool.name(),
                tool.description(),
                schemas.inputSchema(tool.inputs()),
                tool.outputKind().name(),
                schemas.outputSchema(tool.output()),
                new RuntimeHttp(
                        execution.method(),
                        execution.baseUrl() == null
                                ? "/"
                                : execution.baseUrl().normalize().toASCIIString(),
                        execution.path(),
                        execution.bindings(),
                        execution.objectRequestBody(),
                        execution.requestBodyRequired()),
                execution.responseNormalization(),
                execution.retryPolicy(),
                execution.paginationPolicy(),
                credentials(tool.secretBindings()));
    }

    private List<RuntimeCredential> credentials(List<SecretBinding> bindings) {
        return (bindings == null ? List.<SecretBinding>of() : bindings).stream()
                .map(binding -> new RuntimeCredential(
                        binding.propertyName().toLowerCase(Locale.ROOT),
                        binding.targetLocation(),
                        binding.targetName(),
                        binding.required()))
                .toList();
    }
}

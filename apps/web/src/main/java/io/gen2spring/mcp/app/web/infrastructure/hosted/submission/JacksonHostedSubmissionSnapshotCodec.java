package io.gen2spring.mcp.app.web.infrastructure.hosted.submission;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.app.web.application.hosted.port.out.HostedSubmissionSnapshotCodec;
import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.util.Optional;
import java.util.UUID;

public final class JacksonHostedSubmissionSnapshotCodec implements HostedSubmissionSnapshotCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Override
    public String importSnapshot(EncryptedImportTarget target) throws Exception {
        return JSON.writeValueAsString(target);
    }

    @Override
    public String generationSnapshot(ObjectKey specificationKey, Optional<UUID> predecessorCatalogId,
            byte[] configurationBytes) throws Exception {
        JsonNode configuration = JSON.readTree(configurationBytes);
        if (configuration == null || !configuration.isObject()) {
            throw new IllegalArgumentException("Generation configuration is invalid");
        }
        var root = JSON.createObjectNode();
        root.put("specificationObjectKey", specificationKey.value());
        predecessorCatalogId.ifPresent(value -> root.put("predecessorCatalogId", value.toString()));
        root.set("configuration", configuration);
        return JSON.writeValueAsString(root);
    }
}

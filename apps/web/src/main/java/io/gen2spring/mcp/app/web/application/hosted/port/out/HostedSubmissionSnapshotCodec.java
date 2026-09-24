package io.gen2spring.mcp.app.web.application.hosted.port.out;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import java.util.Optional;
import java.util.UUID;

public interface HostedSubmissionSnapshotCodec {
    String importSnapshot(EncryptedImportTarget target) throws Exception;

    String generationSnapshot(ObjectKey specificationKey, Optional<UUID> predecessorCatalogId,
            byte[] configurationBytes) throws Exception;
}

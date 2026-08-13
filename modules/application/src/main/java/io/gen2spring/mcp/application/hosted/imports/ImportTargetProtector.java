package io.gen2spring.mcp.application.hosted.imports;

import io.gen2spring.mcp.domain.platform.imports.ImportTarget;

public interface ImportTargetProtector {
    EncryptedImportTarget protect(ImportTarget target);

    ImportTarget reveal(EncryptedImportTarget encrypted);
}

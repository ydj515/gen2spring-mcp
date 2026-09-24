package io.gen2spring.mcp.application.hosted.imports.port.out;

import io.gen2spring.mcp.application.hosted.imports.EncryptedImportTarget;
import io.gen2spring.mcp.domain.platform.imports.ImportTarget;

public interface ImportTargetProtector {
    EncryptedImportTarget protect(ImportTarget target);

    ImportTarget reveal(EncryptedImportTarget encrypted);
}

package io.gen2spring.mcp.application.port.outbound;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import io.gen2spring.mcp.domain.profile.McpImplementation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.List;

public interface ManifestWriter {
    String MANIFEST_FILE = "GENERATION_MANIFEST.json";

    Path write(
            Path projectRoot,
            CompatibilityProfile profile,
            OpenApiDocument document,
            String sourceChecksum,
            List<ToolDefinition> tools);
    default Path write(Path projectRoot, CompatibilityProfile profile, OpenApiDocument document,
                       String sourceChecksum, List<ToolDefinition> tools, McpImplementation implementation) {
        return write(projectRoot, profile, document, sourceChecksum, tools);
    }

}

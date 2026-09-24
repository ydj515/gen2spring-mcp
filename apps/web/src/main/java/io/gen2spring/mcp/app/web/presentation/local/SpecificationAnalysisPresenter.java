package io.gen2spring.mcp.app.web.presentation.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import java.nio.file.Path;
import java.util.Objects;

public final class SpecificationAnalysisPresenter {
    private final ObjectMapper json;

    public SpecificationAnalysisPresenter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public ObjectNode present(
            String specificationId,
            String specificationName,
            long byteSize,
            SpecificationAnalysisView analysis) {
        if (specificationId == null || specificationName == null || byteSize < 0 || analysis == null) {
            throw new IllegalArgumentException("Specification analysis presentation is invalid");
        }
        ObjectNode root = json.valueToTree(analysis);
        root.put("id", specificationId);
        ObjectNode file = root.putObject("file");
        file.put("name", Path.of(specificationName).getFileName().toString());
        file.put("byteSize", byteSize);
        return root;
    }
}

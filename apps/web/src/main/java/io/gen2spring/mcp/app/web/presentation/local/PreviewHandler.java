package io.gen2spring.mcp.app.web.presentation.local;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.app.web.application.local.io.BoundedBodyReader;
import io.gen2spring.mcp.app.web.application.local.result.StoredSpecification;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.generation.result.GenerationPreview;
import java.io.InputStream;
import java.util.Objects;

public final class PreviewHandler {
    private final LocalGenerationService generation;
    private final ObjectMapper json;
    private final SpecificationAnalysisPresenter analysisPresenter;
    private final GenerationPreviewPresenter previewPresenter;
    private final BoundedBodyReader configurationReader;

    public PreviewHandler(
            LocalGenerationService generation,
            ObjectMapper json,
            int maximumConfigurationBytes) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.json = Objects.requireNonNull(json, "json");
        this.analysisPresenter = new SpecificationAnalysisPresenter(this.json);
        this.previewPresenter = new GenerationPreviewPresenter(this.json);
        this.configurationReader = new BoundedBodyReader(maximumConfigurationBytes);
    }

    ObjectNode upload(String specificationName, InputStream body) {
        StoredSpecification stored = generation.upload(specificationName, body);
        return analysisPresenter.present(
                stored.id(),
                stored.displayName(),
                stored.size(),
                SpecificationAnalysisView.from(stored.analysis().document()));
    }

    ObjectNode preview(String specificationId, InputStream body) {
        byte[] configuration = configurationReader.read(body);
        GenerationPreview preview = generation.preview(specificationId, configuration);

        return previewPresenter.present(preview);
    }
}

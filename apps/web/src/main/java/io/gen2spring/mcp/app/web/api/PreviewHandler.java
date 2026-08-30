package io.gen2spring.mcp.app.web.api;

import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationException;
import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import java.io.InputStream;
import java.util.Objects;

public final class PreviewHandler {
    private final GeneratorRuntime application;
    private final SpecificationStore specifications;
    private final ObjectMapper json;
    private final SpecificationAnalysisPresenter analysisPresenter;
    private final GenerationPreviewPresenter previewPresenter;
    private final BoundedBodyReader configurationReader;

    public PreviewHandler(
            GeneratorRuntime application,
            SpecificationStore specifications,
            ObjectMapper json) {
        this.application = Objects.requireNonNull(application, "application");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
        this.json = Objects.requireNonNull(json, "json");
        this.analysisPresenter = new SpecificationAnalysisPresenter(this.json);
        this.previewPresenter = new GenerationPreviewPresenter(this.json);
        this.configurationReader = new BoundedBodyReader(
                GenerationConfigurationParser.MAX_BYTES);
    }

    ObjectNode upload(String specificationName, InputStream body) {
        SpecificationStore.StoredSpecification stored = specifications.store(specificationName, body);
        return analysisPresenter.present(
                stored.id(),
                stored.displayName(),
                stored.size(),
                SpecificationAnalysisView.from(stored.analysis().document()));
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

        return previewPresenter.present(preview);
    }
}

package io.gen2spring.mcp.app.web.application.hosted.port.out;

import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.generation.result.GenerationPreview;

public interface HostedSpecificationProcessor {
    SpecificationAnalysisView analyze(byte[] source, String contentType);

    void validateConfiguration(byte[] configurationBytes);

    GenerationPreview preview(byte[] source, String contentType, byte[] configurationBytes);
}

package io.gen2spring.mcp.app.web.presentation.local;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.generation.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OperationSupport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecificationAnalysisPresenterTest {
    @Test
    void addsOnlyOpaqueIdentityAndBoundedFileMetadataToTheSharedView() {
        var view = SpecificationAnalysisView.from(new OpenApiDocument(
                "3.1.2", "checksum", "yaml", URI.create("https://example.test"),
                List.of(new ApiOperation(
                        "listWidgets", GET, "/widgets", "List widgets", null,
                        List.of(), null, false, List.of(), OperationSupport.fromIssues(List.of()), null)),
                Map.of(), List.of()));

        var presented = new SpecificationAnalysisPresenter(new ObjectMapper())
                .present("a".repeat(64), "private-value/swagger-3.1.yml", 12345L, view);

        assertEquals("a".repeat(64), presented.path("id").textValue());
        assertEquals("swagger-3.1.yml", presented.at("/file/name").textValue());
        assertEquals(12345L, presented.at("/file/byteSize").longValue());
        assertEquals("SUPPORTED", presented.at("/operations/0/status").textValue());
        assertFalse(presented.toString().contains("private-value"));
    }
}

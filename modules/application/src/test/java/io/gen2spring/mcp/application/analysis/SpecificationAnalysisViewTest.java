package io.gen2spring.mcp.application.analysis;

import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.GET;
import static io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod.HEAD;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.HTTP_METHOD_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.SUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.SUPPORTED_WITH_WARNING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.AnalysisWarning;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSecurityScheme;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.specification.OperationSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecificationAnalysisViewTest {
    @Test
    void derivesImmutableOrderedCountsAndTypedOperationDecisions() {
        List<ApiOperation> operations = new ArrayList<>(List.of(
                operation("stable", GET, OperationSupport.fromIssues(List.of())),
                operation("inferred", GET, OperationSupport.fromIssues(List.of(SUCCESS_MEDIA_TYPE_INFERRED))),
                operation("unsupported", HEAD, OperationSupport.fromIssues(List.of(HTTP_METHOD_UNSUPPORTED)))));
        Map<String, ApiSecurityScheme> security = new LinkedHashMap<>();
        security.put("zeta", new ApiSecurityScheme("zeta", "apiKey", ParameterLocation.HEADER, "X-Key"));
        security.put("alpha", new ApiSecurityScheme("alpha", "apiKey", ParameterLocation.QUERY, "apiKey"));
        List<AnalysisWarning> warnings = new ArrayList<>(List.of(new AnalysisWarning(
                SUCCESS_MEDIA_TYPE_INFERRED.name(), SUCCESS_MEDIA_TYPE_INFERRED.message(), "inferred")));
        OpenApiDocument document = new OpenApiDocument(
                "3.1.2", "checksum", "yaml", URI.create("https://example.test"),
                operations, security, warnings);

        SpecificationAnalysisView view = SpecificationAnalysisView.from(document);
        operations.clear();
        security.clear();
        warnings.clear();

        assertEquals(new SpecificationAnalysisView.Counts(3, 1, 1, 1), view.counts());
        assertEquals(List.of("stable", "inferred", "unsupported"), view.operations().stream()
                .map(SpecificationAnalysisView.Operation::operationId).toList());
        assertEquals(List.of("alpha", "zeta"), new ArrayList<>(view.securitySchemes().keySet()));
        assertEquals(SUPPORTED, view.operations().get(0).status());
        assertTrue(view.operations().get(0).supported());
        assertEquals(SUPPORTED, view.operations().get(0).support().status());
        assertEquals(List.of(), view.operations().get(0).parameters());
        assertEquals(List.of(), view.operations().get(0).securityRequirements());
        assertEquals(SUPPORTED_WITH_WARNING, view.operations().get(1).status());
        assertTrue(view.operations().get(1).supported());
        assertEquals(List.of(SUCCESS_MEDIA_TYPE_INFERRED.name()), view.operations().get(1).issues().stream()
                .map(issue -> issue.code().name()).toList());
        assertEquals(UNSUPPORTED, view.operations().get(2).status());
        assertFalse(view.operations().get(2).supported());
        assertEquals(List.of(HTTP_METHOD_UNSUPPORTED.message()), view.operations().get(2).warnings());
        assertThrows(UnsupportedOperationException.class, () -> view.operations().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.securitySchemes().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.warnings().clear());
    }

    @Test
    void rejectsAMissingDocumentWithOneFixedMessage() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> SpecificationAnalysisView.from(null));

        assertEquals("Specification analysis is invalid", failure.getMessage());
    }

    private ApiOperation operation(String operationId, OpenApiDocument.HttpMethod method, OperationSupport support) {
        return new ApiOperation(
                operationId, method, "/" + operationId, operationId, null,
                List.of(), null, false, List.of(), support, null);
    }
}

package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class OpenApiVersionPairAcceptanceTest {
    private static final long MAX_SPECIFICATION_BYTES = 10L * 1024L * 1024L;
    private final SwaggerOpenApiAnalyzer analyzer = new SwaggerOpenApiAnalyzer();

    @Test
    void normalizesTheSuppliedOpenApi30And31SpecificationsToTheSameDecisions() {
        OpenApiDocument openApi30 = analyze("swagger-3.0.yml");
        OpenApiDocument openApi31 = analyze("swagger-3.1.yml");

        assertEquals("3.0.4", openApi30.openApiVersion());
        assertEquals("3.1.2", openApi31.openApiVersion());
        assertEquals(26, openApi30.operations().size());
        assertEquals(26, openApi31.operations().size());
        assertEquals(26, openApi30.operations().stream().map(OpenApiDocument.ApiOperation::operationId)
                .filter(Objects::nonNull).distinct().count());
        assertEquals(26, openApi31.operations().stream().map(OpenApiDocument.ApiOperation::operationId)
                .filter(Objects::nonNull).distinct().count());
        assertEquals(operationIdentities(openApi30), operationIdentities(openApi31));
        assertEquals(openApi30.baseUrl(), openApi31.baseUrl());
        assertEquals(openApi30.securitySchemes(), openApi31.securitySchemes());
        assertEquals(openApi30.warnings(), openApi31.warnings());
        assertEquals(openApi30.operations(), openApi31.operations());
        assertEquals(expectedDecisionRows(), decisionRows(openApi30));
        assertEquals(expectedDecisionRows(), decisionRows(openApi31));
        assertEquals(15, inferredSuccessMediaCount(openApi30));
        assertEquals(inferredSuccessMediaCount(openApi30), inferredSuccessMediaCount(openApi31));
    }

    private OpenApiDocument analyze(String fileName) {
        String projectRoot = System.getProperty("gen2spring.projectRoot");
        assertNotNull(projectRoot, "The OpenAPI test task must provide gen2spring.projectRoot");
        Path root = Path.of(projectRoot);
        return analyzer.analyze(root.resolve(fileName), MAX_SPECIFICATION_BYTES).document();
    }

    private List<String> operationIdentities(OpenApiDocument document) {
        return document.operations().stream()
                .map(operation -> operation.method() + " " + operation.path() + " " + operation.operationId())
                .toList();
    }

    private long inferredSuccessMediaCount(OpenApiDocument document) {
        return document.operations().stream()
                .filter(operation -> operation.support().issueCodes().contains(SUCCESS_MEDIA_TYPE_INFERRED))
                .count();
    }

    private List<String> decisionRows(OpenApiDocument document) {
        return document.operations().stream()
                .map(operation -> operation.operationId() + " " + operation.support().status() + " "
                        + operation.support().issueCodes())
                .toList();
    }

    private List<String> expectedDecisionRows() {
        return """
                getManualReviews UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                reconcile UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                login UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                getCustomers SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getCustomer SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getOrders UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                createOrder UNSUPPORTED [SCHEMA_NULLABILITY_UNSUPPORTED, SCHEMA_NESTED_UNSUPPORTED, SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                getOrdersWithNestedSelect SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                countOrdersByStatus UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                getOrdersByStatus SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getOrder UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                cancelOrder UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                cancelOrderItems UNSUPPORTED [SCHEMA_NULLABILITY_UNSUPPORTED, SCHEMA_CONSTRAINT_UNSUPPORTED, SCHEMA_NESTED_UNSUPPORTED, SUCCESS_MEDIA_TYPE_INFERRED]
                addOrderItems SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                addOrderItemsWithBatchSession SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getOrderWithNestedSelect SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                payOrder UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED, SUCCESS_SCHEMA_UNSUPPORTED]
                shipOrder UNSUPPORTED [SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                getProducts SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getProduct SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getUsers SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                createUser UNSUPPORTED [SCHEMA_NULLABILITY_UNSUPPORTED, SCHEMA_NESTED_UNSUPPORTED, SUCCESS_MEDIA_TYPE_UNSUPPORTED]
                updateUser UNSUPPORTED [SCHEMA_NULLABILITY_UNSUPPORTED, SCHEMA_NESTED_UNSUPPORTED, SUCCESS_MEDIA_TYPE_INFERRED]
                getUserByUsername SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                getUser SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                deleteUser SUPPORTED_WITH_WARNING [SUCCESS_MEDIA_TYPE_INFERRED]
                """.lines().toList();
    }
}

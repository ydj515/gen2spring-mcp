package io.gen2spring.mcp.adapter.openapi.swagger;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.application.command.GenerationCommand.OperationSelection;
import io.gen2spring.mcp.application.toolmodel.ToolModelFactory;
import io.gen2spring.mcp.application.validation.ExpectedToolSchemaFactory;
import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OperationSupport.Status;
import io.gen2spring.mcp.domain.tool.ToolDefinition;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertEquals(38, openApi30.operations().size());
        assertEquals(38, openApi31.operations().size());
        assertEquals(38, openApi30.operations().stream().map(OpenApiDocument.ApiOperation::operationId)
                .filter(Objects::nonNull).distinct().count());
        assertEquals(38, openApi31.operations().stream().map(OpenApiDocument.ApiOperation::operationId)
                .filter(Objects::nonNull).distinct().count());
        assertEquals(operationIdentities(openApi30), operationIdentities(openApi31));
        assertEquals(openApi30.baseUrl(), openApi31.baseUrl());
        assertEquals(openApi30.securitySchemes(), openApi31.securitySchemes());
        assertEquals(openApi30.warnings(), openApi31.warnings());
        assertEquals(openApi30.operations(), openApi31.operations());
        assertEquals(expectedDecisionRows(), decisionRows(openApi30));
        assertEquals(expectedDecisionRows(), decisionRows(openApi31));
        assertEquals(34, operationCount(openApi30, Status.SUPPORTED));
        assertEquals(4, operationCount(openApi30, Status.UNSUPPORTED));
        Map<String, Map<String, Object>> schemas30 = toolSchemas(openApi30);
        Map<String, Map<String, Object>> schemas31 = toolSchemas(openApi31);
        assertEquals(schemas30, schemas31);
        assertCanonicalSchemaContracts(schemas30);
        assertEquals(0, inferredSuccessMediaCount(openApi30));
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

    private long operationCount(OpenApiDocument document, Status status) {
        return document.operations().stream().filter(operation -> operation.support().status() == status).count();
    }

    private Map<String, Map<String, Object>> toolSchemas(OpenApiDocument document) {
        List<String> operationIds = List.of(
                "listSchemaFixtures",
                "submitRequiredNullablePayload",
                "submitOptionalNullablePayload",
                "submitBoundedUniqueItems",
                "submitCompatibleAllOf",
                "submitOneOfValue",
                "submitAnyOfValue",
                "submitReferencedConstraint");
        List<OperationSelection> selections = operationIds.stream()
                .map(operationId -> new OperationSelection(operationId, true, null, null, Map.of()))
                .toList();
        var command = new GenerationCommand(
                new GenerationCommand.ProjectCoordinates(
                        "io.gen2spring.fixture", "schema-contracts", "io.gen2spring.fixture.schema"),
                "fixture", "schema", "spring-ai-2.0-java21-mvc-streamable",
                GenerationCommand.ValidationLevel.MCP_PROTOCOL,
                new GenerationCommand.ValidationConfiguration(
                        new GenerationCommand.ToolCallValidation("listSchemaFixtures", Map.of())),
                selections);
        List<ToolDefinition> tools = new ToolModelFactory().create(document, command);
        var expected = new ExpectedToolSchemaFactory().create(tools);
        Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        tools.forEach(tool -> schemas.put(tool.operationId(), expected.get(tool.name()).inputSchema()));
        return Map.copyOf(schemas);
    }

    private void assertCanonicalSchemaContracts(Map<String, Map<String, Object>> schemas) {
        Map<String, Object> filters = properties(schemas.get("listSchemaFixtures"));
        assertEquals(java.util.Set.of("cursor", "xSchemaRevision"), filters.keySet());
        assertTrue(map(filters.get("cursor")).containsKey("anyOf"));
        assertTrue(map(filters.get("xSchemaRevision")).containsKey("anyOf"));
        assertTrue(list(schemas.get("listSchemaFixtures").get("required")).isEmpty());

        Map<String, Object> requiredBody = schemas.get("submitRequiredNullablePayload");
        assertEquals(List.of("body"), list(requiredBody.get("required")));
        assertTrue(map(properties(requiredBody).get("body")).containsKey("anyOf"));
        Map<String, Object> optionalBody = schemas.get("submitOptionalNullablePayload");
        assertTrue(list(optionalBody.get("required")).isEmpty());
        assertTrue(map(properties(optionalBody).get("body")).containsKey("anyOf"));

        Map<String, Object> bounded = map(properties(schemas.get("submitBoundedUniqueItems")).get("body"));
        assertEquals(1, bounded.get("minItems"));
        assertEquals(2, bounded.get("maxItems"));
        assertEquals(true, bounded.get("uniqueItems"));

        Map<String, Object> compatible = map(properties(schemas.get("submitCompatibleAllOf")).get("id"));
        assertEquals(3, compatible.get("minLength"));
        assertEquals(32, compatible.get("maxLength"));
        assertTrue(map(properties(schemas.get("submitOneOfValue")).get("body")).containsKey("oneOf"));
        assertTrue(map(properties(schemas.get("submitAnyOfValue")).get("body")).containsKey("anyOf"));

        Map<String, Object> referenced = map(properties(schemas.get("submitReferencedConstraint")).get("body"));
        assertEquals("string", referenced.get("type"));
        assertEquals(8, referenced.get("minLength"));
        assertEquals(16, referenced.get("maxLength"));
        assertFalse(referenced.containsKey("allOf"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        return map(schema.get("properties"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private List<?> list(Object value) {
        return (List<?>) value;
    }

    private List<String> decisionRows(OpenApiDocument document) {
        return document.operations().stream()
                .map(operation -> operation.operationId() + " " + operation.support().status() + " "
                        + operation.support().issueCodes())
                .toList();
    }

    private List<String> expectedDecisionRows() {
        return """
                getManualReviews SUPPORTED []
                reconcile SUPPORTED []
                login SUPPORTED []
                getCustomers SUPPORTED []
                getCustomer SUPPORTED []
                getOrders SUPPORTED []
                createOrder SUPPORTED []
                getOrdersWithNestedSelect SUPPORTED []
                countOrdersByStatus SUPPORTED []
                getOrdersByStatus SUPPORTED []
                getOrder SUPPORTED []
                cancelOrder SUPPORTED []
                cancelOrderItems SUPPORTED []
                addOrderItems SUPPORTED []
                addOrderItemsWithBatchSession SUPPORTED []
                getOrderWithNestedSelect SUPPORTED []
                payOrder SUPPORTED []
                shipOrder SUPPORTED []
                getProducts SUPPORTED []
                getProduct SUPPORTED []
                getUsers SUPPORTED []
                createUser SUPPORTED []
                updateUser SUPPORTED []
                getUserByUsername SUPPORTED []
                getUser SUPPORTED []
                deleteUser SUPPORTED []
                submitAnyOfValue SUPPORTED []
                submitBoundedUniqueItems SUPPORTED []
                submitCompatibleAllOf SUPPORTED []
                submitCompositionBudgetOverflow UNSUPPORTED [SCHEMA_COMPOSITION_UNSUPPORTED]
                submitConflictingAllOf UNSUPPORTED [SCHEMA_CONSTRAINT_UNSUPPORTED]
                listSchemaFixtures SUPPORTED []
                getNullablePathFixture UNSUPPORTED [PARAMETER_NULLABLE_PATH_UNSUPPORTED]
                submitOneOfValue SUPPORTED []
                submitOptionalNullablePayload SUPPORTED []
                submitReferencedConstraint SUPPORTED []
                inspectRequiredNullableFilter UNSUPPORTED [PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED]
                submitRequiredNullablePayload SUPPORTED []
                """.lines().toList();
    }
}

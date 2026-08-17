package io.gen2spring.mcp.adapter.openapi.swagger;

import io.gen2spring.mcp.application.port.outbound.SpecificationAnalyzer;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_REFERENCE_UNRESOLVED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_VERSION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.HTTP_METHOD_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.OPERATION_ID_DUPLICATED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.OPERATION_ID_MISSING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_COMPOSITION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_CONSTRAINT_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NULLABILITY_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_MULTI_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_SCHEMA_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.SUPPORTED_WITH_WARNING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.SchemaType;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SwaggerOpenApiAnalyzerTest {
    private final SpecificationAnalyzer analyzer = new SwaggerOpenApiAnalyzer();

    @ParameterizedTest
    @ValueSource(strings = {"3.0.0", "3.0.999", "3.1.0", "3.1.2"})
    void acceptsOnlyTheApprovedNumericPatchVersionFamilies(String version) throws Exception {
        Path specification = Files.createTempFile("accepted-openapi-version", ".yaml");
        Files.writeString(specification, """
                openapi: %s
                info: { title: Version API, version: '1.0' }
                paths: {}
                """.formatted(version));

        assertEquals(version, analyzer.analyze(specification, 1024).document().openApiVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.0", "3.0", "3.0.3-beta", "3.1", "3.1.0-rc1", "3.2.0"})
    void rejectsEveryOtherVersionFormWithOneSafeFailure(String version) throws Exception {
        Path specification = Files.createTempFile("rejected-openapi-version", ".yaml");
        Files.writeString(specification, """
                openapi: %s
                info: { title: Version API, version: '1.0' }
                paths: {}
                """.formatted(version));

        GeneratorException failure = assertThrows(
                GeneratorException.class, () -> analyzer.analyze(specification, 1024));

        assertEquals(SPEC_VERSION_UNSUPPORTED, failure.code());
        assertEquals("The OpenAPI version or JSON Schema dialect is unsupported", failure.safeMessage());
        assertFalse(failure.safeMessage().contains(version));
    }

    @Test
    void acceptsOnlyTheOpenApi31BaseDialect() throws Exception {
        var supported = analyzer.analyze(resource("openapi/openapi-31-supported.yaml"), 10 * 1024 * 1024);
        Path unsupported = Files.createTempFile("unsupported-openapi-dialect", ".yaml");
        Files.writeString(unsupported, """
                openapi: 3.1.2
                jsonSchemaDialect: https://json-schema.org/draft/2020-12/schema
                info: { title: Dialect API, version: '1.0' }
                paths: {}
                """);

        GeneratorException failure = assertThrows(
                GeneratorException.class, () -> analyzer.analyze(unsupported, 1024));

        assertEquals("3.1.2", supported.document().openApiVersion());
        assertEquals(SPEC_VERSION_UNSUPPORTED, failure.code());
        assertEquals("The OpenAPI version or JSON Schema dialect is unsupported", failure.safeMessage());
        assertFalse(failure.safeMessage().contains("json-schema.org"));
    }

    @Test
    void rejectsSchemaLevelOpenApi31DialectOverridesWithOneSafeFailure() throws Exception {
        Path specification = Files.createTempFile("unsupported-schema-dialect", ".yaml");
        Files.writeString(specification, """
                openapi: 3.1.2
                info: { title: Dialect API, version: '1.0' }
                components:
                  schemas:
                    Widget:
                      $schema: https://json-schema.org/draft/2020-12/schema
                      type: string
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      responses:
                        '200':
                          description: Success
                          content:
                            application/json:
                              schema: { $ref: '#/components/schemas/Widget' }
                """);

        GeneratorException failure = assertThrows(
                GeneratorException.class, () -> analyzer.analyze(specification, 4096));

        assertEquals(SPEC_VERSION_UNSUPPORTED, failure.code());
        assertEquals("The OpenAPI version or JSON Schema dialect is unsupported", failure.safeMessage());
        assertFalse(failure.safeMessage().contains("json-schema.org"));
    }

    @Test
    void normalizesOpenApi31SingleNullUnionsAndRejectsOtherTypeSets() throws Exception {
        var supported = analyzer.analyze(
                resource("openapi/openapi-31-supported.yaml"), 10 * 1024 * 1024).document();
        var nullable = supported.operations().getFirst().successResponse().properties().get("label");
        var unsupported = analyzer.analyze(
                        resource("openapi/openapi-31-unsupported.yaml"), 10 * 1024 * 1024)
                .document().operations().stream()
                .collect(java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        assertEquals(SchemaType.STRING, nullable.type());
        assertTrue(nullable.nullable());
        assertTrue(supported.operations().getFirst().supported());
        assertTrue(unsupported.get("multiTypeInput").support().issueCodes()
                .contains(SCHEMA_MULTI_TYPE_UNSUPPORTED));
        assertTrue(unsupported.get("nullOnlyInput").support().issueCodes()
                .contains(SCHEMA_MULTI_TYPE_UNSUPPORTED));
        assertTrue(unsupported.get("conditionalResult").support().issueCodes()
                .contains(SCHEMA_CONSTRAINT_UNSUPPORTED),
                unsupported.get("conditionalResult").support().issueCodes().toString());
        assertTrue(unsupported.get("tupleResult").support().issueCodes()
                .contains(SCHEMA_CONSTRAINT_UNSUPPORTED),
                unsupported.get("tupleResult").support().issueCodes().toString());
    }

    @Test
    void keepsInputNullabilityFailClosedForOpenApi31() throws Exception {
        Path specification = Files.createTempFile("nullable-openapi31-input", ".yaml");
        Files.writeString(specification, """
                openapi: 3.1.2
                info: { title: Nullable Input API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      parameters:
                        - name: revision
                          in: query
                          schema: { type: [string, 'null'] }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.parameters().getFirst().schema().nullable());
        assertEquals(java.util.List.of(SCHEMA_NULLABILITY_UNSUPPORTED), operation.support().issueCodes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.4", "3.1.2"})
    void supportsNullableObjectBodyPropertiesAndMinItems(String version) throws Exception {
        String nullable = version.startsWith("3.0")
                ? "type: string, nullable: true"
                : "type: [string, 'null']";
        Path specification = Files.createTempFile("nullable-object-body", ".yaml");
        Files.writeString(specification, """
                openapi: %s
                info: { title: Nullable Body API, version: '1.0' }
                paths:
                  /widgets:
                    post:
                      operationId: createWidget
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [label, values]
                              properties:
                                label: { %s }
                                values:
                                  type: array
                                  minItems: 1
                                  items: { type: integer, format: int32 }
                      responses: { '204': { description: Accepted } }
                """.formatted(version, nullable));

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.support().issueCodes().toString());
        assertTrue(operation.requestBody().properties().get("label").nullable());
        assertEquals(1, operation.requestBody().properties().get("values").minItems());
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.0.4", "3.1.2"})
    void keepsNullableRootRequestBodiesFailClosed(String version) throws Exception {
        String nullable = version.startsWith("3.0")
                ? "type: string, nullable: true"
                : "type: [string, 'null']";
        Path specification = Files.createTempFile("nullable-root-body", ".yaml");
        Files.writeString(specification, """
                openapi: %s
                info: { title: Nullable Root Body API, version: '1.0' }
                paths:
                  /widgets:
                    post:
                      operationId: createWidget
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: { %s }
                      responses: { '204': { description: Accepted } }
                """.formatted(version, nullable));

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertEquals(java.util.List.of(SCHEMA_NULLABILITY_UNSUPPORTED), operation.support().issueCodes());
    }

    @Test
    void ignoresTheRemovedNullableKeywordForOpenApi31Schemas() throws Exception {
        Path specification = Files.createTempFile("openapi31-legacy-nullable", ".yaml");
        Files.writeString(specification, """
                openapi: 3.1.2
                info: { title: Nullable Extension API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      parameters:
                        - name: revision
                          in: query
                          schema: { type: string, nullable: true }
                      responses:
                        '200':
                          description: Success
                          content:
                            application/json:
                              schema: { type: string, nullable: true }
                """);

        var operation = analyzer.analyze(specification, 4096).document().operations().getFirst();

        assertTrue(operation.supported(), operation.support().issueCodes().toString());
        assertFalse(operation.parameters().getFirst().schema().nullable());
        assertFalse(operation.successResponse().nullable());
    }

    @Test
    void failsOpenApi31RefSiblingsClosedInsteadOfDroppingTheirConstraints() throws Exception {
        Path specification = Files.createTempFile("openapi31-ref-sibling", ".yaml");
        Files.writeString(specification, """
                openapi: 3.1.2
                info: { title: Ref Sibling API, version: '1.0' }
                components:
                  schemas:
                    WidgetId: { type: string }
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      parameters:
                        - name: widgetId
                          in: query
                          schema:
                            $ref: '#/components/schemas/WidgetId'
                            not: { const: forbidden }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 4096).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(SCHEMA_CONSTRAINT_UNSUPPORTED));
    }

    @Test
    void rejectsDuplicateKeysAndTrailingJsonTokens() throws Exception {
        Path duplicateYaml = Files.createTempFile("duplicate-openapi", ".yaml");
        Files.writeString(duplicateYaml, """
                openapi: 3.0.3
                openapi: 3.0.3
                info: { title: Weather, version: '1.0' }
                paths: {}
                """);
        Path duplicateJson = Files.createTempFile("duplicate-openapi", ".json");
        Files.writeString(duplicateJson,
                "{\"openapi\":\"3.0.3\",\"openapi\":\"3.0.3\",\"info\":{},\"paths\":{}}");
        Path trailingJson = Files.createTempFile("trailing-openapi", ".json");
        Files.writeString(trailingJson,
                "{\"openapi\":\"3.0.3\",\"info\":{},\"paths\":{}} {}");

        assertThrows(GeneratorException.class, () -> analyzer.analyze(duplicateYaml, 1024));
        assertThrows(GeneratorException.class, () -> analyzer.analyze(duplicateJson, 1024));
        assertThrows(GeneratorException.class, () -> analyzer.analyze(trailingJson, 1024));
    }

    @Test
    void normalizesStructurallyIdenticalJsonSuccessResponseSchemas() throws Exception {
        Path specification = Files.createTempFile("success-response", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Weather API, version: '1.0' }
                paths:
                  /weather:
                    get:
                      operationId: getWeather
                      responses:
                        '200':
                          description: Current weather
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [city]
                                properties:
                                  city: { type: string }
                        '201':
                          description: Cached weather
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [city]
                                properties:
                                  city: { type: string }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
        assertEquals(SchemaType.OBJECT, operation.successResponse().type());
        assertEquals(java.util.List.of("city"), operation.successResponse().requiredProperties());
        assertEquals(SchemaType.STRING, operation.successResponse().properties().get("city").type());
    }

    @Test
    void leavesMixedBodyAndBodylessSuccessResponsesUntyped() throws Exception {
        Path specification = Files.createTempFile("mixed-success-response", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Weather API, version: '1.0' }
                paths:
                  /weather:
                    get:
                      operationId: getWeather
                      responses:
                        '200':
                          description: Current weather
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [city]
                                properties:
                                  city: { type: string }
                        '204': { description: No content }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
        assertNull(operation.successResponse());
    }

    @Test
    void failsClosedForAmbiguousOrUnsupportedSuccessResponseSchemas() throws Exception {
        Path specification = Files.createTempFile("success-response-boundaries", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Result API, version: '1.0' }
                paths:
                  /bodyless:
                    get:
                      operationId: bodyless
                      responses: { '204': { description: Accepted } }
                  /range:
                    get:
                      operationId: range
                      responses:
                        2XX:
                          description: Result
                          content:
                            application/json:
                              schema: { type: string }
                  /conflicting:
                    get:
                      operationId: conflicting
                      responses:
                        '200':
                          description: Text
                          content:
                            application/json:
                              schema: { type: string }
                        '201':
                          description: Number
                          content:
                            application/json:
                              schema: { type: integer }
                  /missing-schema:
                    get:
                      operationId: missingSchema
                      responses:
                        '200':
                          description: Missing
                          content:
                            application/json: {}
                        '201':
                          description: Present
                          content:
                            application/json:
                              schema: { type: string }
                  /composed:
                    get:
                      operationId: composed
                      responses:
                        '200':
                          description: Composed
                          content:
                            application/json:
                              schema:
                                anyOf:
                                  - { type: string }
                                  - { type: integer }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().stream()
                .collect(java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        assertTrue(operations.get("bodyless").supported());
        assertNull(operations.get("bodyless").successResponse());
        assertTrue(operations.get("range").supported(), operations.get("range").warnings().toString());
        assertEquals(SchemaType.STRING, operations.get("range").successResponse().type());
        for (String operationId : java.util.List.of("conflicting", "missingSchema", "composed")) {
            assertFalse(operations.get(operationId).supported(), operationId);
            assertNull(operations.get(operationId).successResponse(), operationId);
            assertTrue(operations.get(operationId).support().issueCodes().contains(SUCCESS_SCHEMA_UNSUPPORTED),
                    operationId);
        }
    }

    @Test
    void rejectsExternalReferencesBeforeSwaggerParserCanResolveThem() throws Exception {
        var exception = assertThrows(GeneratorException.class,
                () -> analyzer.analyze(resource("openapi/external-reference.yaml"), 10 * 1024 * 1024));

        assertEquals(SPEC_REFERENCE_UNRESOLVED, exception.code());
    }

    @Test
    void normalizesARequiredQueryParameterAndApiKeyScheme() throws Exception {
        var document = analyzer.analyze(resource("openapi/simple-weather.yaml"), 10 * 1024 * 1024).document();

        var operation = document.operations().getFirst();
        assertEquals("getForecast", operation.operationId());
        assertEquals("GET", operation.method().name());
        assertEquals("nx", operation.parameters().getFirst().name());
        assertTrue(operation.parameters().getFirst().required());
        assertEquals("apiKey", document.securitySchemes().get("serviceKeyAuth").type());
        assertEquals("integer", operation.parameters().getFirst().schema().type().name().toLowerCase());
        assertEquals("int32", operation.parameters().getFirst().schema().format());
    }

    @Test
    void supportsStringEnumsAndRejectsEnumsWhoseP0RuntimeTypeWouldBeChangedToString() throws Exception {
        Path specification = Files.createTempFile("enum-types", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Enum API, version: '1.0' }
                paths:
                  /string:
                    get:
                      operationId: stringEnum
                      parameters:
                        - name: mode
                          in: query
                          schema: { type: string, enum: [brief, full] }
                      responses: { '204': { description: Accepted } }
                  /integer:
                    get:
                      operationId: integerEnum
                      parameters:
                        - name: level
                          in: query
                          schema: { type: integer, enum: [1, 2] }
                      responses: { '204': { description: Accepted } }
                  /boolean:
                    get:
                      operationId: booleanEnum
                      parameters:
                        - name: enabled
                          in: query
                          schema: { type: boolean, enum: [true, false] }
                      responses: { '204': { description: Accepted } }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().stream()
                .collect(java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        assertTrue(operations.get("stringEnum").supported());
        assertEquals(java.util.List.of("brief", "full"),
                operations.get("stringEnum").parameters().getFirst().schema().enumValues());
        assertFalse(operations.get("integerEnum").supported());
        assertFalse(operations.get("booleanEnum").supported());
        assertEquals(java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED),
                operations.get("integerEnum").support().issueCodes());
        assertEquals(java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED),
                operations.get("booleanEnum").support().issueCodes());
        assertEquals(java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED.message()),
                operations.get("integerEnum").parameters().getFirst().schema().warnings());
        assertEquals(java.util.List.of(SCHEMA_CONSTRAINT_UNSUPPORTED.message()),
                operations.get("booleanEnum").parameters().getFirst().schema().warnings());
    }

    @Test
    void marksEveryDuplicateOperationIdWithoutRejectingTheDocument() throws Exception {
        var document = analyzer.analyze(resource("openapi/duplicate-operation-id.yaml"), 10 * 1024 * 1024).document();

        assertEquals(2, document.operations().size());
        document.operations().forEach(operation -> {
            assertEquals(UNSUPPORTED, operation.support().status());
            assertEquals(java.util.List.of(OPERATION_ID_DUPLICATED), operation.support().issueCodes());
        });
    }

    @Test
    void preservesMissingIdsAndUnsupportedHttpMethodsAsVisibleOperations() throws Exception {
        Path specification = Files.createTempFile("visible-unsupported-operations", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Visible API, version: '1.0' }
                paths:
                  /missing:
                    get:
                      responses: { '204': { description: Accepted } }
                  /head:
                    head:
                      operationId: headResource
                      responses: { '204': { description: Accepted } }
                  /options:
                    options:
                      operationId: optionsResource
                      responses: { '204': { description: Accepted } }
                  /trace:
                    trace:
                      operationId: traceResource
                      responses: { '204': { description: Accepted } }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations();

        assertEquals(4, operations.size());
        var missing = operations.stream().filter(operation -> operation.operationId() == null).findFirst().orElseThrow();
        assertEquals(java.util.List.of(OPERATION_ID_MISSING), missing.support().issueCodes());
        operations.stream().filter(operation -> operation.operationId() != null).forEach(operation -> {
            assertEquals(UNSUPPORTED, operation.support().status());
            assertEquals(java.util.List.of(HTTP_METHOD_UNSUPPORTED), operation.support().issueCodes());
        });
    }

    @Test
    void marksOperationsWithComposedSchemasAsUnsupported() throws Exception {
        var document = analyzer.analyze(resource("openapi/unsupported-schema.yaml"), 10 * 1024 * 1024).document();

        var operation = document.operations().getFirst();
        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(SCHEMA_COMPOSITION_UNSUPPORTED));
        assertTrue(operation.support().issueCodes().contains(
                io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.RECURSIVE_SCHEMA_UNSUPPORTED));
    }

    @Test
    void rejectsFreeFormObjectRequestBodiesThatCannotBeRepresentedByP0Tools() throws Exception {
        Path specification = Files.createTempFile("additional-properties", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Map API, version: '1.0' }
                paths:
                  /labels:
                    post:
                      operationId: replaceLabels
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              additionalProperties: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED));
    }

    @Test
    void rejectsNullableSchemasUntilGeneratedContractsCanPreserveExplicitNulls() throws Exception {
        Path specification = Files.createTempFile("nullable-parameter", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Nullable API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      parameters:
                        - name: revision
                          in: query
                          required: true
                          schema: { type: string, nullable: true }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(SCHEMA_NULLABILITY_UNSUPPORTED));
    }

    @Test
    void preservesNullableSuccessResponsePropertiesForBoundedPagination() throws Exception {
        Path specification = Files.createTempFile("nullable-response", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Page API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: listWidgets
                      responses:
                        '200':
                          description: Success
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  items:
                                    type: array
                                    items: { type: string }
                                  next: { type: string, nullable: true }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
        assertTrue(operation.successResponse().supported());
        assertTrue(operation.successResponse().properties().get("next").nullable());
    }

    @Test
    void omitsReadOnlyPropertiesAndTheirRequestRequirements() throws Exception {
        Path specification = Files.createTempFile("read-only-request", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Widget API, version: '1.0' }
                paths:
                  /widgets:
                    post:
                      operationId: createWidget
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              required: [id, name]
                              properties:
                                id: { type: string, readOnly: true }
                                name: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
        assertEquals(java.util.Set.of("name"), operation.requestBody().properties().keySet());
        assertEquals(java.util.List.of("name"), operation.requestBody().requiredProperties());
    }

    @Test
    void preservesReadOnlyAndOmitsWriteOnlyResponseProperties() throws Exception {
        Path specification = Files.createTempFile("response-property-direction", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Widget API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: getWidget
                      responses:
                        '200':
                          description: Widget
                          content:
                            application/json:
                              schema:
                                type: object
                                required: [id, secret, name]
                                properties:
                                  id: { type: string, readOnly: true }
                                  secret: { type: string, writeOnly: true }
                                  name: { type: string }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
        assertEquals(java.util.Set.of("id", "name"), operation.successResponse().properties().keySet());
        assertEquals(java.util.List.of("id", "name"), operation.successResponse().requiredProperties());
    }

    @Test
    void rejectsExclusiveNumericBoundsUntilTheirSemanticsCanBePreserved() throws Exception {
        Path specification = Files.createTempFile("exclusive-bound", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Score API, version: '1.0' }
                paths:
                  /scores:
                    get:
                      operationId: getScores
                      parameters:
                        - name: minimumScore
                          in: query
                          schema:
                            type: number
                            minimum: 0
                            exclusiveMinimum: true
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(SCHEMA_CONSTRAINT_UNSUPPORTED));
    }

    @Test
    void rejectsRequestBodiesWithoutAnApplicationJsonMediaType() throws Exception {
        Path specification = Files.createTempFile("non-json-request", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Partner API, version: '1.0' }
                paths:
                  /partner:
                    post:
                      operationId: submitPartner
                      requestBody:
                        required: true
                        content:
                          application/xml:
                            schema: { type: string }
                      responses:
                        '204': { description: Accepted }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED));
    }

    @Test
    void rejectsVendorJsonBodiesWhenTheExactMediaTypeCannotBePreservedInTheP0Ir() throws Exception {
        Path specification = Files.createTempFile("json-compatible-request", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Partner API, version: '1.0' }
                paths:
                  /partner:
                    post:
                      operationId: submitPartner
                      requestBody:
                        content:
                          application/xml:
                            schema: { type: integer }
                          application/vnd.partner+json:
                            schema: { type: string }
                      responses:
                        '204': { description: Accepted }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertFalse(operation.supported());
        assertTrue(operation.support().issueCodes().contains(REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED));
    }

    @Test
    void appliesTheBoundedSuccessMediaDecisionTable() throws Exception {
        Path specification = Files.createTempFile("response-media-types", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Partner API, version: '1.0' }
                paths:
                  /plain:
                    get:
                      operationId: getPlain
                      responses:
                        '200':
                          description: Plain response
                          content:
                            text/plain: { schema: { type: string } }
                  /xml:
                    get:
                      operationId: getXml
                      responses:
                        '200':
                          description: XML response
                          content:
                            application/xml: { schema: { type: string } }
                  /json:
                    get:
                      operationId: getJson
                      responses:
                        '200':
                          description: JSON response
                          content:
                            application/json: { schema: { type: object } }
                  /vendor-json:
                    get:
                      operationId: getVendorJson
                      responses:
                        '200':
                          description: Vendor JSON response
                          content:
                            application/problem+json: { schema: { type: object } }
                  /wildcard:
                    get:
                      operationId: getWildcardJson
                      responses:
                        '200':
                          description: Inferred JSON response
                          content:
                            '*/*': { schema: { type: object } }
                  /wildcard-missing-schema:
                    get:
                      operationId: getWildcardWithoutSchema
                      responses:
                        '200':
                          description: Missing inferred schema
                          content:
                            '*/*': {}
                  /accepted:
                    get:
                      operationId: getAccepted
                      responses:
                        '204': { description: No content }
                  /mixed:
                    get:
                      operationId: getMixed
                      responses:
                        '200':
                          description: Multiple response types
                          content:
                            application/json: { schema: { type: object } }
                            text/plain: { schema: { type: string } }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations();

        var byId = operations.stream().collect(
                java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        assertEquals(java.util.List.of(SUCCESS_MEDIA_TYPE_UNSUPPORTED),
                byId.get("getPlain").support().issueCodes());
        assertEquals(java.util.List.of(SUCCESS_MEDIA_TYPE_UNSUPPORTED),
                byId.get("getXml").support().issueCodes());
        assertTrue(byId.get("getJson").supported());
        assertTrue(byId.get("getVendorJson").supported());
        assertEquals(SUPPORTED_WITH_WARNING, byId.get("getWildcardJson").support().status());
        assertEquals(java.util.List.of(SUCCESS_MEDIA_TYPE_INFERRED),
                byId.get("getWildcardJson").support().issueCodes());
        assertEquals(SchemaType.OBJECT, byId.get("getWildcardJson").successResponse().type());
        assertEquals(java.util.List.of(SUCCESS_SCHEMA_UNSUPPORTED),
                byId.get("getWildcardWithoutSchema").support().issueCodes());
        assertTrue(byId.get("getAccepted").supported());
        assertEquals(java.util.List.of(SUCCESS_MEDIA_TYPE_UNSUPPORTED),
                byId.get("getMixed").support().issueCodes());
    }

    @Test
    void failsClosedForAmbiguousSecurityAndGetRequestBodiesWhileKeepingOneApiKeyAndAlternative() throws Exception {
        Path specification = Files.createTempFile("security-semantics", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Partner API, version: '1.0' }
                security:
                  - headerKey: []
                components:
                  securitySchemes:
                    headerKey: { type: apiKey, in: header, name: X-Partner-Key }
                    queryKey: { type: apiKey, in: query, name: partner_key }
                    cookieKey: { type: apiKey, in: cookie, name: partner_cookie }
                    bearerAuth: { type: http, scheme: bearer }
                    basicAuth: { type: http, scheme: basic }
                paths:
                  /global:
                    get:
                      operationId: globalApiKey
                      responses: { '204': { description: Accepted } }
                  /none:
                    get:
                      operationId: noAuth
                      security: []
                      responses: { '204': { description: Accepted } }
                  /and:
                    get:
                      operationId: apiKeyAnd
                      security:
                        - headerKey: []
                          queryKey: []
                      responses: { '204': { description: Accepted } }
                  /bearer:
                    get:
                      operationId: bearer
                      security: [ { bearerAuth: [] } ]
                      responses: { '204': { description: Accepted } }
                  /basic:
                    get:
                      operationId: basic
                      security: [ { basicAuth: [] } ]
                      responses: { '204': { description: Accepted } }
                  /cookie:
                    get:
                      operationId: cookie
                      security: [ { cookieKey: [] } ]
                      responses: { '204': { description: Accepted } }
                  /undefined:
                    get:
                      operationId: undefinedScheme
                      security: [ { missingKey: [] } ]
                      responses: { '204': { description: Accepted } }
                  /anonymous:
                    get:
                      operationId: anonymousAlternative
                      security: [ {} ]
                      responses: { '204': { description: Accepted } }
                  /or:
                    get:
                      operationId: apiKeyOr
                      security:
                        - headerKey: []
                        - queryKey: []
                      responses: { '204': { description: Accepted } }
                  /mixed:
                    get:
                      operationId: mixedAnd
                      security:
                        - headerKey: []
                          bearerAuth: []
                      responses: { '204': { description: Accepted } }
                  /get-body:
                    get:
                      operationId: getWithBody
                      requestBody:
                        content:
                          application/json:
                            schema: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().stream()
                .collect(java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        assertTrue(operations.get("globalApiKey").supported());
        assertEquals(java.util.List.of("headerKey"), operations.get("globalApiKey").securityRequirements());
        assertTrue(operations.get("noAuth").supported());
        assertEquals(java.util.List.of(), operations.get("noAuth").securityRequirements());
        assertTrue(operations.get("apiKeyAnd").supported());
        assertEquals(java.util.List.of("headerKey", "queryKey"), operations.get("apiKeyAnd").securityRequirements());
        for (String operationId : java.util.List.of(
                "bearer", "basic", "cookie", "undefinedScheme", "anonymousAlternative", "apiKeyOr", "mixedAnd", "getWithBody")) {
            assertFalse(operations.get(operationId).supported(), operationId);
        }
    }

    @Test
    void supportsScalarParametersWithLocationDefaultStylesRegardlessOfExplicitExplode() throws Exception {
        Path specification = Files.createTempFile("scalar-parameter-styles", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Parameter API, version: '1.0' }
                paths:
                  /widgets/{widgetId}:
                    get:
                      operationId: getWidget
                      parameters:
                        - name: widgetId
                          in: path
                          required: true
                          style: simple
                          explode: true
                          schema: { type: string }
                        - name: page
                          in: query
                          schema: { type: integer }
                        - name: ratio
                          in: query
                          style: form
                          explode: false
                          schema: { type: number }
                        - name: enabled
                          in: query
                          style: form
                          explode: true
                          schema: { type: boolean }
                        - name: X-Mode
                          in: header
                          style: simple
                          explode: false
                          schema: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
    }

    @Test
    void supportsQueryArraysOnlyWithFormStyleAndEffectiveExplodeTrue() throws Exception {
        Path specification = Files.createTempFile("query-array-styles", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Parameter API, version: '1.0' }
                paths:
                  /widgets:
                    get:
                      operationId: listWidgets
                      parameters:
                        - name: tags
                          in: query
                          schema:
                            type: array
                            items: { type: string }
                        - name: levels
                          in: query
                          style: form
                          explode: true
                          schema:
                            type: array
                            items: { type: integer }
                        - name: categories
                          in: query
                          style: form
                          schema:
                            type: array
                            items: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operation = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().getFirst();

        assertTrue(operation.supported(), operation.warnings().toString());
    }

    @Test
    void rejectsUnsupportedParameterSerializationShapesAndStyles() throws Exception {
        Path specification = Files.createTempFile("unsupported-parameter-styles", ".yaml");
        Files.writeString(specification, """
                openapi: 3.0.3
                info: { title: Parameter API, version: '1.0' }
                paths:
                  /path-style/{id}:
                    get:
                      operationId: pathMatrix
                      parameters:
                        - name: id
                          in: path
                          required: true
                          style: matrix
                          schema: { type: string }
                      responses: { '204': { description: Accepted } }
                  /header-style:
                    get:
                      operationId: headerForm
                      parameters:
                        - name: X-Mode
                          in: header
                          style: form
                          schema: { type: string }
                      responses: { '204': { description: Accepted } }
                  /query-style:
                    get:
                      operationId: querySimple
                      parameters:
                        - name: mode
                          in: query
                          style: simple
                          schema: { type: string }
                      responses: { '204': { description: Accepted } }
                  /query-joined:
                    get:
                      operationId: queryJoined
                      parameters:
                        - name: tags
                          in: query
                          style: form
                          explode: false
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /query-default-joined:
                    get:
                      operationId: queryDefaultJoined
                      parameters:
                        - name: tags
                          in: query
                          explode: false
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /query-space:
                    get:
                      operationId: querySpaceDelimited
                      parameters:
                        - name: tags
                          in: query
                          style: spaceDelimited
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /query-pipe:
                    get:
                      operationId: queryPipeDelimited
                      parameters:
                        - name: tags
                          in: query
                          style: pipeDelimited
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /query-deep:
                    get:
                      operationId: queryDeepObject
                      parameters:
                        - name: tags
                          in: query
                          style: deepObject
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /path-array/{segments}:
                    get:
                      operationId: pathArray
                      parameters:
                        - name: segments
                          in: path
                          required: true
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /header-array:
                    get:
                      operationId: headerArray
                      parameters:
                        - name: X-Tags
                          in: header
                          schema: { type: array, items: { type: string } }
                      responses: { '204': { description: Accepted } }
                  /query-nested-array:
                    get:
                      operationId: queryNestedArray
                      parameters:
                        - name: matrix
                          in: query
                          schema:
                            type: array
                            items: { type: array, items: { type: integer } }
                      responses: { '204': { description: Accepted } }
                  /query-object-item:
                    get:
                      operationId: queryObjectItem
                      parameters:
                        - name: filters
                          in: query
                          schema:
                            type: array
                            items:
                              type: object
                              properties:
                                field: { type: string }
                      responses: { '204': { description: Accepted } }
                  /query-object:
                    get:
                      operationId: queryObject
                      parameters:
                        - name: filter
                          in: query
                          schema:
                            type: object
                            properties:
                              field: { type: string }
                      responses: { '204': { description: Accepted } }
                """);

        var operations = analyzer.analyze(specification, 10 * 1024 * 1024).document().operations().stream()
                .collect(java.util.stream.Collectors.toMap(operation -> operation.operationId(), operation -> operation));

        for (String operationId : java.util.List.of(
                "pathMatrix", "headerForm", "querySimple", "queryJoined", "queryDefaultJoined",
                "querySpaceDelimited", "queryPipeDelimited", "queryDeepObject", "pathArray", "headerArray",
                "queryNestedArray", "queryObjectItem", "queryObject")) {
            var operation = operations.get(operationId);
            assertFalse(operation.supported(), operationId);
            assertTrue(operation.warnings().stream().anyMatch(warning -> warning.contains("parameter serialization")),
                    operationId + ": " + operation.warnings());
        }
    }

    private Path resource(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }
}

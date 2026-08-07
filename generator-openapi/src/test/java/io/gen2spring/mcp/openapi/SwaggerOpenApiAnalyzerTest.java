package io.gen2spring.mcp.openapi;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_ID_DUPLICATED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SPEC_REFERENCE_UNRESOLVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.error.GeneratorException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SwaggerOpenApiAnalyzerTest {
    private final SpecificationAnalyzer analyzer = new SwaggerOpenApiAnalyzer();

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
        assertTrue(operations.get("integerEnum").warnings().stream()
                .anyMatch(warning -> warning.contains("Only string enum schemas")));
        assertTrue(operations.get("booleanEnum").warnings().stream()
                .anyMatch(warning -> warning.contains("Only string enum schemas")));
    }

    @Test
    void rejectsDuplicateOperationIds() throws Exception {
        var exception = assertThrows(GeneratorException.class,
                () -> analyzer.analyze(resource("openapi/duplicate-operation-id.yaml"), 10 * 1024 * 1024));

        assertEquals(OPERATION_ID_DUPLICATED, exception.code());
    }

    @Test
    void marksOperationsWithComposedSchemasAsUnsupported() throws Exception {
        var document = analyzer.analyze(resource("openapi/unsupported-schema.yaml"), 10 * 1024 * 1024).document();

        var operation = document.operations().getFirst();
        assertFalse(operation.supported());
        assertTrue(operation.warnings().stream().anyMatch(warning -> warning.contains("allOf")));
        assertTrue(operation.warnings().stream().anyMatch(warning -> warning.contains("Recursive schemas")));
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
        assertTrue(operation.warnings().stream().anyMatch(warning -> warning.contains("application/json")));
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
        assertTrue(operation.warnings().stream().anyMatch(warning -> warning.contains("application/json")));
    }

    @Test
    void supportsOnlyExactJsonSuccessBodiesWhileAllowingAnEmptyNoContentResponse() throws Exception {
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

        assertFalse(operations.stream().filter(operation -> operation.operationId().equals("getPlain"))
                .findFirst().orElseThrow().supported());
        assertFalse(operations.stream().filter(operation -> operation.operationId().equals("getXml"))
                .findFirst().orElseThrow().supported());
        assertTrue(operations.stream().filter(operation -> operation.operationId().equals("getJson"))
                .findFirst().orElseThrow().supported());
        assertTrue(operations.stream().filter(operation -> operation.operationId().equals("getAccepted"))
                .findFirst().orElseThrow().supported());
        assertFalse(operations.stream().filter(operation -> operation.operationId().equals("getMixed"))
                .findFirst().orElseThrow().supported());
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

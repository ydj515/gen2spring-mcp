package io.gen2spring.mcp.policy;

import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.OPERATION_UNSUPPORTED;
import static io.gen2spring.mcp.domain.error.GeneratorErrorCode.SECRET_EXPOSURE_DETECTED;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.SERVER_SECRET;
import static io.gen2spring.mcp.domain.tool.McpToolDefinition.ParameterSource.USER_INPUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.domain.config.GenerationRequest;
import io.gen2spring.mcp.domain.config.GenerationRequest.OperationSelection;
import io.gen2spring.mcp.domain.config.GenerationRequest.ParameterOverride;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiParameter;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ApiSecurityScheme;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.ParameterLocation;
import io.gen2spring.mcp.domain.openapi.OpenApiDocument.SchemaType;
import io.gen2spring.mcp.domain.response.ResponseNormalizationPolicy;
import io.gen2spring.mcp.domain.tool.McpToolDefinition.McpInputDefinition;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolModelFactoryTest {
    private final ToolModelFactory factory = new ToolModelFactory();

    @Test
    void exposesOnlySelectedUserInputsAndCreatesASecretBinding() {
        var tools = factory.create(weatherDocument(), generationRequest());

        assertEquals(1, tools.size());
        assertEquals(List.of("nx", "ny"), tools.getFirst().inputs().stream().map(McpInputDefinition::name).toList());
        assertEquals("KMA_SERVICE_KEY", tools.getFirst().secretBindings().getFirst().environmentVariable());
        assertEquals("serviceKey", tools.getFirst().secretBindings().getFirst().targetName());
        assertEquals("nx", tools.getFirst().execution().bindings().getFirst().sourceName());
    }

    @Test
    void attachesValidatedResponsePolicyToHttpExecution() {
        ResponseNormalizationPolicy policy = normalization();
        GenerationRequest request = request(List.of(new OperationSelection(
                "getForecast", true, null, null,
                Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")), policy)));

        var tool = factory.create(weatherDocument(), request).getFirst();

        assertSame(policy, tool.execution().responseNormalization());
    }

    @Test
    void rejectsInvalidProgrammaticResponsePolicyBeforeRendering() {
        var invalid = new ResponseNormalizationPolicy("bad", null, List.of(), null, null);

        GeneratorException failure = assertThrows(GeneratorException.class,
                () -> factory.create(weatherDocument(), request(List.of(new OperationSelection(
                        "getForecast", true, null, null,
                        Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")), invalid)))));

        assertEquals(OPERATION_UNSUPPORTED, failure.code());
        assertFalse(failure.getMessage().contains("bad"));
    }

    @Test
    void ordersToolsByOperationIdAndRejectsDuplicateFinalNames() {
        var document = document(List.of(operation("zeta", true), operation("alpha", true)));
        var orderedRequest = request(List.of(selection("zeta", null, Map.of()), selection("alpha", null, Map.of())));

        assertEquals(List.of("alpha", "zeta"), factory.create(document, orderedRequest).stream()
                .map(tool -> tool.operationId()).toList());

        var duplicateRequest = request(List.of(selection("zeta", "weather", Map.of()), selection("alpha", "weather", Map.of())));
        assertThrows(GeneratorException.class, () -> factory.create(document, duplicateRequest));
    }

    @Test
    void prefersANonblankDescriptionOverride() {
        var request = request(List.of(new OperationSelection(
                "getForecast", true, null, "  Retrieve a forecast for a grid location.  ",
                Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")))));

        assertEquals("Retrieve a forecast for a grid location.",
                factory.create(weatherDocument(), request).getFirst().description());
    }

    @Test
    void rejectsOperationsWithoutAnyUsableToolDescription() {
        var operation = new ApiOperation(
                "undocumented", HttpMethod.GET, "/undocumented", null, null,
                List.of(parameter("id")), null, false, List.of(), true, List.of());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(document(List.of(operation)), request(List.of(selection(
                        "undocumented", null, Map.of())))));

        assertEquals(OPERATION_UNSUPPORTED, exception.code());
    }

    @Test
    void rejectsMissingUnsupportedAndVisibleConfirmedSecrets() {
        var missing = request(List.of(selection("missing", null, Map.of())));
        var missingException = assertThrows(GeneratorException.class, () -> factory.create(weatherDocument(), missing));
        assertEquals(OPERATION_UNSUPPORTED, missingException.code());

        var unsupportedException = assertThrows(GeneratorException.class,
                () -> factory.create(document(List.of(operation("unsupported", false))), request(List.of(selection("unsupported", null, Map.of())))));
        assertEquals(OPERATION_UNSUPPORTED, unsupportedException.code());

        var visibleSecret = request(List.of(selection("getForecast", null,
                Map.of("serviceKey", new ParameterOverride(USER_INPUT, null)))));
        var secretException = assertThrows(GeneratorException.class, () -> factory.create(weatherDocument(), visibleSecret));
        assertEquals(SECRET_EXPOSURE_DETECTED, secretException.code());
    }

    @Test
    void rejectsApplicationReservedSecretEnvironmentNamesDuringToolModelValidation() {
        for (String reserved : List.of(
                "PROVIDER_BASE_URL",
                "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS",
                "SPRING_APPLICATION_JSON")) {
            var request = request(List.of(selection(
                    "getForecast",
                    null,
                    Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, reserved)))));

            GeneratorException exception = assertThrows(
                    GeneratorException.class,
                    () -> factory.create(weatherDocument(), request),
                    reserved);

            assertEquals(SECRET_EXPOSURE_DETECTED, exception.code(), reserved);
            assertEquals("tool-policy", exception.stage(), reserved);
            assertEquals(
                    "Server secrets cannot use an application-reserved environment variable name",
                    exception.safeMessage(),
                    reserved);
            assertFalse(exception.safeMessage().contains(reserved), reserved);
        }
    }

    @Test
    void bindsAnApplicableSchemeOnlyApiKeyFromItsExplicitSecretOverride() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(parameter("region")), null, false, List.of("partnerKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of(
                        "partnerKey", new ApiSecurityScheme(
                                "partnerKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key"),
                        "unusedKey", new ApiSecurityScheme(
                                "unusedKey", "apiKey", ParameterLocation.QUERY, "unused")),
                List.of());
        var request = request(List.of(selection("getPartnerData", null,
                Map.of("partnerKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

        var tool = factory.create(document, request).getFirst();

        assertEquals(List.of("region"), tool.inputs().stream().map(McpInputDefinition::name).toList());
        assertEquals(1, tool.secretBindings().size());
        assertEquals("PARTNER_KEY", tool.secretBindings().getFirst().environmentVariable());
        assertEquals(ParameterLocation.HEADER, tool.secretBindings().getFirst().targetLocation());
        assertEquals("X-Partner-Key", tool.secretBindings().getFirst().targetName());
    }

    @Test
    void bindsASchemeOnlyApiKeyFromItsTargetParameterNameOverride() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(), null, false, List.of("partnerKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of("partnerKey", new ApiSecurityScheme(
                        "partnerKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key")),
                List.of());
        var request = request(List.of(selection("getPartnerData", null,
                Map.of("X-Partner-Key", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

        var tool = factory.create(document, request).getFirst();

        assertEquals("PARTNER_KEY", tool.secretBindings().getFirst().environmentVariable());
        assertEquals("X-Partner-Key", tool.secretBindings().getFirst().targetName());
    }

    @Test
    void rejectsRuntimeOwnedHeaderTargetsFromSchemeOnlyApiKeysCaseInsensitively() {
        for (String header : List.of("aCcEpT", "cOnTeNt-TyPe")) {
            var operation = new ApiOperation(
                    "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                    List.of(), null, false, List.of("partnerKey"), true, List.of());
            var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                    List.of(operation),
                    Map.of("partnerKey", new ApiSecurityScheme(
                            "partnerKey", "apiKey", ParameterLocation.HEADER, header)),
                    List.of());
            var request = request(List.of(selection("getPartnerData", null,
                    Map.of("partnerKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

            GeneratorException exception = assertThrows(GeneratorException.class, () -> factory.create(document, request));

            assertEquals(OPERATION_UNSUPPORTED, exception.code(), header);
        }
    }

    @Test
    void rejectsReservedPropagationHeaderTargetsFromSchemeOnlyApiKeysWithoutEchoingTheHeader() {
        for (String header : List.of(
                "TrAcEpArEnT", "TRACESTATE", "bAgGaGe", "B3", "X-B3-TraceId", "x-b3-custom")) {
            var operation = new ApiOperation(
                    "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                    List.of(), null, false, List.of("partnerKey"), true, List.of());
            var document = new OpenApiDocument(
                    "3.0.3",
                    "checksum",
                    "yaml",
                    URI.create("https://api.example.test"),
                    List.of(operation),
                    Map.of("partnerKey", new ApiSecurityScheme(
                            "partnerKey", "apiKey", ParameterLocation.HEADER, header)),
                    List.of());
            var request = request(List.of(selection(
                    "getPartnerData",
                    null,
                    Map.of("partnerKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

            GeneratorException exception = assertThrows(
                    GeneratorException.class, () -> factory.create(document, request), header);

            assertEquals(OPERATION_UNSUPPORTED, exception.code(), header);
            assertEquals("tool-policy", exception.stage(), header);
            assertEquals("Operation uses a runtime-owned or restricted HTTP header", exception.safeMessage(), header);
            assertFalse(exception.safeMessage().contains(header), header);
        }
    }

    @Test
    void rejectsMissingAndConflictingOverridesForApplicableApiKeySchemes() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(parameter("X-Partner-Key")), null, false, List.of("partnerKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of("partnerKey", new ApiSecurityScheme(
                        "partnerKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key")),
                List.of());

        var missing = request(List.of(selection("getPartnerData", null, Map.of())));
        assertEquals(SECRET_EXPOSURE_DETECTED,
                assertThrows(GeneratorException.class, () -> factory.create(document, missing)).code());

        var conflicting = request(List.of(selection("getPartnerData", null, Map.of(
                "X-Partner-Key", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY"),
                "partnerKey", new ParameterOverride(SERVER_SECRET, "OTHER_PARTNER_KEY")))));
        assertEquals(SECRET_EXPOSURE_DETECTED,
                assertThrows(GeneratorException.class, () -> factory.create(document, conflicting)).code());
    }

    @Test
    void rejectsConflictingSchemeOverridesForApiKeysThatShareTheSameTarget() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(), null, false, List.of("primaryKey", "secondaryKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of(
                        "primaryKey", new ApiSecurityScheme(
                                "primaryKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key"),
                        "secondaryKey", new ApiSecurityScheme(
                                "secondaryKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key")),
                List.of());
        var conflicting = request(List.of(selection("getPartnerData", null, Map.of(
                "primaryKey", new ParameterOverride(SERVER_SECRET, "PRIMARY_KEY"),
                "secondaryKey", new ParameterOverride(SERVER_SECRET, "SECONDARY_KEY")))));

        assertEquals(SECRET_EXPOSURE_DETECTED,
                assertThrows(GeneratorException.class, () -> factory.create(document, conflicting)).code());
    }

    @Test
    void mergesEquivalentApiKeySchemeOverridesForTheSameTargetAsARequiredSecret() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(new ApiParameter("X-Partner-Key", ParameterLocation.HEADER, false, "key", schema())),
                null, false, List.of("primaryKey", "secondaryKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of(
                        "primaryKey", new ApiSecurityScheme(
                                "primaryKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key"),
                        "secondaryKey", new ApiSecurityScheme(
                                "secondaryKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key")),
                List.of());
        var request = request(List.of(selection("getPartnerData", null, Map.of(
                "primaryKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY"),
                "secondaryKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

        var secret = factory.create(document, request).getFirst().secretBindings().getFirst();

        assertEquals(1, factory.create(document, request).getFirst().secretBindings().size());
        assertEquals("PARTNER_KEY", secret.environmentVariable());
        assertEquals("X-Partner-Key", secret.targetName());
        assertEquals(true, secret.required());
    }

    @Test
    void matchesApiKeyHeaderTargetsCaseInsensitivelyAndKeepsTheExplicitParameterServerOnly() {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(new ApiParameter("x-partner-key", ParameterLocation.HEADER, false, "key", schema())),
                null, false, List.of("partnerKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of("partnerKey", new ApiSecurityScheme(
                        "partnerKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key")),
                List.of());
        var request = request(List.of(selection("getPartnerData", null,
                Map.of("partnerKey", new ParameterOverride(SERVER_SECRET, "PARTNER_KEY")))));

        var tool = factory.create(document, request).getFirst();

        assertTrue(tool.inputs().isEmpty());
        assertEquals(1, tool.secretBindings().size());
        assertEquals("PARTNER_KEY", tool.secretBindings().getFirst().environmentVariable());
        assertEquals("x-partner-key", tool.secretBindings().getFirst().targetName());
    }

    @Test
    void deduplicatesCaseVariantHeaderSchemesWhenTheyUseTheSameSecret() {
        var fixture = caseVariantHeaderSchemes("PARTNER_KEY", "PARTNER_KEY");

        var secrets = factory.create(fixture.document(), fixture.request()).getFirst().secretBindings();

        assertEquals(1, secrets.size());
        assertEquals("PARTNER_KEY", secrets.getFirst().environmentVariable());
    }

    @Test
    void rejectsCaseVariantHeaderSchemesWhenTheyUseDifferentSecrets() {
        var fixture = caseVariantHeaderSchemes("PRIMARY_KEY", "SECONDARY_KEY");

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(fixture.document(), fixture.request()));

        assertEquals(SECRET_EXPOSURE_DETECTED, exception.code());
    }

    @Test
    void rejectsRuntimeOwnedAndJdkRestrictedHeaderInputsCaseInsensitively() {
        for (String header : List.of(
                "aCcEpT", "cOnTeNt-TyPe", "hOsT", "cOnNeCtIoN", "cOnTeNt-LeNgTh", "eXpEcT", "uPgRaDe")) {
            var operation = new ApiOperation(
                    "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                    List.of(new ApiParameter(header, ParameterLocation.HEADER, false, header, schema())),
                    null, false, List.of(), true, List.of());

            GeneratorException exception = assertThrows(GeneratorException.class,
                    () -> factory.create(document(List.of(operation)), request(List.of(selection(
                            "getPartnerData", null, Map.of())))));

            assertEquals(OPERATION_UNSUPPORTED, exception.code(), header);
        }
    }

    @Test
    void rejectsReservedPropagationHeaderInputsWithoutEchoingTheHeader() {
        for (String header : List.of(
                "TrAcEpArEnT", "TRACESTATE", "bAgGaGe", "B3", "X-B3-TraceId", "x-b3-custom")) {
            var operation = new ApiOperation(
                    "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                    List.of(new ApiParameter(header, ParameterLocation.HEADER, false, header, schema())),
                    null, false, List.of(), true, List.of());

            GeneratorException exception = assertThrows(
                    GeneratorException.class,
                    () -> factory.create(document(List.of(operation)), request(List.of(selection(
                            "getPartnerData", null, Map.of())))),
                    header);

            assertEquals(OPERATION_UNSUPPORTED, exception.code(), header);
            assertEquals("tool-policy", exception.stage(), header);
            assertEquals("Operation uses a runtime-owned or restricted HTTP header", exception.safeMessage(), header);
            assertFalse(exception.safeMessage().contains(header), header);
        }
    }

    @Test
    void flattensObjectRequestBodyPropertiesIntoJavaSafeVisibleInputsAndBodyBindings() {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema integer = new ApiSchema(
                SchemaType.INTEGER, "int32", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null,
                Map.of("postal-code", text, "unit-number", integer), List.of("postal-code"), null, true, List.of());
        var operation = new ApiOperation(
                "submitAddress", HttpMethod.POST, "/addresses", "Submit address", null,
                List.of(), body, true, List.of(), true, List.of());

        var tool = factory.create(document(List.of(operation)), request(List.of(selection(
                "submitAddress", null, Map.of())))).getFirst();

        assertEquals(List.of("postalCode", "unitNumber"),
                tool.inputs().stream().map(McpInputDefinition::name).toList());
        assertEquals(List.of("postal-code", "unit-number"),
                tool.inputs().stream().map(McpInputDefinition::jsonName).toList());
        assertEquals(List.of(true, false), tool.inputs().stream().map(McpInputDefinition::required).toList());
        assertEquals(List.of("postalCode:postal-code", "unitNumber:unit-number"),
                tool.execution().bindings().stream()
                        .map(binding -> binding.sourceName() + ":" + binding.targetName()).toList());
        assertTrue(tool.execution().objectRequestBody());
        assertTrue(tool.execution().requestBodyRequired());
    }

    @Test
    void rejectsOptionalObjectBodiesWithRequiredProperties() {
        ApiSchema body = objectSchema(Map.of("postal-code", textSchema()), List.of("postal-code"));
        var operation = new ApiOperation(
                "submitAddress", HttpMethod.POST, "/addresses", "Submit address", null,
                List.of(), body, false, List.of(), true, List.of());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(document(List.of(operation)), request(List.of(selection(
                        "submitAddress", null, Map.of())))));

        assertEquals(OPERATION_UNSUPPORTED, exception.code());
    }

    @Test
    void keepsOptionalObjectBodiesWithoutRequiredPropertiesSupported() {
        ApiSchema body = objectSchema(Map.of("note", textSchema()), List.of());
        var operation = new ApiOperation(
                "submitAddress", HttpMethod.POST, "/addresses", "Submit address", null,
                List.of(), body, false, List.of(), true, List.of());

        var tool = factory.create(document(List.of(operation)), request(List.of(selection(
                "submitAddress", null, Map.of())))).getFirst();

        assertFalse(tool.inputs().getFirst().required());
        assertTrue(tool.execution().objectRequestBody());
        assertFalse(tool.execution().requestBodyRequired());
    }

    @Test
    void rejectsApprovedSecretCandidateNamesAnywhereInRequestBodySchemas() {
        ApiSchema topLevel = objectSchema(Map.of("api_key", textSchema()), List.of());
        ApiSchema nested = objectSchema(
                Map.of("credentials", objectSchema(Map.of("clientSecret", textSchema()), List.of())), List.of());
        ApiSchema array = new ApiSchema(
                SchemaType.ARRAY, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(),
                objectSchema(Map.of("serviceKey", textSchema()), List.of()), true, List.of());
        List<Map.Entry<String, ApiSchema>> cases = List.of(
                Map.entry("top-level", topLevel),
                Map.entry("nested object", nested),
                Map.entry("array item object", array));

        for (Map.Entry<String, ApiSchema> testCase : cases) {
            var operation = new ApiOperation(
                    "submitCredential", HttpMethod.POST, "/credentials", "Submit credential", null,
                    List.of(), testCase.getValue(), true, List.of(), true, List.of());

            GeneratorException exception = assertThrows(GeneratorException.class,
                    () -> factory.create(document(List.of(operation)), request(List.of(selection(
                            "submitCredential", null, Map.of())))), testCase.getKey());

            assertEquals(SECRET_EXPOSURE_DETECTED, exception.code(), testCase.getKey());
        }
    }

    @Test
    void rejectsObjectBodyPropertiesThatNormalizeToTheSameJavaInputName() {
        ApiSchema text = new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
        ApiSchema body = new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null,
                Map.of("postal-code", text, "postal_code", text), List.of(), null, true, List.of());
        var operation = new ApiOperation(
                "submitAddress", HttpMethod.POST, "/addresses", "Submit address", null,
                List.of(), body, true, List.of(), true, List.of());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(document(List.of(operation)), request(List.of(selection(
                        "submitAddress", null, Map.of())))));

        assertEquals(OPERATION_UNSUPPORTED, exception.code());
    }

    @Test
    void rejectsUnknownOverridesWhileAcceptingApiKeyTargetAndSchemeAliases() {
        ApiOperation operation = new ApiOperation(
                "lookupCredential", HttpMethod.GET, "/credentials", "Lookup credential", null,
                List.of(parameter("page"), parameter("api_key")), null, false,
                List.of("nonstandardCredential"), true, List.of());
        OpenApiDocument document = new OpenApiDocument(
                "3.0.3", "checksum", "yaml", URI.create("https://api.weather.example.com"), List.of(operation),
                Map.of("nonstandardCredential", new ApiSecurityScheme(
                        "nonstandardCredential", "apiKey", ParameterLocation.QUERY, "api_key")),
                List.of());

        assertEquals(1, factory.create(document, request(List.of(selection(
                "lookupCredential", null,
                Map.of("api_key", new ParameterOverride(SERVER_SECRET, "API_KEY")))))).size());
        assertEquals(1, factory.create(document, request(List.of(selection(
                "lookupCredential", null,
                Map.of("nonstandardCredential", new ParameterOverride(SERVER_SECRET, "API_KEY")))))).size());
        var parameterAliases = Map.of(
                "page", new ParameterOverride(SERVER_SECRET, "PAGE_KEY"),
                "api_key", new ParameterOverride(SERVER_SECRET, "API_KEY"));
        var parameterAliasTools = factory.create(
                document, request(List.of(selection("lookupCredential", null, parameterAliases))));
        assertEquals(1, parameterAliasTools.size());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(document, request(List.of(selection(
                        "lookupCredential", null,
                        Map.of("nonstandardCredentail", new ParameterOverride(SERVER_SECRET, "API_KEY")))))));

        assertEquals(OPERATION_UNSUPPORTED, exception.code());
    }

    @Test
    void rejectsAnOverrideAliasThatTargetsSameNamedParametersInDifferentLocations() {
        ApiOperation operation = new ApiOperation(
                "lookupToken", HttpMethod.GET, "/tokens", "Lookup token", null,
                List.of(
                        new ApiParameter("token", ParameterLocation.QUERY, true, "Lookup token", textSchema()),
                        new ApiParameter("token", ParameterLocation.HEADER, false, "API key", textSchema())),
                null, false, List.of("tokenAuth"), true, List.of());
        OpenApiDocument document = new OpenApiDocument(
                "3.0.3", "checksum", "yaml", URI.create("https://api.weather.example.com"), List.of(operation),
                Map.of("tokenAuth", new ApiSecurityScheme(
                        "tokenAuth", "apiKey", ParameterLocation.HEADER, "token")),
                List.of());

        GeneratorException exception = assertThrows(GeneratorException.class,
                () -> factory.create(document, request(List.of(selection(
                        "lookupToken", null,
                        Map.of("token", new ParameterOverride(SERVER_SECRET, "TOKEN_KEY")))))));

        assertEquals(OPERATION_UNSUPPORTED, exception.code());
    }

    private GenerationRequest generationRequest() {
        return request(List.of(selection("getForecast", null,
                Map.of("serviceKey", new ParameterOverride(SERVER_SECRET, "KMA_SERVICE_KEY")))));
    }

    private ApiKeyFixture caseVariantHeaderSchemes(String primaryEnvironment, String secondaryEnvironment) {
        var operation = new ApiOperation(
                "getPartnerData", HttpMethod.GET, "/partner", "Partner data", null,
                List.of(), null, false, List.of("primaryKey", "secondaryKey"), true, List.of());
        var document = new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.example.test"),
                List.of(operation),
                Map.of(
                        "primaryKey", new ApiSecurityScheme(
                                "primaryKey", "apiKey", ParameterLocation.HEADER, "X-Partner-Key"),
                        "secondaryKey", new ApiSecurityScheme(
                                "secondaryKey", "apiKey", ParameterLocation.HEADER, "x-partner-key")),
                List.of());
        var request = request(List.of(selection("getPartnerData", null, Map.of(
                "primaryKey", new ParameterOverride(SERVER_SECRET, primaryEnvironment),
                "secondaryKey", new ParameterOverride(SERVER_SECRET, secondaryEnvironment)))));
        return new ApiKeyFixture(document, request);
    }

    private ApiSchema objectSchema(Map<String, ApiSchema> properties, List<String> requiredProperties) {
        return new ApiSchema(
                SchemaType.OBJECT, null, false, List.of(), null, null,
                null, null, null, null, properties, requiredProperties, null, true, List.of());
    }

    private ApiSchema textSchema() {
        return new ApiSchema(
                SchemaType.STRING, null, false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
    }

    private GenerationRequest request(List<OperationSelection> selections) {
        return new GenerationRequest(
                new GenerationRequest.ProjectCoordinates("io.example", "weather", "io.example.weather"),
                "KMA", "weather", "spring-ai-2.0-java21-mvc-streamable",
                GenerationRequest.ValidationLevel.MCP_PROTOCOL,
                new GenerationRequest.ValidationConfiguration(new GenerationRequest.ToolCallValidation(
                        "getForecast", Map.of("nx", 60, "ny", 127))),
                selections);
    }

    private OperationSelection selection(String operationId, String toolName, Map<String, ParameterOverride> parameters) {
        return new OperationSelection(operationId, true, toolName, null, parameters);
    }

    private ResponseNormalizationPolicy normalization() {
        return new ResponseNormalizationPolicy(
                "/response/body/items", "/response/header/code", List.of("00", 0, false),
                "/response/header/message", "/response/body/totalCount");
    }

    private OpenApiDocument weatherDocument() {
        return document(List.of(new ApiOperation(
                "getForecast", HttpMethod.GET, "/forecast", "Get forecast", "Public weather forecast",
                List.of(parameter("nx"), parameter("ny"), parameter("serviceKey")), null, false,
                List.of("serviceKeyAuth"), true, List.of())));
    }

    private OpenApiDocument document(List<ApiOperation> operations) {
        return new OpenApiDocument("3.0.3", "checksum", "yaml", URI.create("https://api.weather.example.com"), operations,
                Map.of("serviceKeyAuth", new ApiSecurityScheme("serviceKeyAuth", "apiKey", ParameterLocation.QUERY, "serviceKey")),
                List.of());
    }

    private ApiOperation operation(String operationId, boolean supported) {
        return new ApiOperation(operationId, HttpMethod.GET, "/" + operationId, operationId, null,
                List.of(parameter("id")), null, false, List.of(), supported, List.of());
    }

    private ApiParameter parameter(String name) {
        return new ApiParameter(name, ParameterLocation.QUERY, true, name + " description", schema());
    }

    private ApiSchema schema() {
        return new ApiSchema(SchemaType.INTEGER, "int32", false, List.of(), null, null,
                null, null, null, null, Map.of(), List.of(), null, true, List.of());
    }

    private record ApiKeyFixture(OpenApiDocument document, GenerationRequest request) {}
}

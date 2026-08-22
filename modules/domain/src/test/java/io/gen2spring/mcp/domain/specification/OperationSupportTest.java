package io.gen2spring.mcp.domain.specification;

import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.OPERATION_ID_MISSING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SCHEMA_NESTED_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_MEDIA_TYPE_INFERRED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode.SUCCESS_SCHEMA_UNSUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.SUPPORTED;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.SUPPORTED_WITH_WARNING;
import static io.gen2spring.mcp.domain.specification.OperationSupport.Status.UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OperationSupportTest {
    @Test
    void derivesTheCanonicalStatusFromDeterministicallyOrderedIssues() {
        OperationSupport supported = OperationSupport.fromIssues(List.of());
        OperationSupport warning = OperationSupport.fromIssues(List.of(
                SUCCESS_MEDIA_TYPE_INFERRED, SUCCESS_MEDIA_TYPE_INFERRED));
        OperationSupport unsupported = OperationSupport.fromIssues(List.of(
                SUCCESS_SCHEMA_UNSUPPORTED, SUCCESS_MEDIA_TYPE_INFERRED, OPERATION_ID_MISSING));

        assertEquals(SUPPORTED, supported.status());
        assertEquals(SUPPORTED_WITH_WARNING, warning.status());
        assertEquals(UNSUPPORTED, unsupported.status());
        assertTrue(supported.selectable());
        assertTrue(warning.selectable());
        assertFalse(unsupported.selectable());
        assertEquals(List.of(SUCCESS_MEDIA_TYPE_INFERRED), warning.issueCodes());
        assertEquals(List.of(OPERATION_ID_MISSING, SUCCESS_MEDIA_TYPE_INFERRED, SUCCESS_SCHEMA_UNSUPPORTED),
                unsupported.issueCodes());
    }

    @Test
    void exposesOnlyTheApprovedFiniteIssueContract() {
        assertEquals(List.of(
                "OPERATION_ID_MISSING",
                "OPERATION_ID_DUPLICATED",
                "HTTP_METHOD_UNSUPPORTED",
                "RECURSIVE_SCHEMA_UNSUPPORTED",
                "SCHEMA_MISSING",
                "SCHEMA_TYPE_UNSUPPORTED",
                "SCHEMA_NULLABILITY_UNSUPPORTED",
                "PARAMETER_NULLABLE_PATH_UNSUPPORTED",
                "PARAMETER_REQUIRED_NULLABLE_UNSUPPORTED",
                "SCHEMA_COMPOSITION_UNSUPPORTED",
                "SCHEMA_MULTI_TYPE_UNSUPPORTED",
                "SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED",
                "SCHEMA_CONSTRAINT_UNSUPPORTED",
                "SCHEMA_NESTED_UNSUPPORTED",
                "PARAMETER_LOCATION_UNSUPPORTED",
                "PARAMETER_SERIALIZATION_UNSUPPORTED",
                "GET_REQUEST_BODY_UNSUPPORTED",
                "REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED",
                "SUCCESS_MEDIA_TYPE_INFERRED",
                "SUCCESS_MEDIA_TYPE_UNSUPPORTED",
                "SUCCESS_SCHEMA_UNSUPPORTED",
                "SECURITY_REQUIREMENT_UNSUPPORTED"),
                Arrays.stream(OperationSupport.IssueCode.values()).map(Enum::name).toList());

        assertEquals(OperationSupport.Severity.WARNING, SUCCESS_MEDIA_TYPE_INFERRED.severity());
        assertEquals("The JSON response media type was inferred from */*",
                SUCCESS_MEDIA_TYPE_INFERRED.message());
        assertEquals(OperationSupport.Severity.ERROR, OPERATION_ID_MISSING.severity());
        assertEquals("The operation must declare an operationId", OPERATION_ID_MISSING.message());
        assertEquals("A nested schema contains unsupported features", SCHEMA_NESTED_UNSUPPORTED.message());
    }

    @Test
    void canonicalConstructorAlsoDeduplicatesAndOrdersIssues() {
        OperationSupport support = new OperationSupport(UNSUPPORTED, List.of(
                new OperationSupport.Issue(SUCCESS_SCHEMA_UNSUPPORTED),
                new OperationSupport.Issue(OPERATION_ID_MISSING),
                new OperationSupport.Issue(SUCCESS_SCHEMA_UNSUPPORTED)));

        assertEquals(List.of(OPERATION_ID_MISSING, SUCCESS_SCHEMA_UNSUPPORTED), support.issueCodes());
        assertThrows(UnsupportedOperationException.class,
                () -> support.issues().add(new OperationSupport.Issue(SUCCESS_MEDIA_TYPE_INFERRED)));
    }

    @Test
    void rejectsNullIssuesWithoutLeakingInput() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperationSupport.fromIssues(Arrays.asList(OPERATION_ID_MISSING, null)));

        assertEquals("Operation support is invalid", failure.getMessage());
    }
}

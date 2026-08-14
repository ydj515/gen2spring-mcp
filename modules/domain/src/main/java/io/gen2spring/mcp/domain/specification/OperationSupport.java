package io.gen2spring.mcp.domain.specification;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public record OperationSupport(Status status, List<Issue> issues) {
    private static final String INVALID = "Operation support is invalid";

    public OperationSupport {
        if (status == null || issues == null || issues.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(INVALID);
        }
        EnumSet<IssueCode> codes = issues.isEmpty()
                ? EnumSet.noneOf(IssueCode.class)
                : EnumSet.copyOf(issues.stream().map(Issue::code).toList());
        issues = codes.stream().map(Issue::new).toList();
        boolean hasError = issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        boolean hasWarning = issues.stream().anyMatch(issue -> issue.severity() == Severity.WARNING);
        Status expected = hasError ? Status.UNSUPPORTED
                : hasWarning ? Status.SUPPORTED_WITH_WARNING : Status.SUPPORTED;
        if (status != expected) {
            throw new IllegalArgumentException(INVALID);
        }
    }

    public static OperationSupport fromIssues(List<IssueCode> issueCodes) {
        if (issueCodes == null || issueCodes.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(INVALID);
        }
        EnumSet<IssueCode> unique = issueCodes.isEmpty()
                ? EnumSet.noneOf(IssueCode.class) : EnumSet.copyOf(issueCodes);
        List<Issue> issues = new ArrayList<>(unique.size());
        unique.forEach(code -> issues.add(new Issue(code)));
        boolean hasError = unique.stream().anyMatch(code -> code.severity() == Severity.ERROR);
        Status status = hasError ? Status.UNSUPPORTED
                : unique.isEmpty() ? Status.SUPPORTED : Status.SUPPORTED_WITH_WARNING;
        return new OperationSupport(status, issues);
    }

    public boolean selectable() {
        return status != Status.UNSUPPORTED;
    }

    public List<IssueCode> issueCodes() {
        return issues.stream().map(Issue::code).toList();
    }

    public enum Status {
        SUPPORTED,
        SUPPORTED_WITH_WARNING,
        UNSUPPORTED
    }

    public enum Severity {
        WARNING,
        ERROR
    }

    public enum IssueCode {
        OPERATION_ID_MISSING(Severity.ERROR, "The operation must declare an operationId"),
        OPERATION_ID_DUPLICATED(Severity.ERROR, "The operationId is duplicated"),
        HTTP_METHOD_UNSUPPORTED(Severity.ERROR, "The HTTP method is not supported"),
        RECURSIVE_SCHEMA_UNSUPPORTED(Severity.ERROR, "Recursive schemas are not supported"),
        SCHEMA_MISSING(Severity.ERROR, "The schema is missing"),
        SCHEMA_TYPE_UNSUPPORTED(Severity.ERROR, "The schema type is not supported"),
        SCHEMA_NULLABILITY_UNSUPPORTED(Severity.ERROR, "The schema nullability is not supported"),
        SCHEMA_COMPOSITION_UNSUPPORTED(Severity.ERROR, "Composed schemas are not supported"),
        SCHEMA_MULTI_TYPE_UNSUPPORTED(Severity.ERROR, "Multiple non-null schema types are not supported"),
        SCHEMA_ADDITIONAL_PROPERTIES_UNSUPPORTED(
                Severity.ERROR, "Schemas with additional properties are not supported"),
        SCHEMA_CONSTRAINT_UNSUPPORTED(Severity.ERROR, "The schema constraint is not supported"),
        SCHEMA_NESTED_UNSUPPORTED(Severity.ERROR, "The nested schema is not supported"),
        PARAMETER_LOCATION_UNSUPPORTED(Severity.ERROR, "The parameter location is not supported"),
        PARAMETER_SERIALIZATION_UNSUPPORTED(Severity.ERROR, "The parameter serialization is not supported"),
        GET_REQUEST_BODY_UNSUPPORTED(Severity.ERROR, "GET request bodies are not supported"),
        REQUEST_BODY_MEDIA_TYPE_UNSUPPORTED(
                Severity.ERROR, "The request body must declare a supported JSON media type"),
        SUCCESS_MEDIA_TYPE_INFERRED(
                Severity.WARNING, "The JSON response media type was inferred from */*"),
        SUCCESS_MEDIA_TYPE_UNSUPPORTED(Severity.ERROR, "The success response media type is not supported"),
        SUCCESS_SCHEMA_UNSUPPORTED(Severity.ERROR, "The success response schema is not supported"),
        SECURITY_REQUIREMENT_UNSUPPORTED(Severity.ERROR, "The security requirement is not supported");

        private final Severity severity;
        private final String message;

        IssueCode(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public Severity severity() {
            return severity;
        }

        public String message() {
            return message;
        }
    }

    public record Issue(IssueCode code) {
        public Issue {
            if (code == null) {
                throw new IllegalArgumentException(INVALID);
            }
        }

        public Severity severity() {
            return code.severity();
        }

        public String message() {
            return code.message();
        }
    }
}

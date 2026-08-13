package io.gen2spring.mcp.application.analysis;

import io.gen2spring.mcp.domain.specification.OpenApiDocument;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.AnalysisWarning;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiOperation;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiParameter;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSchema;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ApiSecurityScheme;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.HttpMethod;
import io.gen2spring.mcp.domain.specification.OperationSupport;
import io.gen2spring.mcp.domain.specification.OperationSupport.IssueCode;
import io.gen2spring.mcp.domain.specification.OperationSupport.Severity;
import io.gen2spring.mcp.domain.specification.OperationSupport.Status;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpecificationAnalysisView(
        String checksum,
        String openApiVersion,
        String sourceExtension,
        URI baseUrl,
        Counts counts,
        List<Operation> operations,
        Map<String, ApiSecurityScheme> securitySchemes,
        List<AnalysisWarning> warnings) {
    private static final String INVALID = "Specification analysis is invalid";

    public SpecificationAnalysisView {
        if (checksum == null || openApiVersion == null || sourceExtension == null
                || counts == null || operations == null || securitySchemes == null || warnings == null) {
            throw new IllegalArgumentException(INVALID);
        }
        operations = List.copyOf(operations);
        Counts expectedCounts = new Counts(
                operations.size(),
                Math.toIntExact(operations.stream()
                        .filter(operation -> operation.status() == Status.SUPPORTED).count()),
                Math.toIntExact(operations.stream()
                        .filter(operation -> operation.status() == Status.SUPPORTED_WITH_WARNING).count()),
                Math.toIntExact(operations.stream()
                        .filter(operation -> operation.status() == Status.UNSUPPORTED).count()));
        if (!counts.equals(expectedCounts)) {
            throw new IllegalArgumentException(INVALID);
        }
        Map<String, ApiSecurityScheme> sortedSchemes = new LinkedHashMap<>();
        securitySchemes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sortedSchemes.put(entry.getKey(), entry.getValue()));
        securitySchemes = Collections.unmodifiableMap(sortedSchemes);
        warnings = List.copyOf(warnings);
    }

    public static SpecificationAnalysisView from(OpenApiDocument document) {
        if (document == null || document.operations() == null || document.securitySchemes() == null
                || document.warnings() == null) {
            throw new IllegalArgumentException(INVALID);
        }
        List<Operation> operations = document.operations().stream().map(Operation::from).toList();
        int supported = Math.toIntExact(operations.stream()
                .filter(operation -> operation.status() == Status.SUPPORTED).count());
        int supportedWithWarning = Math.toIntExact(operations.stream()
                .filter(operation -> operation.status() == Status.SUPPORTED_WITH_WARNING).count());
        int unsupported = Math.toIntExact(operations.stream()
                .filter(operation -> operation.status() == Status.UNSUPPORTED).count());
        return new SpecificationAnalysisView(
                document.checksum(),
                document.openApiVersion(),
                document.sourceExtension(),
                document.baseUrl(),
                new Counts(operations.size(), supported, supportedWithWarning, unsupported),
                operations,
                document.securitySchemes(),
                document.warnings());
    }

    public record Counts(int total, int supported, int supportedWithWarning, int unsupported) {
        public Counts {
            if (total < 0 || supported < 0 || supportedWithWarning < 0 || unsupported < 0
                    || total != supported + supportedWithWarning + unsupported) {
                throw new IllegalArgumentException(INVALID);
            }
        }
    }

    public record Operation(
            String operationId,
            HttpMethod method,
            String path,
            String summary,
            String description,
            List<ApiParameter> parameters,
            ApiSchema requestBody,
            boolean requestBodyRequired,
            List<String> securityRequirements,
            OperationSupport support,
            Status status,
            boolean supported,
            List<Issue> issues,
            List<String> warnings,
            ApiSchema successResponse) {
        public Operation {
            if (method == null || path == null || parameters == null || securityRequirements == null
                    || support == null || status == null || issues == null || warnings == null
                    || status != support.status() || supported != support.selectable()) {
                throw new IllegalArgumentException(INVALID);
            }
            parameters = List.copyOf(parameters);
            securityRequirements = List.copyOf(securityRequirements);
            issues = List.copyOf(issues);
            warnings = List.copyOf(warnings);
            List<Issue> expectedIssues = support.issues().stream().map(Issue::from).toList();
            if (!issues.equals(expectedIssues)
                    || !warnings.equals(expectedIssues.stream().map(Issue::message).toList())) {
                throw new IllegalArgumentException(INVALID);
            }
        }

        private static Operation from(ApiOperation operation) {
            return new Operation(
                    operation.operationId(),
                    operation.method(),
                    operation.path(),
                    operation.summary(),
                    operation.description(),
                    operation.parameters(),
                    operation.requestBody(),
                    operation.requestBodyRequired(),
                    operation.securityRequirements(),
                    operation.support(),
                    operation.support().status(),
                    operation.supported(),
                    operation.support().issues().stream().map(Issue::from).toList(),
                    operation.warnings(),
                    operation.successResponse());
        }
    }

    public record Issue(IssueCode code, Severity severity, String message) {
        public Issue {
            if (code == null || severity == null || message == null
                    || severity != code.severity() || !message.equals(code.message())) {
                throw new IllegalArgumentException(INVALID);
            }
        }

        private static Issue from(io.gen2spring.mcp.domain.specification.OperationSupport.Issue issue) {
            return new Issue(issue.code(), issue.severity(), issue.message());
        }
    }
}

package io.gen2spring.mcp.app.web.presentation.error;

import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.app.web.application.hosted.port.in.HostedSubmissionUseCase;
import io.gen2spring.mcp.app.web.application.hosted.service.HostedArtifactDownloadService.HostedArtifactUnavailable;
import io.gen2spring.mcp.app.web.application.local.exception.LocalConfigurationFailure;
import io.gen2spring.mcp.app.web.application.local.exception.LocalJobFailure;
import io.gen2spring.mcp.app.web.application.local.exception.LocalSpecificationFailure;
import io.gen2spring.mcp.app.web.application.local.io.BoundedBodyReader;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiffService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.job.HostedJobFailure;
import io.gen2spring.mcp.application.managed.audit.RuntimeAuditService;
import io.gen2spring.mcp.application.managed.credential.service.ManagedCredentialService;
import io.gen2spring.mcp.application.managed.policy.RuntimeGrantService;
import io.gen2spring.mcp.application.managed.runtime.service.ManagedRuntimeMigrationService;
import io.gen2spring.mcp.application.managed.runtime.service.ManagedRuntimeService;
import io.gen2spring.mcp.domain.error.GeneratorException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Component
public final class WebErrorMapper {
    public WebFailure map(Throwable failure) {
        if (failure instanceof BoundedBodyReader.BodyTooLargeException) {
            return new WebFailure(413, "REQUEST_TOO_LARGE", "HTTP", "The request body is too large");
        }
        if (failure instanceof LocalSpecificationFailure storage) {
            return switch (storage.kind()) {
                case WORKSPACE_CREATE -> new WebFailure(500, "WORKSPACE_CREATE_FAILED", "WEB_START",
                        "The private workspace could not be created");
                case INVALID_NAME -> new WebFailure(400, "SPECIFICATION_NAME_INVALID", "SPEC_STORE",
                        "The specification name is invalid");
                case STORE_FAILED -> new WebFailure(500, "SPECIFICATION_STORE_FAILED", "SPEC_STORE",
                        "The specification could not be stored");
                case NOT_FOUND -> new WebFailure(404, "SPECIFICATION_NOT_FOUND", "SPEC_STORE",
                        "The specification was not found");
                case CAPACITY -> new WebFailure(429, "SPECIFICATION_CAPACITY_EXCEEDED", "SPEC_STORE",
                        "The specification capacity is exhausted");
            };
        }
        if (failure instanceof WebException exception) {
            return exception.failure();
        }
        if (failure instanceof HostedResourceNotFound
                || failure instanceof HostedSubmissionUseCase.HostedSpecificationNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "HOSTED_LOOKUP", "The hosted resource was not found");
        }
        if (failure instanceof ToolCatalogService.ToolCatalogNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "CATALOG_LOOKUP",
                    "The hosted resource was not found");
        }
        if (failure instanceof ToolCatalogService.ToolCatalogQueryInvalid) {
            return new WebFailure(400, "CATALOG_QUERY_INVALID", "CATALOG_LOOKUP",
                    "The Tool Catalog query is invalid");
        }
        if (failure instanceof CatalogDiffService.CatalogDiffQueryInvalid) {
            return new WebFailure(400, "CATALOG_DIFF_QUERY_INVALID", "CATALOG_DIFF",
                    "The Catalog diff query is invalid");
        }
        if (failure instanceof CatalogDiffService.CatalogDiffNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "CATALOG_DIFF",
                    "The hosted resource was not found");
        }
        if (failure instanceof CatalogDiffService.CatalogDiffUnavailable) {
            return new WebFailure(503, "CATALOG_DIFF_UNAVAILABLE", "CATALOG_DIFF",
                    "The Catalog diff is unavailable");
        }
        if (failure instanceof ManagedRuntimeService.ManagedRuntimeRequestInvalid) {
            return new WebFailure(400, "RUNTIME_REQUEST_INVALID", "RUNTIME_CONTROL",
                    "The managed runtime request is invalid");
        }
        if (failure instanceof ManagedRuntimeService.ManagedRuntimeNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "RUNTIME_CONTROL",
                    "The hosted resource was not found");
        }
        if (failure instanceof ManagedRuntimeService.ManagedRuntimeUnavailable) {
            return new WebFailure(503, "RUNTIME_UNAVAILABLE", "RUNTIME_CONTROL",
                    "The managed runtime is unavailable");
        }
        if (failure instanceof ManagedRuntimeMigrationService.RuntimeMigrationRequestInvalid) {
            return new WebFailure(400, "RUNTIME_MIGRATION_REQUEST_INVALID", "RUNTIME_MIGRATION",
                    "The runtime migration request is invalid");
        }
        if (failure instanceof ManagedRuntimeMigrationService.RuntimeMigrationNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "RUNTIME_MIGRATION",
                    "The hosted resource was not found");
        }
        if (failure instanceof ManagedRuntimeMigrationService.CatalogVersionConflict) {
            return new WebFailure(409, "CATALOG_VERSION_CONFLICT", "RUNTIME_MIGRATION",
                    "The Catalog version changed before the request was applied");
        }
        if (failure instanceof ManagedRuntimeMigrationService.CatalogMigrationBreaking) {
            return new WebFailure(409, "CATALOG_MIGRATION_BREAKING", "RUNTIME_MIGRATION",
                    "The target Catalog contains breaking changes");
        }
        if (failure instanceof ManagedRuntimeMigrationService.CatalogMigrationBlocked) {
            return new WebFailure(409, "CATALOG_MIGRATION_BLOCKED", "RUNTIME_MIGRATION",
                    "The Catalog migration is blocked by the active Runtime policy");
        }
        if (failure instanceof ManagedRuntimeMigrationService.RuntimeMigrationUnavailable) {
            return new WebFailure(503, "RUNTIME_MIGRATION_UNAVAILABLE", "RUNTIME_MIGRATION",
                    "The runtime migration service is unavailable");
        }
        if (failure instanceof ManagedCredentialService.ManagedCredentialRequestInvalid) {
            return new WebFailure(400, "CREDENTIAL_REQUEST_INVALID", "CREDENTIAL_CONTROL",
                    "The managed credential request is invalid");
        }
        if (failure instanceof ManagedCredentialService.ManagedCredentialNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "CREDENTIAL_CONTROL",
                    "The hosted resource was not found");
        }
        if (failure instanceof ManagedCredentialService.ManagedCredentialUnavailable) {
            return new WebFailure(503, "CREDENTIAL_UNAVAILABLE", "CREDENTIAL_CONTROL",
                    "The managed credential service is unavailable");
        }
        if (failure instanceof RuntimeGrantService.RuntimeGrantRequestInvalid) {
            return new WebFailure(400, "RUNTIME_GRANT_REQUEST_INVALID", "RUNTIME_GRANT_CONTROL",
                    "The managed runtime grant request is invalid");
        }
        if (failure instanceof RuntimeGrantService.RuntimeGrantNotFound
                || failure instanceof RuntimeAuditService.RuntimeAuditNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "RUNTIME_POLICY_CONTROL",
                    "The hosted resource was not found");
        }
        if (failure instanceof RuntimeGrantService.RuntimeGrantUnavailable
                || failure instanceof RuntimeAuditService.RuntimeAuditUnavailable) {
            return new WebFailure(503, "RUNTIME_POLICY_UNAVAILABLE", "RUNTIME_POLICY_CONTROL",
                    "The managed runtime policy is unavailable");
        }
        if (failure instanceof RuntimeAuditService.RuntimeAuditRequestInvalid) {
            return new WebFailure(400, "RUNTIME_AUDIT_REQUEST_INVALID", "RUNTIME_AUDIT_QUERY",
                    "The managed runtime audit request is invalid");
        }
        if (failure instanceof HostedJobFailure hosted) {
            return switch (hosted.code()) {
                case NOT_FOUND -> new WebFailure(404, "RESOURCE_NOT_FOUND", "HOSTED_LOOKUP", hosted.getMessage());
                case IDEMPOTENCY_CONFLICT -> new WebFailure(409, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
                case CAPACITY_EXCEEDED -> new WebFailure(429, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
                case INVALID_REQUEST -> new WebFailure(400, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
            };
        }
        if (failure instanceof HostedSubmissionUseCase.HostedSubmissionFailure) {
            return new WebFailure(400, "HOSTED_SUBMISSION_INVALID", "HOSTED_SUBMIT", "The hosted request is invalid");
        }
        if (failure instanceof HostedAuthenticationFailure) {
            return new WebFailure(401, "AUTHENTICATION_REQUIRED", "OIDC", "Hosted authentication is required");
        }
        if (failure instanceof HostedArtifactUnavailable) {
            return new WebFailure(409, "ARTIFACT_UNAVAILABLE", "ARTIFACT_READ", "The requested artifact is unavailable");
        }
        if (failure instanceof HttpMediaTypeNotSupportedException) {
            return new WebFailure(415, "CONTENT_TYPE_UNSUPPORTED", "HTTP",
                    "The request content type is not supported");
        }
        if (failure instanceof HttpRequestMethodNotSupportedException) {
            return new WebFailure(405, "METHOD_NOT_ALLOWED", "HTTP",
                    "The request method is not allowed");
        }
        if (failure instanceof MissingRequestHeaderException) {
            return new WebFailure(400, "REQUEST_INVALID", "HTTP", "The request is invalid");
        }
        if (failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentTypeMismatchException) {
            return new WebFailure(400, "REQUEST_INVALID", "HTTP", "The request is invalid");
        }
        if (failure instanceof NoResourceFoundException) {
            return new WebFailure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
        }
        if (failure instanceof LocalJobFailure job) {
            return switch (job.kind()) {
                case CAPACITY -> new WebFailure(429, "GENERATION_CAPACITY_EXCEEDED", "JOB_CREATE",
                        "The generation capacity is exhausted");
                case NOT_FOUND -> new WebFailure(404, "JOB_NOT_FOUND", "JOB_LOOKUP",
                        "The generation job was not found");
                case INVALID_STATE -> new WebFailure(409, "JOB_STATE_INVALID", "JOB_DELETE",
                        "The generation job state does not allow this request");
                case ARTIFACT_UNAVAILABLE -> new WebFailure(409, "ARTIFACT_UNAVAILABLE", "ARTIFACT_READ",
                        "The requested artifact is unavailable");
                case WORKSPACE_CREATE -> new WebFailure(500, "WORKSPACE_CREATE_FAILED", "JOB_CREATE",
                        "The generation workspace could not be created");
            };
        }
        if (failure instanceof LocalConfigurationFailure exception) {
            return new WebFailure(400, "CONFIGURATION_INVALID", "CONFIG_PARSE", exception.getMessage());
        }
        if (failure instanceof GeneratorException exception) {
            return new WebFailure(422, exception.code().name(), exception.stage(), exception.safeMessage());
        }
        return new WebFailure(500, "INTERNAL_ERROR", "WEB", "The request failed safely");
    }

    public WebFailure httpStatus(int status) {
        return switch (status) {
            case 400 -> new WebFailure(400, "REQUEST_INVALID", "HTTP", "The request is invalid");
            case 403 -> new WebFailure(403, "REQUEST_FORBIDDEN", "HTTP", "The request is not allowed");
            case 404 -> new WebFailure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
            case 405 -> new WebFailure(405, "METHOD_NOT_ALLOWED", "HTTP",
                    "The request method is not allowed");
            case 413 -> new WebFailure(413, "REQUEST_TOO_LARGE", "HTTP", "The request body is too large");
            case 415 -> new WebFailure(415, "CONTENT_TYPE_UNSUPPORTED", "HTTP",
                    "The request content type is not supported");
            default -> new WebFailure(500, "INTERNAL_ERROR", "WEB", "The request failed safely");
        };
    }

    public static WebException failure(int status, String code, String stage, String message) {
        return new WebException(new WebFailure(status, code, stage, message));
    }

    public record WebFailure(int status, String code, String stage, String message) {}

    public static final class WebException extends RuntimeException {
        private final WebFailure failure;

        private WebException(WebFailure failure) {
            super(failure.message());
            this.failure = failure;
        }

        private WebFailure failure() {
            return failure;
        }
    }
}

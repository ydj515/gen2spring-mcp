package io.gen2spring.mcp.app.web.error;

import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationException;
import io.gen2spring.mcp.domain.error.GeneratorException;
import io.gen2spring.mcp.app.web.job.GenerationJobManager;
import io.gen2spring.mcp.app.web.job.JobWorkspace;
import io.gen2spring.mcp.application.hosted.job.HostedJobFailure;
import io.gen2spring.mcp.app.web.hosted.HostedArtifactController;
import io.gen2spring.mcp.app.web.hosted.HostedJobController;
import io.gen2spring.mcp.app.web.hosted.HostedSubmissionService;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@org.springframework.stereotype.Component
public final class WebErrorMapper {
    public WebFailure map(Throwable failure) {
        if (failure instanceof WebException exception) {
            return exception.failure();
        }
        if (failure instanceof HostedJobController.HostedResourceNotFound) {
            return new WebFailure(404, "RESOURCE_NOT_FOUND", "HOSTED_LOOKUP", "The hosted resource was not found");
        }
        if (failure instanceof HostedJobFailure hosted) {
            return switch (hosted.code()) {
                case NOT_FOUND -> new WebFailure(404, "RESOURCE_NOT_FOUND", "HOSTED_LOOKUP", hosted.getMessage());
                case IDEMPOTENCY_CONFLICT -> new WebFailure(409, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
                case CAPACITY_EXCEEDED -> new WebFailure(429, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
                case INVALID_REQUEST -> new WebFailure(400, hosted.code().name(), "JOB_CREATE", hosted.getMessage());
            };
        }
        if (failure instanceof HostedSubmissionService.HostedSubmissionFailure) {
            return new WebFailure(400, "HOSTED_SUBMISSION_INVALID", "HOSTED_SUBMIT", "The hosted request is invalid");
        }
        if (failure instanceof HostedAccountResolver.HostedAuthenticationFailure) {
            return new WebFailure(401, "AUTHENTICATION_REQUIRED", "OIDC", "Hosted authentication is required");
        }
        if (failure instanceof HostedArtifactController.HostedArtifactFailure) {
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
        if (failure instanceof NoResourceFoundException) {
            return new WebFailure(404, "ROUTE_NOT_FOUND", "HTTP", "The route was not found");
        }
        if (failure instanceof GenerationJobManager.GenerationCapacityException) {
            return new WebFailure(429, "GENERATION_CAPACITY_EXCEEDED", "JOB_CREATE",
                    "The generation capacity is exhausted");
        }
        if (failure instanceof GenerationJobManager.JobNotFoundException) {
            return new WebFailure(404, "JOB_NOT_FOUND", "JOB_LOOKUP", "The generation job was not found");
        }
        if (failure instanceof GenerationJobManager.JobStateException) {
            return new WebFailure(409, "JOB_STATE_INVALID", "JOB_DELETE",
                    "The generation job state does not allow this request");
        }
        if (failure instanceof GenerationJobManager.ArtifactUnavailableException) {
            return new WebFailure(409, "ARTIFACT_UNAVAILABLE", "ARTIFACT_READ",
                    "The requested artifact is unavailable");
        }
        if (failure instanceof JobWorkspace.WorkspaceCreationException) {
            return new WebFailure(500, "WORKSPACE_CREATE_FAILED", "JOB_CREATE",
                    "The generation workspace could not be created");
        }
        if (failure instanceof GenerationConfigurationException exception) {
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

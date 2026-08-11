package io.gen2spring.mcp.web;

import io.gen2spring.mcp.application.GenerationConfigurationException;
import io.gen2spring.mcp.domain.error.GeneratorException;

final class WebErrorMapper {
    WebFailure map(Throwable failure) {
        if (failure instanceof WebException exception) {
            return exception.failure();
        }
        if (failure instanceof RequestGuard.RequestRejectedException) {
            return new WebFailure(403, "REQUEST_FORBIDDEN", "HTTP", "The request is not allowed");
        }
        if (failure instanceof BoundedBodyReader.PayloadTooLargeException) {
            return new WebFailure(413, "REQUEST_TOO_LARGE", "HTTP", "The request body is too large");
        }
        if (failure instanceof SpecificationStore.InvalidSpecificationNameException) {
            return new WebFailure(400, "SPECIFICATION_NAME_INVALID", "SPEC_STORE",
                    "The specification name is invalid");
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
        if (failure instanceof GenerationConfigurationException exception) {
            return new WebFailure(400, "CONFIGURATION_INVALID", "CONFIG_PARSE", exception.getMessage());
        }
        if (failure instanceof GeneratorException exception) {
            return new WebFailure(422, exception.code().name(), exception.stage(), exception.safeMessage());
        }
        return new WebFailure(500, "INTERNAL_ERROR", "WEB", "The request failed safely");
    }

    static WebException failure(int status, String code, String stage, String message) {
        return new WebException(new WebFailure(status, code, stage, message));
    }

    record WebFailure(int status, String code, String stage, String message) {}

    static final class WebException extends RuntimeException {
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

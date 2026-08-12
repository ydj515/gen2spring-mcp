package io.gen2spring.mcp.application.hosted.job;

public final class HostedJobFailure extends RuntimeException {
    private final Code code;

    HostedJobFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        CAPACITY_EXCEEDED
    }
}

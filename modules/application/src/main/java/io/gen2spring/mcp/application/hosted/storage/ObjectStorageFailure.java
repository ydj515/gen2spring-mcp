package io.gen2spring.mcp.application.hosted.storage;

public final class ObjectStorageFailure extends RuntimeException {
    private final Code code;

    public ObjectStorageFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ObjectStorageFailure(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        REJECTED,
        NOT_FOUND,
        UNAVAILABLE
    }
}

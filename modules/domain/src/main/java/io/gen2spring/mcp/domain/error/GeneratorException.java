package io.gen2spring.mcp.domain.error;

public final class GeneratorException extends RuntimeException {
    private final GeneratorErrorCode code;
    private final String stage;
    private final String safeMessage;

    private GeneratorException(GeneratorErrorCode code, String stage, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.code = code;
        this.stage = stage;
        this.safeMessage = safeMessage;
    }

    public static GeneratorException user(GeneratorErrorCode code, String stage, String safeMessage) {
        return new GeneratorException(code, stage, safeMessage, null);
    }

    public static GeneratorException user(GeneratorErrorCode code, String stage, String safeMessage, Throwable cause) {
        return new GeneratorException(code, stage, safeMessage, cause);
    }

    public static GeneratorException system(GeneratorErrorCode code, String stage, String safeMessage, Throwable cause) {
        return new GeneratorException(code, stage, safeMessage, cause);
    }

    public GeneratorErrorCode code() {
        return code;
    }

    public String stage() {
        return stage;
    }

    public String safeMessage() {
        return safeMessage;
    }
}

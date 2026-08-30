package io.gen2spring.mcp.app.fetch.fetching;

public final class FetchFailure extends RuntimeException {
    private final boolean retryable;

    public FetchFailure() {
        this(false);
    }

    FetchFailure(boolean retryable) {
        super("URL import fetch failed", null, false, false);
        this.retryable = retryable;
    }

    boolean retryable() {
        return retryable;
    }
}

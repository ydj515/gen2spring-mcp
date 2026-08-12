package io.gen2spring.mcp.app.fetch;

public final class FetchFailure extends RuntimeException {
    public FetchFailure() {
        super("URL import fetch failed", null, false, false);
    }
}

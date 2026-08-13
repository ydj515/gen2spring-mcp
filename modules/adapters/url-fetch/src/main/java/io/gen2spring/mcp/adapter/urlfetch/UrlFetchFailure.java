package io.gen2spring.mcp.adapter.urlfetch;

public final class UrlFetchFailure extends RuntimeException {
    public UrlFetchFailure() {
        super("URL import gateway request failed", null, false, false);
    }
}

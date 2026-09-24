package io.gen2spring.mcp.application.managed.runtime.port.out;

import io.gen2spring.mcp.application.managed.runtime.IssuedRuntimeToken;
import io.gen2spring.mcp.application.managed.runtime.RuntimeTokenDigest;

public interface RuntimeTokenCodec {
    IssuedRuntimeToken issue();

    boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest);

    default RuntimeTokenDigest digest(String presentedToken) {
        throw new UnsupportedOperationException("Runtime token digest is unavailable");
    }
}

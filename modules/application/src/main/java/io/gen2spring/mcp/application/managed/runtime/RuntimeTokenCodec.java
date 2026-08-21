package io.gen2spring.mcp.application.managed.runtime;

public interface RuntimeTokenCodec {
    IssuedRuntimeToken issue();

    boolean matches(String presentedToken, RuntimeTokenDigest persistedDigest);
}

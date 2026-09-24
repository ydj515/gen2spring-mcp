package io.gen2spring.mcp.app.web.application.local.port.out;

import io.gen2spring.mcp.app.web.application.local.result.StoredSpecification;
import java.io.InputStream;

/** Stores a bounded specification for the lifetime of a local generation request. */
public interface SpecificationStorage {
    StoredSpecification store(String name, InputStream source);

    StoredSpecification retain(String identifier);

    void release(String identifier);

}

package io.gen2spring.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.GeneratorApplication;
import java.nio.file.Path;

public final class WebApplicationFactory {
    private WebApplicationFactory() {}

    public static LocalWebServer create(int port) {
        GeneratorApplication application = GeneratorApplication.defaults();
        SpecificationStore store = new SpecificationStore(
                Path.of(System.getProperty("java.io.tmpdir")), application.analyzer());
        try {
            return new LocalWebServer(port, application, store, new ObjectMapper());
        } catch (Error | RuntimeException failure) {
            store.close();
            throw failure;
        }
    }
}

package io.gen2spring.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.GeneratorApplication;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public final class WebApplicationFactory {
    private WebApplicationFactory() {}

    public static LocalWebServer create(int port) {
        GeneratorApplication application = GeneratorApplication.defaults();
        SpecificationStore store = new SpecificationStore(
                Path.of(System.getProperty("java.io.tmpdir")), application.analyzer());
        GenerationJobManager jobs = new GenerationJobManager(
                Path.of(System.getProperty("java.io.tmpdir")),
                application.pipeline()::generate,
                Clock.systemUTC(),
                Duration.ofHours(1),
                ignored -> {});
        try {
            return new LocalWebServer(port, application, store, jobs, new ObjectMapper());
        } catch (Error | RuntimeException failure) {
            jobs.close();
            store.close();
            throw failure;
        }
    }
}

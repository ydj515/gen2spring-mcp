package io.gen2spring.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.GeneratorApplication;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

public final class WebApplicationFactory {
    private WebApplicationFactory() {}

    public static LocalWebServer create(int port) {
        GeneratorApplication application = GeneratorApplication.defaults();
        Path temporaryParent = privateTemporaryParent();
        SpecificationStore store = new SpecificationStore(
                temporaryParent, application.analyzer());
        GenerationJobManager jobs = new GenerationJobManager(
                temporaryParent,
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

    static Path privateTemporaryParent() {
        try {
            return Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw WebErrorMapper.failure(500, "WORKSPACE_CREATE_FAILED", "WEB_START",
                    "The private workspace parent could not be verified");
        }
    }
}

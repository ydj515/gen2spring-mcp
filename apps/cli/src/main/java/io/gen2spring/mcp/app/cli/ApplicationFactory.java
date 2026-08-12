package io.gen2spring.mcp.app.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;

public final class ApplicationFactory {
    private ApplicationFactory() {}

    public static CliApplication create() {
        GeneratorRuntime application = GeneratorRuntime.defaults();
        ObjectMapper json = new ObjectMapper();
        return new CliApplication(
                new CommandLine(),
                new GenerationConfigurationReader(application.configurationParser()),
                application.analyzer(),
                application.pipeline()::generate,
                application.profiles(),
                json);
    }
}

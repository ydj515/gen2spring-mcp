package io.gen2spring.mcp.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.GeneratorApplication;

public final class ApplicationFactory {
    private ApplicationFactory() {}

    public static CliApplication create() {
        GeneratorApplication application = GeneratorApplication.defaults();
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

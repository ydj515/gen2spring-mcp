package io.gen2spring.mcp.app.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.gen2spring.mcp.app.cli.application.CliUseCases;
import io.gen2spring.mcp.app.cli.infrastructure.file.GenerationConfigurationReader;
import io.gen2spring.mcp.app.cli.infrastructure.file.LocalCliFiles;
import io.gen2spring.mcp.app.cli.presentation.CliApplication;
import io.gen2spring.mcp.app.cli.presentation.CommandLine;
import io.gen2spring.mcp.bootstrap.GeneratorRuntime;

public final class ApplicationFactory {
    private ApplicationFactory() {}

    public static CliApplication create() {
        GeneratorRuntime runtime = GeneratorRuntime.defaults();
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        CliUseCases useCases = new CliUseCases(
                new GenerationConfigurationReader(runtime.configurationParser()),
                new LocalCliFiles(json), runtime.analyzer(), runtime.pipeline()::generate, runtime.profiles());
        return new CliApplication(new CommandLine(), useCases, json);
    }
}

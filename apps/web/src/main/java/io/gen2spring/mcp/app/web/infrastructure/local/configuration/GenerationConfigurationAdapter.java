package io.gen2spring.mcp.app.web.infrastructure.local.configuration;

import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationParser;
import io.gen2spring.mcp.adapter.configuration.GenerationConfigurationException;
import io.gen2spring.mcp.app.web.application.local.exception.LocalConfigurationFailure;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationConfigurationDecoder;
import io.gen2spring.mcp.application.command.GenerationCommand;
import java.util.Objects;

public final class GenerationConfigurationAdapter implements GenerationConfigurationDecoder {
    private final GenerationConfigurationParser parser;

    public GenerationConfigurationAdapter(GenerationConfigurationParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public GenerationCommand decode(byte[] bytes) {
        try {
            return parser.parseJson(bytes);
        } catch (GenerationConfigurationException failure) {
            throw new LocalConfigurationFailure(failure.getMessage(), failure);
        }
    }
}

package io.gen2spring.mcp.cli;

import io.gen2spring.mcp.application.GenerationConfigurationException;
import io.gen2spring.mcp.application.GenerationConfigurationParser;
import io.gen2spring.mcp.application.command.GenerationCommand;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import java.nio.file.Path;
import java.util.Objects;

public final class GenerationConfigurationReader {
    static final int MAX_BYTES = GenerationConfigurationParser.MAX_BYTES;

    private final GenerationConfigurationParser parser;
    private final LocalPathBoundary pathBoundary;

    public GenerationConfigurationReader() {
        this(CompatibilityProfileRegistry.defaults());
    }

    public GenerationConfigurationReader(CompatibilityProfileRegistry profiles) {
        this(new GenerationConfigurationParser(profiles), new LocalPathBoundary());
    }

    GenerationConfigurationReader(LocalPathBoundary pathBoundary) {
        this(new GenerationConfigurationParser(), pathBoundary);
    }

    GenerationConfigurationReader(GenerationConfigurationParser parser) {
        this(parser, new LocalPathBoundary());
    }

    private GenerationConfigurationReader(
            GenerationConfigurationParser parser,
            LocalPathBoundary pathBoundary) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.pathBoundary = Objects.requireNonNull(pathBoundary, "pathBoundary");
    }

    GenerationConfigurationReader withProfiles(CompatibilityProfileRegistry profiles) {
        return new GenerationConfigurationReader(new GenerationConfigurationParser(profiles), pathBoundary);
    }

    public GenerationCommand read(Path configuration) {
        byte[] bytes = readBoundedRegularFile(configuration);
        try {
            return parser.parseYaml(bytes);
        } catch (GenerationConfigurationException exception) {
            throw new CliConfigurationException(exception.getMessage(), exception);
        }
    }

    private byte[] readBoundedRegularFile(Path configuration) {
        try {
            return pathBoundary.regularFile(configuration, "Generation configuration").readBounded(MAX_BYTES);
        } catch (LocalPathBoundary.PathBoundaryException exception) {
            throw new CliConfigurationException(exception.getMessage(), exception);
        }
    }
}

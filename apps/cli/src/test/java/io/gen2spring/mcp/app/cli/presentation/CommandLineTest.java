package io.gen2spring.mcp.app.cli.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommandLineTest {
    private final CommandLine commandLine = new CommandLine();

    @Test
    void parsesEverySupportedCommand() {
        var profiles = commandLine.parse(new String[] {"profiles"});
        assertEquals(CommandLine.Command.PROFILES, profiles.command());
        assertNull(profiles.specification());

        var inspect = commandLine.parse(new String[] {
                "inspect", "--spec", "weather.yaml", "--output", "analysis.json"
        });
        assertEquals(CommandLine.Command.INSPECT, inspect.command());
        assertEquals(Path.of("weather.yaml"), inspect.specification());
        assertEquals(Path.of("analysis.json"), inspect.output());

        var generate = commandLine.parse(new String[] {
                "generate", "--spec", "weather.yaml", "--config", "generation.yaml",
                "--output", "weather-mcp"
        });
        assertEquals(CommandLine.Command.GENERATE, generate.command());
        assertEquals(Path.of("generation.yaml"), generate.configuration());
    }

    @Test
    void rejectsMissingDuplicateUnknownEmptyAndPositionalArguments() {
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[0]));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {"unknown"}));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {"profiles", "extra"}));
        assertThrows(CliUsageException.class,
                () -> commandLine.parse(new String[] {"inspect", "--spec", "weather.yaml"}));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {
                "inspect", "--spec", "weather.yaml", "--spec", "other.yaml",
                "--output", "analysis.json"
        }));
        assertThrows(CliUsageException.class,
                () -> commandLine.parse(new String[] {"inspect", "--unknown", "x"}));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {
                "inspect", "--spec", "", "--output", "analysis.json"
        }));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {
                "inspect", "--spec", "--output", "analysis.json"
        }));
        assertThrows(CliUsageException.class, () -> commandLine.parse(new String[] {
                "generate", "--spec", "weather.yaml", "loose", "--config", "generation.yaml",
                "--output", "weather-mcp"
        }));
    }
}

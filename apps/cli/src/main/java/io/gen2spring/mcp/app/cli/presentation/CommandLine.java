package io.gen2spring.mcp.app.cli.presentation;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class CommandLine {
    public enum Command { PROFILES, INSPECT, GENERATE }

    public record Parsed(
            Command command,
            Path specification,
            Path configuration,
            Path output) {}

    public Parsed parse(String[] arguments) {
        if (arguments == null || arguments.length == 0 || arguments[0] == null) {
            throw usage();
        }
        return switch (arguments[0]) {
            case "profiles" -> profiles(arguments);
            case "inspect" -> inspect(arguments);
            case "generate" -> generate(arguments);
            default -> throw usage();
        };
    }

    private Parsed profiles(String[] arguments) {
        if (arguments.length != 1) {
            throw usage();
        }
        return new Parsed(Command.PROFILES, null, null, null);
    }

    private Parsed inspect(String[] arguments) {
        Map<String, String> flags = flags(arguments, Set.of("--spec", "--output"));
        requireExact(flags, Set.of("--spec", "--output"));
        return new Parsed(Command.INSPECT, path(flags.get("--spec")), null, path(flags.get("--output")));
    }

    private Parsed generate(String[] arguments) {
        Map<String, String> flags = flags(arguments, Set.of("--spec", "--config", "--output"));
        requireExact(flags, Set.of("--spec", "--config", "--output"));
        return new Parsed(Command.GENERATE, path(flags.get("--spec")), path(flags.get("--config")),
                path(flags.get("--output")));
    }

    private Map<String, String> flags(String[] arguments, Set<String> permitted) {
        if ((arguments.length - 1) % 2 != 0) {
            throw usage();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            String flag = arguments[index];
            String value = arguments[index + 1];
            if (flag == null || !permitted.contains(flag) || value == null || value.isBlank()
                    || value.startsWith("--") || values.putIfAbsent(flag, value) != null) {
                throw usage();
            }
        }
        return values;
    }

    private void requireExact(Map<String, String> actual, Set<String> required) {
        if (!actual.keySet().equals(required)) {
            throw usage();
        }
    }

    private Path path(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException exception) {
            throw usage();
        }
    }

    private CliUsageException usage() {
        return new CliUsageException("Invalid command. Use profiles, inspect, or generate with the documented flags");
    }
}

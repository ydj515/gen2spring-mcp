package io.gen2spring.mcp.validation.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EnvironmentProbeProcess {
    private EnvironmentProbeProcess() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        List<String> lines = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String name = args[index];
            String value = System.getenv(name);
            if (value != null) {
                lines.add(name + "=" + value);
            }
        }
        Files.write(output, lines, StandardCharsets.UTF_8);
    }
}

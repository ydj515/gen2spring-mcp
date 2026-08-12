package io.gen2spring.mcp.app.cli;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class Main {
    private Main() {}

    public static void main(String[] arguments) {
        var stdout = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        var stderr = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        int exitCode = ApplicationFactory.create().run(arguments, stdout, stderr);
        System.exit(exitCode);
    }
}

package io.gen2spring.mcp.validation.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class StartupDelayApplication {
    private StartupDelayApplication() {}

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("app.pid"), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(60_000);
    }
}

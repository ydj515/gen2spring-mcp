package io.gen2spring.mcp.adapter.validation.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PortBindFailureApplication {
    private PortBindFailureApplication() {}

    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("app.pid"), Long.toString(ProcessHandle.current().pid()));
        Path competitorPort = Path.of("competitor-port");
        if (Files.exists(competitorPort)) {
            String port = Files.readString(competitorPort).trim();
            System.out.println("Tomcat started on port " + port + " (http) with context path '/'");
            System.out.flush();
            Path used = Path.of("competitor-used");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
            while (!Files.exists(used) && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            return;
        }
        Thread.sleep(300);
        throw new IllegalStateException("simulated child startup failure");
    }
}

package io.gen2spring.mcp.adapter.validation.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SleepingProcess {
    private SleepingProcess() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "sleep" : args[0];
        if ("large".equals(mode)) {
            byte[] chunk = "x".repeat(8_192).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int index = 0; index < 64; index++) {
                System.out.write(chunk);
                System.err.write(chunk);
            }
            return;
        }
        if ("secret".equals(mode)) {
            System.out.print("Authorization: Bearer secret-value");
            System.err.print("apiKey=secret-value");
            return;
        }
        if ("child".equals(mode)) {
            Path pidFile = Path.of(args[1]);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process child = new ProcessBuilder(
                    java, "-cp", System.getProperty("java.class.path"), SleepingProcess.class.getName(), "sleep")
                    .start();
            Files.writeString(pidFile, Long.toString(child.pid()));
        }
        if (args.length > 1 && "pid".equals(args[0])) {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            System.out.println("READY");
            System.out.flush();
            System.err.println("READY");
            System.err.flush();
        }
        Thread.sleep(60_000);
    }
}

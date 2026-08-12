package io.gen2spring.mcp.adapter.container;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface DockerCommandRunner {
    CommandResult run(List<String> argv, Duration timeout) throws InterruptedException;

    record CommandResult(int exitCode, String output) {
        public CommandResult {
            if (exitCode < 0 || output == null || output.length() > 65_536) {
                throw new IllegalArgumentException("Docker command result is invalid");
            }
        }

        @Override
        public String toString() {
            return "CommandResult[exitCode=" + exitCode + ", redacted]";
        }
    }
}

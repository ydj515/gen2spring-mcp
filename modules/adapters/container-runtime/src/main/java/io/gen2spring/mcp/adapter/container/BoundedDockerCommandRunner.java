package io.gen2spring.mcp.adapter.container;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class BoundedDockerCommandRunner implements DockerCommandRunner {
    private static final int MAX_OUTPUT_BYTES = 65_536;

    @Override
    public CommandResult run(List<String> argv, Duration timeout) throws InterruptedException {
        List<String> command = validate(argv);
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(11)) > 0) {
            throw new IllegalArgumentException("Docker command is invalid");
        }
        Process process = null;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process started = process;
            Future<byte[]> output = executor.submit(() -> readBounded(started.getInputStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                output.cancel(true);
                throw failed();
            }
            byte[] bytes;
            try {
                bytes = output.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                terminate(process);
                throw interrupted;
            } catch (ExecutionException execution) {
                terminate(process);
                if (execution.getCause() instanceof Error fatal) {
                    throw fatal;
                }
                throw failed();
            } catch (Exception failure) {
                terminate(process);
                throw failed();
            }
            return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Error fatal) {
            terminate(process);
            throw fatal;
        } catch (Exception failure) {
            terminate(process);
            throw failed();
        }
    }

    private List<String> validate(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.size() > 128) {
            throw new IllegalArgumentException("Docker command is invalid");
        }
        List<String> copied = new ArrayList<>(argv.size());
        for (String value : argv) {
            if (value == null
                    || value.isEmpty()
                    || value.length() > 4096
                    || value.chars().anyMatch(character -> character == 0 || character == '\r' || character == '\n')) {
                throw new IllegalArgumentException("Docker command is invalid");
            }
            copied.add(value);
        }
        return List.copyOf(copied);
    }

    private byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            total += read;
            if (total > MAX_OUTPUT_BYTES) {
                throw failed();
            }
            output.write(buffer, 0, read);
        }
    }

    private void terminate(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().toList().reversed();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private SandboxRuntimeFailure failed() {
        return new SandboxRuntimeFailure();
    }
}

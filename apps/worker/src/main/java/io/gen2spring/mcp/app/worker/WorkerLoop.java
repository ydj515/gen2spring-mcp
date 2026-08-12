package io.gen2spring.mcp.app.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerLoop implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final WorkerReadiness readiness;
    private final Poller poller;
    private final Duration idleDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;

    WorkerLoop(WorkerReadiness readiness, Poller poller, Duration idleDelay) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.poller = Objects.requireNonNull(poller, "poller");
        this.idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        if (idleDelay.isZero()
                || idleDelay.isNegative()
                || idleDelay.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    synchronized void start() {
        if (running.get()) {
            return;
        }
        readiness.verify();
        running.set(true);
        thread = Thread.ofPlatform().name("gen2spring-hosted-worker").unstarted(this::run);
        thread.start();
    }

    boolean running() {
        Thread current = thread;
        return running.get() && current != null && current.isAlive();
    }

    private void run() {
        try {
            while (running.get()) {
                boolean worked;
                try {
                    worked = poller.poll();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Error fatal) {
                    break;
                } catch (RuntimeException failure) {
                    worked = false;
                }
                if (!worked && running.get()) {
                    try {
                        Thread.sleep(idleDelay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        Thread current = thread;
        if (current == null) {
            return;
        }
        current.interrupt();
        try {
            current.join(CLOSE_TIMEOUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hosted worker shutdown was interrupted", null);
        }
        if (current.isAlive()) {
            throw new IllegalStateException("Hosted worker shutdown timed out", null);
        }
    }

    @FunctionalInterface
    interface Poller {
        boolean poll() throws InterruptedException;
    }
}

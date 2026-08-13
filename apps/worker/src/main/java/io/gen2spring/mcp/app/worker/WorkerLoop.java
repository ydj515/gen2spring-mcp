package io.gen2spring.mcp.app.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerLoop implements AutoCloseable {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final WorkerReadiness readiness;
    private final Poller poller;
    private final Duration idleDelay;
    private final Runnable heartbeat;
    private final Duration heartbeatInterval;
    private final Runnable maintenance;
    private final Duration maintenanceInterval;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread pollThread;
    private volatile Thread heartbeatThread;
    private volatile Thread maintenanceThread;

    WorkerLoop(WorkerReadiness readiness, Poller poller, Duration idleDelay) {
        this(readiness, poller, idleDelay, () -> {}, Duration.ofSeconds(10), () -> {}, Duration.ofSeconds(10));
    }

    WorkerLoop(
            WorkerReadiness readiness,
            Poller poller,
            Duration idleDelay,
            Runnable heartbeat,
            Duration heartbeatInterval) {
        this(readiness, poller, idleDelay, heartbeat, heartbeatInterval, () -> {}, Duration.ofSeconds(10));
    }

    WorkerLoop(
            WorkerReadiness readiness,
            Poller poller,
            Duration idleDelay,
            Runnable heartbeat,
            Duration heartbeatInterval,
            Runnable maintenance,
            Duration maintenanceInterval) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.poller = Objects.requireNonNull(poller, "poller");
        this.idleDelay = Objects.requireNonNull(idleDelay, "idleDelay");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
        this.maintenanceInterval = Objects.requireNonNull(maintenanceInterval, "maintenanceInterval");
        if (idleDelay.isZero()
                || idleDelay.isNegative()
                || idleDelay.compareTo(Duration.ofSeconds(10)) > 0
                || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(Duration.ofMinutes(1)) > 0
                || maintenanceInterval.isZero()
                || maintenanceInterval.isNegative()
                || maintenanceInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    synchronized void start() {
        if (running.get()) {
            return;
        }
        readiness.verify();
        running.set(true);
        pollThread = Thread.ofPlatform().name("gen2spring-hosted-worker").unstarted(this::runPoller);
        heartbeatThread = Thread.ofPlatform()
                .name("gen2spring-hosted-heartbeat")
                .unstarted(() -> runScheduled(heartbeat, heartbeatInterval));
        maintenanceThread = Thread.ofPlatform()
                .name("gen2spring-hosted-maintenance")
                .unstarted(() -> runScheduled(maintenance, maintenanceInterval));
        pollThread.start();
        heartbeatThread.start();
        maintenanceThread.start();
    }

    boolean running() {
        Thread poll = pollThread;
        Thread pulse = heartbeatThread;
        Thread upkeep = maintenanceThread;
        return running.get()
                && poll != null && poll.isAlive()
                && pulse != null && pulse.isAlive()
                && upkeep != null && upkeep.isAlive();
    }

    private void runPoller() {
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
            interrupt(heartbeatThread);
            interrupt(maintenanceThread);
        }
    }

    private void runScheduled(Runnable task, Duration interval) {
        try {
            while (running.get()) {
                try {
                    task.run();
                } catch (Error fatal) {
                    running.set(false);
                    interrupt(pollThread);
                    break;
                } catch (RuntimeException ignored) {
                    // Maintenance is retried at the next bounded interval.
                }
                if (running.get()) {
                    try {
                        Thread.sleep(interval);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            running.set(false);
            interrupt(pollThread);
            interrupt(heartbeatThread);
            interrupt(maintenanceThread);
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        Thread poll = pollThread;
        Thread pulse = heartbeatThread;
        Thread upkeep = maintenanceThread;
        if (poll == null && pulse == null && upkeep == null) {
            return;
        }
        interrupt(poll);
        interrupt(pulse);
        interrupt(upkeep);
        long deadline = System.nanoTime() + CLOSE_TIMEOUT.toNanos();
        try {
            join(poll, deadline);
            join(pulse, deadline);
            join(upkeep, deadline);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Hosted worker shutdown was interrupted", null);
        }
        if ((poll != null && poll.isAlive())
                || (pulse != null && pulse.isAlive())
                || (upkeep != null && upkeep.isAlive())) {
            throw new IllegalStateException("Hosted worker shutdown timed out", null);
        }
    }

    private void join(Thread thread, long deadline) throws InterruptedException {
        if (thread == null) return;
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
            thread.join(Duration.ofNanos(remaining));
        }
    }

    private void interrupt(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    @FunctionalInterface
    interface Poller {
        boolean poll() throws InterruptedException;
    }
}

package io.gen2spring.mcp.app.worker.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class WorkerExecutionTest {
    @Test
    void readinessRunsEveryDependencyProbeAndFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        WorkerReadiness ready = new WorkerReadiness(List.of(
                calls::incrementAndGet,
                calls::incrementAndGet,
                calls::incrementAndGet));
        ready.verify();
        assertEquals(3, calls.get());

        WorkerReadiness failed = new WorkerReadiness(List.of(
                calls::incrementAndGet,
                () -> { throw new IllegalStateException("private-marker"); },
                calls::incrementAndGet));
        WorkerStartupFailure failure = assertThrows(WorkerStartupFailure.class, failed::verify);
        assertEquals("Hosted worker dependencies are unavailable", failure.getMessage());
        assertFalse(failure.toString().contains("private-marker"));
    }

    @Test
    void pollingStartsOnlyAfterReadinessAndStopsWithinTheBound() throws Exception {
        AtomicInteger readinessCalls = new AtomicInteger();
        AtomicInteger pollCalls = new AtomicInteger();
        WorkerLoop loop = new WorkerLoop(
                new WorkerReadiness(List.of(readinessCalls::incrementAndGet)),
                () -> {
                    pollCalls.incrementAndGet();
                    return false;
                },
                Duration.ofMillis(10));

        try {
            loop.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (pollCalls.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        } finally {
            loop.close();
        }

        assertEquals(1, readinessCalls.get());
        assertTrue(pollCalls.get() >= 2);
        assertFalse(loop.running());

        WorkerLoop rejected = new WorkerLoop(
                new WorkerReadiness(List.of(() -> { throw new IllegalStateException("private-marker"); })),
                () -> { throw new AssertionError("polling started before readiness"); },
                Duration.ofMillis(10));
        try {
            assertThrows(WorkerStartupFailure.class, rejected::start);
        } finally {
            rejected.close();
        }
        assertFalse(rejected.running());
    }

    @Test
    void heartbeatContinuesWhileJobPollingIsBlocked() throws Exception {
        CountDownLatch polling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger heartbeatCalls = new AtomicInteger();
        WorkerLoop loop = new WorkerLoop(
                new WorkerReadiness(List.of(() -> {})),
                () -> {
                    polling.countDown();
                    release.await();
                    return true;
                },
                Duration.ofMillis(10),
                heartbeatCalls::incrementAndGet,
                Duration.ofMillis(10));

        try {
            loop.start();
            assertTrue(polling.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (heartbeatCalls.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(heartbeatCalls.get() >= 2);
        } finally {
            release.countDown();
            loop.close();
        }
    }

    @Test
    void heartbeatContinuesWhileRetentionMaintenanceIsBlocked() throws Exception {
        CountDownLatch maintenanceStarted = new CountDownLatch(1);
        CountDownLatch releaseMaintenance = new CountDownLatch(1);
        AtomicInteger heartbeatCalls = new AtomicInteger();
        WorkerLoop loop = new WorkerLoop(
                new WorkerReadiness(List.of(() -> {})),
                () -> false,
                Duration.ofMillis(10),
                heartbeatCalls::incrementAndGet,
                Duration.ofMillis(10),
                () -> {
                    maintenanceStarted.countDown();
                    try {
                        releaseMaintenance.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                Duration.ofMillis(10));

        try {
            loop.start();
            assertTrue(maintenanceStarted.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            while (heartbeatCalls.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(heartbeatCalls.get() >= 2);
        } finally {
            releaseMaintenance.countDown();
            loop.close();
        }
    }
}

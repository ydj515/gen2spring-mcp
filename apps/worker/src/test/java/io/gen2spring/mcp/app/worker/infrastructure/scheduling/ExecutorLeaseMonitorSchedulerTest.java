package io.gen2spring.mcp.app.worker.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutorLeaseMonitorSchedulerTest {
    private final ExecutorLeaseMonitorScheduler scheduler = new ExecutorLeaseMonitorScheduler();

    @Test
    void runsPeriodicChecksOnOwnedThreadAndStopsIt() throws Exception {
        CountDownLatch checks = new CountDownLatch(2);
        AtomicReference<Thread> thread = new AtomicReference<>();
        var registration = scheduler.schedule(Duration.ofMillis(10), () -> {
            thread.set(Thread.currentThread());
            checks.countDown();
        });
        try {
            assertTrue(checks.await(5, TimeUnit.SECONDS));
            assertTrue(thread.get().isDaemon());
        } finally {
            assertTrue(registration.stop());
        }
        thread.get().join(1000);
        assertFalse(thread.get().isAlive());
    }

    @Test
    void waitsForTheFirstIntervalAndCancelsBeforeIt() {
        CountDownLatch checks = new CountDownLatch(1);
        var registration = scheduler.schedule(Duration.ofHours(1), checks::countDown);
        assertTrue(registration.stop());
        assertEquals(1, checks.getCount());
    }

    @Test
    void restoresInterruptStatusWhenStoppingIsInterrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var registration = scheduler.schedule(Duration.ofMillis(10), () -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Keep the check active so stopping must enter its interruptible wait.
                }
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            assertFalse(registration.stop());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            release.countDown();
            assertTrue(registration.stop());
        }
    }

    @Test
    void rejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(Duration.ZERO, () -> { }));
        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(Duration.ofSeconds(-1), () -> { }));
    }
}

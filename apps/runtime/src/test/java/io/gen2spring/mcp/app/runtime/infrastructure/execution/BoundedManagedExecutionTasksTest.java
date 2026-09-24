package io.gen2spring.mcp.app.runtime.infrastructure.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gen2spring.mcp.application.managed.execution.ManagedExecutionLimits;
import io.gen2spring.mcp.application.managed.execution.ManagedToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class BoundedManagedExecutionTasksTest {
    private static final ManagedExecutionLimits LIMITS = new ManagedExecutionLimits(Duration.ofSeconds(1), 1, 1);

    @Test
    void boundsCapacityAndCancelsQueuedWorkOnClose() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var tasks = new BoundedManagedExecutionTasks(LIMITS)) {
            var active = tasks.submit(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    interrupted.countDown();
                    throw failure;
                }
                return result();
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            var queued = tasks.submit(BoundedManagedExecutionTasksTest::result);
            assertThrows(RejectedExecutionException.class, () -> tasks.submit(BoundedManagedExecutionTasksTest::result));
            tasks.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertTrue(active.isDone());
            assertTrue(queued.isCancelled());
            assertThrows(RejectedExecutionException.class, () -> tasks.submit(BoundedManagedExecutionTasksTest::result));
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationInterruptsTheRunningCall() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (var tasks = new BoundedManagedExecutionTasks(LIMITS)) {
            var future = tasks.submit(() -> {
                running.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException failure) {
                    interrupted.countDown();
                    throw failure;
                }
                return result();
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void preservesTaskFailuresAndSuccessfulValues() throws Exception {
        try (var tasks = new BoundedManagedExecutionTasks(LIMITS)) {
            ManagedToolResult expected = result();
            assertSame(expected, tasks.submit(() -> expected).get(2, TimeUnit.SECONDS));
            AssertionError fatal = new AssertionError("fatal");
            var failed = tasks.submit(() -> { throw fatal; });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS));
            assertSame(fatal, failure.getCause());
        }
    }

    @Test
    void restoresCallerInterruptionDuringShutdown() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var tasks = new BoundedManagedExecutionTasks(LIMITS)) {
            tasks.submit(() -> {
                running.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return result();
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class, tasks::close);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
                release.countDown();
            }
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static ManagedToolResult result() {
        return ManagedToolResult.success("{}".getBytes(StandardCharsets.UTF_8));
    }
}

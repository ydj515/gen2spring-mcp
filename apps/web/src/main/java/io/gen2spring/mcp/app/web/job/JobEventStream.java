package io.gen2spring.mcp.app.web.job;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Owns every piece of server-sent event mechanics for job progress: the emitter,
 * the heartbeat, the version cursor, and the worker thread. The per-mode
 * difference arrives as a {@link ChangeFeed} the caller has already bound to a
 * job, and in hosted mode to an account, so this class never learns about
 * identity or ownership.
 */
public final class JobEventStream implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(JobEventStream.class);
    private static final Duration FEED_BUDGET = Duration.ofSeconds(15);
    private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

    private final ThreadPoolExecutor workers;

    public JobEventStream(int maximumStreams) {
        this.workers = new ThreadPoolExecutor(
                0,
                maximumStreams,
                30,
                TimeUnit.SECONDS,
                // SynchronousQueue, not a bounded queue: a stream that cannot start
                // now must be refused so the client falls back immediately. Any
                // queue capacity would instead park it behind a running stream,
                // sending no events while the client waits out its deadline.
                new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "job-event-stream");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @FunctionalInterface
    public interface ChangeFeed {
        /**
         * Blocks until the job changes past {@code sinceVersion}, or the timeout
         * elapses. Returns empty on timeout so the stream can emit a heartbeat.
         */
        Optional<Change> awaitChange(long sinceVersion, Duration timeout) throws InterruptedException;
    }

    public record Change(long version, JsonNode payload, boolean terminal) {
        public Change {
            Objects.requireNonNull(payload, "payload");
        }
    }

    interface EmitterSink {
        void event(String name, JsonNode payload);

        void complete();

        void completeWithError(Throwable failure);
    }

    public SseEmitter open(ChangeFeed feed) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        if (!pump(new SseEmitterSink(emitter), feed)) {
            // Completing immediately drops the client onto its polling fallback.
            emitter.complete();
        }
        return emitter;
    }

    boolean pump(EmitterSink sink, ChangeFeed feed) {
        try {
            workers.execute(() -> run(sink, feed));
            return true;
        } catch (RejectedExecutionException exhausted) {
            LOG.warn("Job event stream capacity is exhausted; the client will fall back to polling");
            sink.complete();
            return false;
        }
    }

    private void run(EmitterSink sink, ChangeFeed feed) {
        long version = 0;
        try {
            while (true) {
                Optional<Change> change = feed.awaitChange(version, FEED_BUDGET);
                if (change.isEmpty()) {
                    // A named event is observable by EventSource. A comment keeps
                    // the connection alive at the transport layer but cannot reset
                    // the browser's liveness watchdog.
                    sink.event("heartbeat", null);
                    continue;
                }
                Change observed = change.get();
                version = observed.version();
                sink.event("snapshot", observed.payload());
                if (observed.terminal()) {
                    sink.event("done", null);
                    sink.complete();
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sink.complete();
        } catch (StreamClosed disconnected) {
            // The client went away. That is an ordinary end, not a failure.
            sink.complete();
        } catch (RuntimeException failure) {
            sink.completeWithError(failure);
        }
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }

    static final class StreamClosed extends RuntimeException {
        StreamClosed() {
            super(null, null, false, false);
        }
    }

    private record SseEmitterSink(SseEmitter emitter) implements EmitterSink {
        @Override
        public void event(String name, JsonNode payload) {
            send(SseEmitter.event().name(name).data(payload == null ? "{}" : payload.toString()));
        }

        private void send(SseEmitter.SseEventBuilder builder) {
            try {
                emitter.send(builder);
            } catch (IOException | IllegalStateException disconnected) {
                throw new StreamClosed();
            }
        }

        @Override
        public void complete() {
            emitter.complete();
        }

        @Override
        public void completeWithError(Throwable failure) {
            emitter.completeWithError(failure);
        }
    }
}

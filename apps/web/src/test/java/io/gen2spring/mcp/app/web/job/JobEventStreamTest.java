package io.gen2spring.mcp.app.web.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JobEventStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsEverySnapshotThenCompletesAtTerminalState() throws Exception {
        try (JobEventStream stream = new JobEventStream(2)) {
            List<JobEventStream.Change> feed = List.of(
                    new JobEventStream.Change(1, json.createObjectNode().put("state", "RUNNING"), false),
                    new JobEventStream.Change(2, json.createObjectNode().put("state", "VALIDATED"), true));
            RecordingSink sink = new RecordingSink();

            assertTrue(stream.pump(sink, (since, timeout) -> feed.stream()
                    .filter(change -> change.version() > since)
                    .findFirst()));

            assertTrue(sink.awaitCompletion(5, TimeUnit.SECONDS));
            assertEquals(List.of("snapshot", "snapshot", "done"), sink.eventNames());
        }
    }

    @Test
    void emitsAHeartbeatWhenTheFeedTimesOut() throws Exception {
        try (JobEventStream stream = new JobEventStream(2)) {
            RecordingSink sink = new RecordingSink();
            AtomicInteger calls = new AtomicInteger();

            assertTrue(stream.pump(sink, (since, timeout) -> {
                if (calls.incrementAndGet() <= 2) {
                    return Optional.empty();
                }
                return Optional.of(new JobEventStream.Change(1, json.createObjectNode(), true));
            }));

            assertTrue(sink.awaitCompletion(5, TimeUnit.SECONDS));
            assertTrue(sink.eventNames().stream().filter("heartbeat"::equals).count() >= 2,
                    "each feed timeout must produce one observable heartbeat event");
        }
    }

    @Test
    void refusesToOpenWhenThePoolIsExhausted() throws Exception {
        try (JobEventStream stream = new JobEventStream(1)) {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RecordingSink blocking = new RecordingSink();
            assertTrue(stream.pump(blocking, (since, timeout) -> {
                occupied.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return Optional.of(new JobEventStream.Change(1, json.createObjectNode(), true));
            }));
            assertTrue(occupied.await(5, TimeUnit.SECONDS));

            RecordingSink rejected = new RecordingSink();
            boolean accepted = stream.pump(rejected, (since, timeout) -> Optional.empty());

            assertFalse(accepted, "an exhausted pool must refuse rather than queue");
            assertTrue(rejected.completed(), "a refused stream must complete so the client falls back");
            release.countDown();
        }
    }

    @Test
    void completesQuietlyWhenTheClientDisconnects() throws Exception {
        try (JobEventStream stream = new JobEventStream(2)) {
            RecordingSink sink = new RecordingSink();
            sink.failOnEvent();

            assertTrue(stream.pump(sink, (since, timeout) ->
                    Optional.of(new JobEventStream.Change(since + 1, json.createObjectNode(), false))));

            assertTrue(sink.awaitCompletion(5, TimeUnit.SECONDS));
            assertFalse(sink.erroredOut(), "a disconnected client is not a stream failure");
        }
    }

    private static final class RecordingSink implements JobEventStream.EmitterSink {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile boolean errored;
        private volatile boolean throwOnEvent;

        void failOnEvent() {
            throwOnEvent = true;
        }

        @Override
        public void event(String name, JsonNode payload) {
            if (throwOnEvent) {
                throw new JobEventStream.StreamClosed();
            }
            events.add(name);
        }

        @Override
        public void complete() {
            done.countDown();
        }

        @Override
        public void completeWithError(Throwable failure) {
            errored = true;
            done.countDown();
        }

        List<String> eventNames() {
            return List.copyOf(events);
        }

        boolean completed() {
            return done.getCount() == 0;
        }

        boolean erroredOut() {
            return errored;
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return done.await(timeout, unit);
        }
    }
}

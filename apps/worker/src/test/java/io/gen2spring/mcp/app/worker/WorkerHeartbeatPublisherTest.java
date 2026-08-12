package io.gen2spring.mcp.app.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gen2spring.mcp.application.hosted.job.WorkerId;
import io.gen2spring.mcp.application.hosted.worker.WorkerHeartbeatStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class WorkerHeartbeatPublisherTest {
    @Test
    void publishesImmediatelyAndThenAtOneBoundedInterval() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        ArrayList<Instant> values = new ArrayList<>();
        WorkerHeartbeatPublisher publisher = new WorkerHeartbeatPublisher(
                new RecordingStore(values), new WorkerId("worker-1"), clock, Duration.ofSeconds(10));

        publisher.publishIfDue();
        clock.value = Instant.EPOCH.plusSeconds(9);
        publisher.publishIfDue();
        clock.value = Instant.EPOCH.plusSeconds(10);
        publisher.publishIfDue();

        assertEquals(java.util.List.of(Instant.EPOCH, Instant.EPOCH.plusSeconds(10)), values);
    }

    private record RecordingStore(ArrayList<Instant> values) implements WorkerHeartbeatStore {
        @Override public void beat(WorkerId worker, Instant observedAt) { values.add(observedAt); }
        @Override public boolean hasRecentHeartbeat(Instant notBefore) { return false; }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        private MutableClock(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}

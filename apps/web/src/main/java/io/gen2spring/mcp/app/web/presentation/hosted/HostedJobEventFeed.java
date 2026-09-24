package io.gen2spring.mcp.app.web.presentation.hosted;

import com.fasterxml.jackson.databind.JsonNode;
import io.gen2spring.mcp.app.web.presentation.stream.JobEventStream;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Hosted jobs run in a separate worker process and record their progress as rows
 * in PostgreSQL, which offers no change notification. This feed therefore re-reads
 * the job on a short interval: server-sent events move polling out of the browser
 * and into the web process rather than eliminating it. Replacing this class with a
 * {@code LISTEN}/{@code NOTIFY} listener is the documented upgrade.
 */
final class HostedJobEventFeed {
    private static final Duration INTERVAL = Duration.ofMillis(400);
    private static final Set<JobStatus> TERMINAL =
            EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final PayloadReader reader;

    HostedJobEventFeed(PayloadReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * Reads one payload. The controller binds the owner and the job into this
     * closure, which is what keeps {@link JobEventStream} free of any notion of
     * identity or ownership.
     */
    @FunctionalInterface
    interface PayloadReader {
        Payload read();
    }

    record Payload(JsonNode node, long highestSequence, JobStatus status) {
        Payload {
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(status, "status");
        }
    }

    Optional<JobEventStream.Change> awaitChange(long sinceVersion, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<JobEventStream.Change> change = read(sinceVersion);
            if (change.isPresent()) {
                return change;
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            TimeUnit.MILLISECONDS.sleep(INTERVAL.toMillis());
        }
    }

    private Optional<JobEventStream.Change> read(long sinceVersion) {
        Payload payload = reader.read();
        boolean terminal = TERMINAL.contains(payload.status());
        // The event sequence advances on every worker transition. Doubling it
        // leaves a spare low bit for the terminal move, which can settle the job
        // without appending a further event.
        long version = payload.highestSequence() * 2 + (terminal ? 1 : 0);
        if (version <= sinceVersion) {
            return Optional.empty();
        }
        return Optional.of(new JobEventStream.Change(version, payload.node(), terminal));
    }
}

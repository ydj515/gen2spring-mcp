package io.gen2spring.mcp.application.hosted.worker.port.out;

import java.time.Duration;

/** Runs lease checks periodically, starting after the first interval. */
@FunctionalInterface
public interface LeaseMonitorScheduler {
    Registration schedule(Duration interval, Runnable check);

    interface Registration {
        /** Stops future checks and reports whether any running check has terminated. */
        boolean stop();
    }
}

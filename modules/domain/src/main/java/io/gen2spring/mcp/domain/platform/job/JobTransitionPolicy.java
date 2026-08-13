package io.gen2spring.mcp.domain.platform.job;

public final class JobTransitionPolicy {
    private static final String INVALID = "Hosted job state transition is invalid";

    private JobTransitionPolicy() {}

    public static void requireAllowed(JobStatus from, JobStatus to) {
        boolean allowed = from == JobStatus.QUEUED
                && (to == JobStatus.RUNNING || to == JobStatus.CANCELLED);
        allowed |= from == JobStatus.RUNNING
                && (to == JobStatus.QUEUED
                || to == JobStatus.SUCCEEDED
                || to == JobStatus.FAILED
                || to == JobStatus.CANCELLED);
        if (!allowed) {
            throw new IllegalArgumentException(INVALID);
        }
    }
}

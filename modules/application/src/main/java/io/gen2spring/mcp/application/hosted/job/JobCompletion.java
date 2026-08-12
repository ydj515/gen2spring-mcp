package io.gen2spring.mcp.application.hosted.job;

import io.gen2spring.mcp.domain.platform.job.JobStatus;
import java.util.Set;
import java.util.regex.Pattern;

public record JobCompletion(JobStatus status, String safeCode, String safeSummary) {
    private static final Set<JobStatus> TERMINAL = Set.of(
            JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED);
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public JobCompletion {
        if (!TERMINAL.contains(status)) {
            throw new IllegalArgumentException("Hosted job completion is invalid");
        }
        if (status == JobStatus.SUCCEEDED) {
            if (safeCode != null || safeSummary != null) {
                throw new IllegalArgumentException("Hosted job completion is invalid");
            }
        } else if (safeCode == null || !CODE.matcher(safeCode).matches()
                || safeSummary == null || safeSummary.isBlank() || safeSummary.length() > 256
                || safeSummary.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Hosted job completion is invalid");
        }
    }

    public static JobCompletion success() {
        return new JobCompletion(JobStatus.SUCCEEDED, null, null);
    }

    public static JobCompletion failure(String safeCode, String safeSummary) {
        return new JobCompletion(JobStatus.FAILED, safeCode, safeSummary);
    }
}

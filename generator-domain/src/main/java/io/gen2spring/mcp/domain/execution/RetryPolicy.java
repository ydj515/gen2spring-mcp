package io.gen2spring.mcp.domain.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public record RetryPolicy(
        List<Integer> statusCodes,
        boolean networkErrors,
        int maxRetries,
        long initialBackoffMillis,
        long maxBackoffMillis,
        boolean respectRetryAfter) {
    private static final String INVALID_MESSAGE = "Retry policy is invalid";

    public RetryPolicy {
        List<Integer> copied = statusCodes == null ? List.of() : new ArrayList<>(statusCodes);
        if (copied.stream().anyMatch(status -> status == null || status < 400 || status > 599)
                || new HashSet<>(copied).size() != copied.size()
                || copied.size() > 16
                || !networkErrors && copied.isEmpty()
                || maxRetries < 1 || maxRetries > 3
                || initialBackoffMillis < 1 || initialBackoffMillis > 5_000
                || maxBackoffMillis < initialBackoffMillis || maxBackoffMillis > 10_000) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        statusCodes = copied.stream().sorted().toList();
    }
}

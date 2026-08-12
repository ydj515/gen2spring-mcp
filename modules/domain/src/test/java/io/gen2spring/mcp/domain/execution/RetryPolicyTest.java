package io.gen2spring.mcp.domain.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void sortsAndDefensivelyCopiesValidRetryStatuses() {
        var statuses = new java.util.ArrayList<>(List.of(503, 429));

        RetryPolicy policy = new RetryPolicy(statuses, true, 2, 100, 1_000, true);
        statuses.clear();

        assertEquals(List.of(429, 503), policy.statusCodes());
        assertThrows(UnsupportedOperationException.class, () -> policy.statusCodes().add(504));
    }

    @Test
    void rejectsInvalidRetryContractsWithAFixedMessage() {
        for (RetryPolicyArguments invalid : List.of(
                new RetryPolicyArguments(List.of(), false, 1, 1, 1, false),
                new RetryPolicyArguments(List.of(429, 429), false, 1, 1, 1, false),
                new RetryPolicyArguments(List.of(399), false, 1, 1, 1, false),
                new RetryPolicyArguments(List.of(600), false, 1, 1, 1, false),
                new RetryPolicyArguments(java.util.stream.IntStream.range(0, 17).mapToObj(i -> 400 + i).toList(),
                        false, 1, 1, 1, false),
                new RetryPolicyArguments(List.of(429), false, 0, 1, 1, false),
                new RetryPolicyArguments(List.of(429), false, 4, 1, 1, false),
                new RetryPolicyArguments(List.of(429), false, 1, 0, 1, false),
                new RetryPolicyArguments(List.of(429), false, 1, 5_001, 5_001, false),
                new RetryPolicyArguments(List.of(429), false, 1, 100, 99, false),
                new RetryPolicyArguments(List.of(429), false, 1, 100, 10_001, false))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, invalid::create);
            assertEquals("Retry policy is invalid", failure.getMessage());
        }
    }

    private record RetryPolicyArguments(
            List<Integer> statusCodes,
            boolean networkErrors,
            int maxRetries,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean respectRetryAfter) {
        private RetryPolicy create() {
            return new RetryPolicy(statusCodes, networkErrors, maxRetries,
                    initialBackoffMillis, maxBackoffMillis, respectRetryAfter);
        }
    }
}

package io.gen2spring.mcp.domain.platform.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobTransitionPolicyTest {
    private static final String INVALID_IDENTIFIER = "Platform identifier is invalid";
    private static final String INVALID_TRANSITION = "Hosted job state transition is invalid";

    @Test
    void preservesCanonicalUuidIdentifiersAcrossStorageBoundaries() {
        UUID accountValue = UUID.fromString("67d8ce8b-8f88-4b30-82c4-4d98b971e964");
        UUID specificationValue = UUID.fromString("e7665380-d12d-4392-8b97-bf3d42e36455");
        UUID jobValue = UUID.fromString("7d91577c-cb9e-40b1-8da9-dad2d0ce1db5");

        assertEquals(accountValue, new AccountId(accountValue).value());
        assertEquals(specificationValue, new SpecificationId(specificationValue).value());
        assertEquals(jobValue, new JobId(jobValue).value());
        assertEquals(accountValue, AccountId.parse(accountValue.toString()).value());
        assertEquals(specificationValue, SpecificationId.parse(specificationValue.toString()).value());
        assertEquals(jobValue, JobId.parse(jobValue.toString()).value());
    }

    @Test
    void rejectsInvalidIdentifiersWithoutEchoingTheirValues() {
        assertInvalidIdentifier(() -> new AccountId(null));
        assertInvalidIdentifier(() -> new SpecificationId(null));
        assertInvalidIdentifier(() -> new JobId(null));
        assertInvalidIdentifier(() -> AccountId.parse("account-private-marker"));
        assertInvalidIdentifier(() -> SpecificationId.parse("specification-private-marker"));
        assertInvalidIdentifier(() -> JobId.parse("job-private-marker"));
    }

    @Test
    void permitsOnlyTheCanonicalQueuedAndRunningTransitions() {
        for (Transition allowed : List.of(
                new Transition(JobStatus.QUEUED, JobStatus.RUNNING),
                new Transition(JobStatus.QUEUED, JobStatus.CANCELLED),
                new Transition(JobStatus.RUNNING, JobStatus.QUEUED),
                new Transition(JobStatus.RUNNING, JobStatus.SUCCEEDED),
                new Transition(JobStatus.RUNNING, JobStatus.FAILED),
                new Transition(JobStatus.RUNNING, JobStatus.CANCELLED))) {
            JobTransitionPolicy.requireAllowed(allowed.from(), allowed.to());
        }
    }

    @Test
    void keepsTerminalStatesClosedAndRejectsNullOrSelfTransitions() {
        for (JobStatus terminal : List.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobStatus target : JobStatus.values()) {
                assertInvalidTransition(terminal, target);
            }
        }

        for (JobStatus state : JobStatus.values()) {
            assertInvalidTransition(state, state);
        }
        assertInvalidTransition(null, JobStatus.QUEUED);
        assertInvalidTransition(JobStatus.QUEUED, null);
        assertInvalidTransition(JobStatus.QUEUED, JobStatus.SUCCEEDED);
        assertInvalidTransition(JobStatus.QUEUED, JobStatus.FAILED);
    }

    private void assertInvalidIdentifier(Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertEquals(INVALID_IDENTIFIER, failure.getMessage());
    }

    private void assertInvalidTransition(JobStatus from, JobStatus to) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> JobTransitionPolicy.requireAllowed(from, to));
        assertEquals(INVALID_TRANSITION, failure.getMessage());
    }

    private record Transition(JobStatus from, JobStatus to) {}
}

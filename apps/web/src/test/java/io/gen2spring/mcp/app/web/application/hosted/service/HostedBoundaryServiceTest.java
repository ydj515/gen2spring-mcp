package io.gen2spring.mcp.app.web.application.hosted.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.web.application.hosted.exception.HostedResourceNotFound;
import io.gen2spring.mcp.app.web.application.hosted.port.out.VerifiedArtifactReader;
import io.gen2spring.mcp.application.hosted.account.port.out.AccountStore;
import io.gen2spring.mcp.application.hosted.query.port.out.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostedBoundaryServiceTest {
    private static final AccountId OWNER = new AccountId(UUID.randomUUID());
    private static final JobId JOB = new JobId(UUID.randomUUID());

    @Test
    void accountResolutionUsesTheObservedClockAndOnlyTheStableIdentity() {
        AccountStore store = mock(AccountStore.class);
        Instant observedAt = Instant.parse("2026-08-13T00:00:00Z");
        when(store.findOrCreate("https://issuer.example", "subject-1", observedAt)).thenReturn(OWNER);
        HostedAccountService service = new HostedAccountService(store, Clock.fixed(observedAt, ZoneOffset.UTC));

        assertEquals(OWNER, service.findOrCreate("https://issuer.example", "subject-1"));
        verify(store).findOrCreate("https://issuer.example", "subject-1", observedAt);
    }

    @Test
    void aMissingOwnedJobStopsBeforeReadingItsEventsAndArtifacts() {
        HostedResourceStore store = mock(HostedResourceStore.class);
        HostedResourceQueryService service = new HostedResourceQueryService(store);
        when(store.job(OWNER, JOB)).thenReturn(Optional.empty());

        assertThrows(HostedResourceNotFound.class,
                () -> service.job(OWNER, JOB));
        verify(store).job(OWNER, JOB);
        verifyNoMoreInteractions(store);
    }

    @Test
    void specificationPagesExposeOnlyPublicFieldsAndTheLastVisibleCursor() {
        HostedResourceStore store = mock(HostedResourceStore.class);
        HostedResourceQueryService service = new HostedResourceQueryService(store);
        Instant createdAt = Instant.parse("2026-08-13T00:00:00Z");
        SpecificationId first = new SpecificationId(UUID.randomUUID());
        SpecificationId second = new SpecificationId(UUID.randomUUID());
        when(store.specifications(OWNER, 2, Optional.empty())).thenReturn(List.of(
                specification(first, createdAt), specification(second, createdAt.minusSeconds(1))));

        var page = service.specifications(OWNER, 1, Optional.empty());

        assertEquals(List.of(first), page.items().stream().map(
                HostedResourceQueryService.SpecificationSummary::id).toList());
        assertEquals(first.value(), page.nextCursor().orElseThrow().id());
        assertFalse(page.items().getFirst().toString().contains("objectKey"));
        assertFalse(page.items().getFirst().toString().contains("sha256"));
    }

    @Test
    void aMissingOwnedArtifactDoesNotOpenObjectStorage() {
        HostedResourceStore store = mock(HostedResourceStore.class);
        VerifiedArtifactReader reader = mock(VerifiedArtifactReader.class);
        HostedArtifactDownloadService service = new HostedArtifactDownloadService(store, reader);
        UUID id = UUID.randomUUID();
        when(store.artifact(OWNER, id)).thenReturn(Optional.empty());

        assertThrows(HostedResourceNotFound.class,
                () -> service.download(OWNER, id));
        verifyNoInteractions(reader);
    }

    private HostedResourceStore.SpecificationView specification(SpecificationId id, Instant createdAt) {
        return new HostedResourceStore.SpecificationView(
                id, "UPLOAD", ObjectKey.parse("specifications/" + id.value() + "/source"),
                "a".repeat(64), 10, "weather.yaml", createdAt);
    }
}

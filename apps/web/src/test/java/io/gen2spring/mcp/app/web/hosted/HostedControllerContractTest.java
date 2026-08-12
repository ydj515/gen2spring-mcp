package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.app.web.security.HostedAccountPrincipal;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class HostedControllerContractTest {
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final JobId JOB = new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800"));

    @Test
    void exposesSafeOwnedJobMetadataWithoutPrivateObjectKeys() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        Authentication authentication = mock(Authentication.class);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(resources.job(OWNER, JOB)).thenReturn(Optional.of(new HostedResourceStore.JobDetails(
                JOB, JobKind.GENERATION, JobStatus.SUCCEEDED, Optional.empty(), 1, false,
                null, null, Instant.EPOCH, Instant.EPOCH)));
        when(resources.events(OWNER, JOB, 100)).thenReturn(List.of());
        when(resources.artifacts(OWNER, JOB)).thenReturn(List.of(new HostedResourceStore.ArtifactView(
                UUID.randomUUID(), JOB, "ZIP",
                ObjectKey.parse("artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/result"),
                "a".repeat(64), 20, "application/zip", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))));
        HostedJobController controller = new HostedJobController(
                accounts, mock(HostedSubmissionService.class), mock(HostedJobService.class), resources,
                new ObjectMapper());

        String response = controller.get(authentication, JOB.value().toString()).toString();

        assertFalse(response.contains("objectKey"));
        assertFalse(response.contains("sha256"));
        assertFalse(response.contains("artifacts/1a803410"));
    }

    @Test
    void rejectsCrossOwnerArtifactReadsBeforeObjectStorageAccess() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        Authentication authentication = mock(Authentication.class);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        UUID artifact = UUID.randomUUID();
        when(resources.artifact(OWNER, artifact)).thenReturn(Optional.empty());
        HostedArtifactController controller = new HostedArtifactController(accounts, resources, storage);

        assertThrows(
                HostedJobController.HostedResourceNotFound.class,
                () -> controller.download(authentication, artifact.toString(), mock(jakarta.servlet.http.HttpServletResponse.class)));
        verifyNoInteractions(storage);
    }

    @Test
    void exposesBoundedSpecificationPagesWithoutStorageCoordinates() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        Authentication authentication = mock(Authentication.class);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        var first = specification("80782e7c-337d-4d4d-bd4d-ad478359563c", Instant.parse("2026-08-13T00:00:02Z"));
        var second = specification("90782e7c-337d-4d4d-bd4d-ad478359563c", Instant.parse("2026-08-13T00:00:01Z"));
        when(resources.specifications(OWNER, 2, Optional.empty())).thenReturn(List.of(first, second));
        HostedSpecificationController controller = new HostedSpecificationController(
                accounts, mock(HostedSubmissionService.class), resources, new ObjectMapper());

        String response = controller.specifications(authentication, 1, null).toString();

        org.junit.jupiter.api.Assertions.assertTrue(response.contains("nextCursor"));
        org.junit.jupiter.api.Assertions.assertTrue(response.contains(first.id().value().toString()));
        assertFalse(response.contains(second.id().value().toString()));
        assertFalse(response.contains("objectKey"));
        assertFalse(response.contains("sha256"));
    }

    private HostedResourceStore.SpecificationView specification(String id, Instant createdAt) {
        return new HostedResourceStore.SpecificationView(
                new SpecificationId(UUID.fromString(id)), "UPLOAD",
                ObjectKey.parse("specifications/" + id + "/source"), "a".repeat(64), 10,
                "weather.yaml", createdAt);
    }
}

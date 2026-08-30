package io.gen2spring.mcp.app.web.hosted;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.hosted.job.HostedJobService;
import io.gen2spring.mcp.application.hosted.query.HostedResourceStore;
import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.application.hosted.storage.ObjectStorage;
import io.gen2spring.mcp.application.hosted.storage.StoredObjectContent;
import io.gen2spring.mcp.app.web.job.JobEventStream;
import io.gen2spring.mcp.app.web.security.HostedAccountPrincipal;
import io.gen2spring.mcp.app.web.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.job.JobId;
import io.gen2spring.mcp.domain.platform.job.JobKind;
import io.gen2spring.mcp.domain.platform.job.JobStatus;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import io.gen2spring.mcp.domain.profile.CompatibilityProfileRegistry;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;

class HostedControllerContractTest {
    private static final AccountId OWNER = new AccountId(UUID.fromString("41dd3b69-589c-4466-a78e-d448407d17b9"));
    private static final JobId JOB = new JobId(UUID.fromString("1a803410-a22a-4bc6-b951-7dbc301ae800"));

    @Test
    void registersTheToolCatalogControllerOnlyInHostedMode() {
        ConditionalOnProperty condition = HostedToolCatalogController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertNotNull(condition);
        assertEquals(List.of("gen2spring.mode"), List.of(condition.name()));
        assertEquals("hosted", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Test
    void exposesTheSharedAnalysisForUploadReadAndPlanningPreview() throws Exception {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedSubmissionService submissions = mock(HostedSubmissionService.class);
        Authentication authentication = mock(Authentication.class);
        SpecificationId id = new SpecificationId(UUID.randomUUID());
        SpecificationAnalysisView analysis = analysis();
        var result = new HostedSubmissionService.HostedSpecificationAnalysis(
                id, "weather.yml", 123, analysis);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(submissions.upload(
                eq(OWNER), any(InputStream.class), eq("application/yaml"), eq("weather.yml")))
                .thenReturn(result);
        when(submissions.analysis(OWNER, id)).thenReturn(result);
        when(submissions.preview(eq(OWNER), eq(id), any(byte[].class)))
                .thenReturn(new GenerationPreview(
                        CompatibilityProfileRegistry.defaults()
                                .find("spring-ai-2.0-java21-mvc-streamable").orElseThrow(),
                        List.of(), List.of(), List.of(), List.of("README.md")));
        HostedSpecificationController controller = new HostedSpecificationController(
                accounts, submissions, mock(HostedResourceStore.class), new ObjectMapper());
        MockHttpServletRequest uploadRequest = new MockHttpServletRequest();
        uploadRequest.setContent("openapi: 3.1.1".getBytes(StandardCharsets.UTF_8));

        String uploaded = controller.upload(
                authentication, "application/yaml", "weather.yml", uploadRequest).getBody().toString();
        String analyzed = controller.analysis(authentication, id.value().toString()).toString();
        String previewed = controller.preview(
                authentication, id.value().toString(), "{}".getBytes(StandardCharsets.UTF_8)).toString();

        assertTrue(uploaded.contains("weather.yml"));
        assertTrue(uploaded.contains("3.1.1"));
        assertEquals(uploaded, analyzed);
        assertTrue(
                previewed.contains("spring-ai-2.0-java21-mvc-streamable"));
        assertFalse(previewed.contains("objectKey"));
    }

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
                new ObjectMapper(), mock(JobEventStream.class));

        String response = controller.get(authentication, JOB.value().toString()).toString();

        assertFalse(response.contains("objectKey"));
        assertFalse(response.contains("sha256"));
        assertFalse(response.contains("artifacts/1a803410"));
    }

    @Test
    void refusesAnEventStreamForAJobOwnedByAnotherAccount() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        Authentication authentication = mock(Authentication.class);
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(resources.job(OWNER, JOB)).thenReturn(Optional.empty());
        JobEventStream streams = mock(JobEventStream.class);
        HostedJobController controller = new HostedJobController(
                accounts, mock(HostedSubmissionService.class), mock(HostedJobService.class), resources,
                new ObjectMapper(), streams);

        assertThrows(
                HostedJobController.HostedResourceNotFound.class,
                () -> controller.events(authentication, JOB.value().toString()));
        // Ownership must be settled before any stream exists, or the stream's
        // behaviour would itself disclose whether the job is real.
        verifyNoInteractions(streams);
    }

    @Test
    void streamsOwnedJobProgressWithoutPrivateObjectKeys() {
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

        try (JobEventStream streams = new JobEventStream(2)) {
            HostedJobController controller = new HostedJobController(
                    accounts, mock(HostedSubmissionService.class), mock(HostedJobService.class), resources,
                    new ObjectMapper(), streams);

            assertNotNull(controller.events(authentication, JOB.value().toString()));
        }
    }

    @Test
    void hostedFeedReportsTerminalStatusAndHidesPrivateObjectKeys() throws Exception {
        var payload = new ObjectMapper().createObjectNode().put("status", "SUCCEEDED");
        HostedJobEventFeed feed = new HostedJobEventFeed(
                () -> new HostedJobEventFeed.Payload(payload, 3, JobStatus.SUCCEEDED));

        var change = feed.awaitChange(0, Duration.ofMillis(200)).orElseThrow();

        assertEquals(payload, change.payload());
        assertTrue(change.terminal(), "a SUCCEEDED job must close the stream");
        assertTrue(feed.awaitChange(change.version(), Duration.ofMillis(150)).isEmpty(),
                "an unchanged job must time out rather than repeat itself");
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
                () -> controller.download(authentication, artifact.toString(), mock(HttpServletResponse.class)));
        verifyNoInteractions(storage);
    }

    @Test
    void verifiesArtifactBytesBeforeCommittingTheDownloadResponse() {
        HostedAccountResolver accounts = mock(HostedAccountResolver.class);
        HostedResourceStore resources = mock(HostedResourceStore.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        Authentication authentication = mock(Authentication.class);
        UUID artifactId = UUID.randomUUID();
        ObjectKey key = ObjectKey.parse("artifacts/1a803410-a22a-4bc6-b951-7dbc301ae800/result");
        when(accounts.resolve(authentication)).thenReturn(new HostedAccountPrincipal(OWNER));
        when(resources.artifact(OWNER, artifactId)).thenReturn(Optional.of(
                new HostedResourceStore.ArtifactView(
                        artifactId, JOB, "ZIP", key, "a".repeat(64), 4,
                        "application/zip", Instant.EPOCH, Instant.EPOCH.plusSeconds(60))));
        when(storage.get(key)).thenReturn(new StoredObjectContent() {
            @Override public InputStream body() { return new ByteArrayInputStream("evil".getBytes()); }
            @Override public long size() { return 4; }
            @Override public String sha256() { return "a".repeat(64); }
            @Override public String contentType() { return "application/zip"; }
            @Override public void close() {}
        });
        MockHttpServletResponse response = new MockHttpServletResponse();
        HostedArtifactController controller = new HostedArtifactController(accounts, resources, storage);

        assertThrows(HostedArtifactController.HostedArtifactFailure.class,
                () -> controller.download(authentication, artifactId.toString(), response));
        assertEquals(0, response.getContentAsByteArray().length);
        assertFalse(response.containsHeader(HttpHeaders.CONTENT_DISPOSITION));
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

        assertTrue(response.contains("nextCursor"));
        assertTrue(response.contains(first.id().value().toString()));
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

    private SpecificationAnalysisView analysis() {
        return new SpecificationAnalysisView(
                "a".repeat(64), "3.1.1", "yaml", URI.create("https://weather.example.test"),
                new SpecificationAnalysisView.Counts(0, 0, 0, 0),
                List.of(), Map.of(), List.of());
    }
}

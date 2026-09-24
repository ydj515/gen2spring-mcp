package io.gen2spring.mcp.app.web.presentation.hosted;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeMigrationService.MigrationResult;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.RuntimeCatalogTransition;
import io.gen2spring.mcp.application.managed.runtime.RuntimeCatalogTransitionStore.TransitionPage;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record RuntimeMigrationResponse(
        String runtimeId,
        String catalogId,
        String catalogChecksum,
        String state,
        String diffChecksum,
        RuntimeTransitionResponse transition,
        String token) {

    static RuntimeMigrationResponse from(MigrationResult result, Instant now) {
        var instance = result.instance();
        return new RuntimeMigrationResponse(
                instance.id().value().toString(),
                instance.catalogId().toString(),
                instance.catalogChecksum(),
                instance.stateAt(now).name(),
                result.diffChecksum(),
                RuntimeTransitionResponse.from(result.transition()),
                null);
    }
}

record RuntimeTransitionPageResponse(
        List<RuntimeTransitionResponse> items,
        Long nextBefore) {

    static RuntimeTransitionPageResponse from(TransitionPage page) {
        return new RuntimeTransitionPageResponse(
                page.items().stream().map(RuntimeTransitionResponse::from).toList(),
                page.nextBefore().orElse(null));
    }
}

record RuntimeTransitionResponse(
        long sequence,
        String runtimeId,
        String sourceCatalogId,
        String sourceChecksum,
        String targetCatalogId,
        String targetChecksum,
        String diffChecksum,
        String kind,
        String createdAt) {

    static RuntimeTransitionResponse from(RuntimeCatalogTransition transition) {
        return new RuntimeTransitionResponse(
                transition.sequence(),
                transition.runtimeId().value().toString(),
                transition.sourceCatalogId().toString(),
                transition.sourceChecksum(),
                transition.targetCatalogId().toString(),
                transition.targetChecksum(),
                transition.diffChecksum(),
                transition.kind().name(),
                transition.createdAt().toString());
    }
}

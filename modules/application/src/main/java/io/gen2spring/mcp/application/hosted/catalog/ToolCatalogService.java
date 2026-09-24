package io.gen2spring.mcp.application.hosted.catalog;

import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogCursor;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogPage;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.CatalogSummary;
import io.gen2spring.mcp.application.hosted.catalog.port.out.ToolCatalogStore.ToolDetails;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ToolCatalogService {
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private final ToolCatalogStore store;

    public ToolCatalogService(ToolCatalogStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public CatalogPage list(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
        requireListQuery(owner, limit, cursor);
        try {
            List<CatalogSummary> fetched = List.copyOf(store.list(owner, limit + 1, cursor));
            if (fetched.size() > limit + 1 || fetched.stream().anyMatch(Objects::isNull)) {
                throw new IllegalStateException();
            }
            boolean hasMore = fetched.size() > limit;
            List<CatalogSummary> items = fetched.subList(0, Math.min(limit, fetched.size()));
            Optional<CatalogCursor> next = hasMore
                    ? Optional.of(new CatalogCursor(items.getLast().createdAt(), items.getLast().catalogId()))
                    : Optional.empty();
            return new CatalogPage(items, next);
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new ToolCatalogReadFailure();
        }
    }

    public CatalogDetails require(AccountId owner, UUID catalogId) {
        requireIdentity(owner, catalogId);
        Optional<CatalogDetails> result;
        try {
            result = Objects.requireNonNull(store.find(owner, catalogId));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new ToolCatalogReadFailure();
        }
        return result.orElseThrow(ToolCatalogNotFound::new);
    }

    public ToolDetails requireTool(AccountId owner, UUID catalogId, String toolName) {
        requireIdentity(owner, catalogId);
        if (toolName == null || !TOOL_NAME.matcher(toolName).matches()) {
            throw new ToolCatalogQueryInvalid();
        }
        Optional<ToolDetails> result;
        try {
            result = Objects.requireNonNull(store.findTool(owner, catalogId, toolName));
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new ToolCatalogReadFailure();
        }
        return result.orElseThrow(ToolCatalogNotFound::new);
    }

    private void requireListQuery(AccountId owner, int limit, Optional<CatalogCursor> cursor) {
        if (owner == null || limit < 1 || limit > 100 || cursor == null
                || cursor.filter(value -> value.createdAt() == null || value.id() == null).isPresent()) {
            throw new ToolCatalogQueryInvalid();
        }
    }

    private void requireIdentity(AccountId owner, UUID catalogId) {
        if (owner == null || catalogId == null) {
            throw new ToolCatalogQueryInvalid();
        }
    }

    public static final class ToolCatalogNotFound extends RuntimeException {
        public ToolCatalogNotFound() {
            super("The Tool Catalog was not found");
        }
    }

    public static final class ToolCatalogQueryInvalid extends RuntimeException {
        public ToolCatalogQueryInvalid() {
            super("The Tool Catalog query is invalid");
        }
    }

    public static final class ToolCatalogReadFailure extends RuntimeException {
        public ToolCatalogReadFailure() {
            super("The Tool Catalog could not be read");
        }
    }
}

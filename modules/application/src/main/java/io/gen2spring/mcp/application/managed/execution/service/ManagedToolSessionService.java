package io.gen2spring.mcp.application.managed.execution.service;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.managed.credential.service.RuntimeCredentialResolver;
import io.gen2spring.mcp.application.managed.execution.ManagedRuntimeBinding;
import io.gen2spring.mcp.application.managed.execution.result.ManagedToolSession;
import io.gen2spring.mcp.application.managed.runtime.RuntimeAccess;
import java.util.Objects;

public final class ManagedToolSessionService {
    private final ToolCatalogService catalogs;
    private final ManagedToolExecutor executor;
    private final RuntimeCredentialResolver credentials;

    public ManagedToolSessionService(
            ToolCatalogService catalogs,
            ManagedToolExecutor executor,
            RuntimeCredentialResolver credentials) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public ManagedToolSession open(RuntimeAccess access) {
        Objects.requireNonNull(access, "access");
        var instance = access.instance();
        var catalog = catalogs.require(instance.owner(), instance.catalogId());
        ManagedRuntimeBinding binding = new ManagedRuntimeBinding(instance, catalog.metadata());
        ManagedExecutionContext context = new ManagedExecutionContext(access, binding, credentials);
        var tools = catalog.metadata().document().tools().stream()
                .filter(tool -> access.allowedTools().contains(tool.name()))
                .toList();
        return new ManagedToolSession(tools, (name, arguments) -> executor.call(context, name, arguments));
    }
}

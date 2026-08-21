package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogService;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore.StoredRuntime;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.ProviderTarget;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeInstanceId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class ManagedRuntimeService {
    private static final Duration DEFAULT_LIFETIME = Duration.ofHours(24);
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);

    private final ToolCatalogService catalogs;
    private final ManagedRuntimeStore runtimes;
    private final RuntimeTokenCodec tokens;
    private final Clock clock;
    private final URI runtimeBaseUri;
    private final Supplier<UUID> identifiers;

    public ManagedRuntimeService(
            ToolCatalogService catalogs,
            ManagedRuntimeStore runtimes,
            RuntimeTokenCodec tokens,
            Clock clock,
            URI runtimeBaseUri) {
        this(catalogs, runtimes, tokens, clock, runtimeBaseUri, UUID::randomUUID);
    }

    ManagedRuntimeService(
            ToolCatalogService catalogs,
            ManagedRuntimeStore runtimes,
            RuntimeTokenCodec tokens,
            Clock clock,
            URI runtimeBaseUri,
            Supplier<UUID> identifiers) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeBaseUri = runtimeBase(runtimeBaseUri);
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    public RuntimeActivation activate(
            AccountId owner,
            UUID catalogId,
            Optional<String> providerBaseUrl,
            Optional<Duration> requestedLifetime) {
        if (owner == null || catalogId == null || providerBaseUrl == null || requestedLifetime == null) {
            throw invalid();
        }
        Duration lifetime = requestedLifetime.orElse(DEFAULT_LIFETIME);
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw invalid();
        }
        CatalogDetails catalog = requireCatalog(owner, catalogId);
        Optional<ProviderTarget> provider = provider(catalog, providerBaseUrl);
        IssuedRuntimeToken issued = tokens.issue();
        Instant now = clock.instant();
        RuntimeInstanceId id = new RuntimeInstanceId(Objects.requireNonNull(identifiers.get()));
        ManagedRuntimeInstance instance = new ManagedRuntimeInstance(
                id,
                owner,
                catalogId,
                catalog.summary().metadataChecksum(),
                provider,
                now,
                now.plus(lifetime),
                Optional.empty());
        try {
            runtimes.create(instance, issued.digest());
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        return new RuntimeActivation(
                instance,
                issued.plaintext(),
                runtimeBaseUri.resolve("/mcp/" + id.value()));
    }

    public ManagedRuntimeInstance require(AccountId owner, RuntimeInstanceId id) {
        if (owner == null || id == null) {
            throw invalid();
        }
        try {
            StoredRuntime stored = Objects.requireNonNull(runtimes.find(id)).orElseThrow(ManagedRuntimeService::notFound);
            if (!stored.instance().owner().equals(owner)) {
                throw notFound();
            }
            return stored.instance();
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedRuntimeNotFound failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public void revoke(AccountId owner, RuntimeInstanceId id) {
        ManagedRuntimeInstance instance = require(owner, id);
        if (instance.revokedAt().isPresent()) {
            return;
        }
        try {
            if (!runtimes.revoke(owner, id, clock.instant())) {
                StoredRuntime current = Objects.requireNonNull(runtimes.find(id)).orElseThrow(ManagedRuntimeService::notFound);
                if (!current.instance().owner().equals(owner) || current.instance().revokedAt().isEmpty()) {
                    throw unavailable();
                }
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedRuntimeNotFound | ManagedRuntimeUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private CatalogDetails requireCatalog(AccountId owner, UUID catalogId) {
        try {
            return catalogs.require(owner, catalogId);
        } catch (Error fatal) {
            throw fatal;
        } catch (ToolCatalogService.ToolCatalogNotFound failure) {
            throw notFound();
        } catch (ToolCatalogService.ToolCatalogQueryInvalid failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private Optional<ProviderTarget> provider(CatalogDetails catalog, Optional<String> requested) {
        boolean relative = false;
        boolean absolute = false;
        try {
            for (RuntimeTool tool : catalog.metadata().document().tools()) {
                if (!tool.credentials().isEmpty()) {
                    throw invalid();
                }
                URI base = URI.create(tool.http().baseUrl());
                if (base.isAbsolute()) {
                    ProviderTarget.parse(base.toASCIIString());
                    absolute = true;
                } else {
                    relative = true;
                }
            }
            if (relative && absolute || relative != requested.isPresent()) {
                throw invalid();
            }
            return requested.map(ProviderTarget::parse);
        } catch (ManagedRuntimeRequestInvalid failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static URI runtimeBase(URI value) {
        try {
            ProviderTarget parsed = new ProviderTarget(value);
            return parsed.uri();
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static ManagedRuntimeRequestInvalid invalid() {
        return new ManagedRuntimeRequestInvalid();
    }

    private static ManagedRuntimeNotFound notFound() {
        return new ManagedRuntimeNotFound();
    }

    private static ManagedRuntimeUnavailable unavailable() {
        return new ManagedRuntimeUnavailable();
    }

    public static final class ManagedRuntimeRequestInvalid extends RuntimeException {
        public ManagedRuntimeRequestInvalid() {
            super("Managed runtime request is invalid", null, false, false);
        }
    }

    public static final class ManagedRuntimeNotFound extends RuntimeException {
        public ManagedRuntimeNotFound() {
            super("Managed runtime was not found", null, false, false);
        }
    }

    public static final class ManagedRuntimeUnavailable extends RuntimeException {
        public ManagedRuntimeUnavailable() {
            super("Managed runtime is unavailable", null, false, false);
        }
    }
}

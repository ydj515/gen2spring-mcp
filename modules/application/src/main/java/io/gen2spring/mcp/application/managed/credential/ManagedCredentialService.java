package io.gen2spring.mcp.application.managed.credential;

import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore.StoredCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential.CredentialState;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class ManagedCredentialService {
    private static final int MAX_ACTIVE = 100;
    private final ManagedCredentialStore store;
    private final CredentialProtector protector;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public ManagedCredentialService(
            ManagedCredentialStore store,
            CredentialProtector protector,
            Clock clock) {
        this(store, protector, clock, UUID::randomUUID);
    }

    ManagedCredentialService(
            ManagedCredentialStore store,
            CredentialProtector protector,
            Clock clock,
            Supplier<UUID> identifiers) {
        this.store = Objects.requireNonNull(store, "store");
        this.protector = Objects.requireNonNull(protector, "protector");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
    }

    public ManagedCredential create(AccountId owner, String label, CredentialSecret secret) {
        try (CredentialSecret value = requireSecret(secret)) {
            if (owner == null || store.countActive(owner) >= MAX_ACTIVE) throw invalid();
            Instant now = clock.instant();
            ManagedCredential credential = new ManagedCredential(
                    new ManagedCredentialId(identifiers.get()), owner, label, value.kind(), 1,
                    now, now, Optional.empty());
            ProtectedCredential protectedValue = protector.protect(owner, credential.id(), 1, value);
            store.create(credential, protectedValue);
            return credential;
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedCredentialRequestInvalid failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public ManagedCredential rotate(AccountId owner, ManagedCredentialId id, CredentialSecret secret) {
        try (CredentialSecret value = requireSecret(secret)) {
            StoredCredential stored = stored(owner, id);
            ManagedCredential current = stored.credential();
            if (current.state() != CredentialState.ACTIVE || current.kind() != value.kind()) throw invalid();
            long version = Math.addExact(current.version(), 1);
            Instant now = clock.instant();
            ManagedCredential rotated = new ManagedCredential(
                    current.id(), current.owner(), current.label(), current.kind(), version,
                    current.createdAt(), now, Optional.empty());
            ProtectedCredential protectedValue = protector.protect(owner, id, version, value);
            if (!store.rotate(owner, id, current.version(), rotated, protectedValue)) throw unavailable();
            return rotated;
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedCredentialRequestInvalid | ManagedCredentialNotFound | ManagedCredentialUnavailable failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw invalid();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public List<ManagedCredential> list(AccountId owner) {
        if (owner == null) throw invalid();
        try {
            List<ManagedCredential> result = List.copyOf(store.list(owner));
            if (result.size() > MAX_ACTIVE || result.stream().anyMatch(value -> value == null
                    || !value.owner().equals(owner))) throw unavailable();
            return result;
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedCredentialUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public ManagedCredential require(AccountId owner, ManagedCredentialId id) {
        try {
            return stored(owner, id).credential();
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedCredentialRequestInvalid | ManagedCredentialNotFound failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public void revoke(AccountId owner, ManagedCredentialId id) {
        try {
            ManagedCredential credential = stored(owner, id).credential();
            if (credential.state() == CredentialState.REVOKED) return;
            if (!store.revoke(owner, id, clock.instant())) throw unavailable();
        } catch (Error fatal) {
            throw fatal;
        } catch (ManagedCredentialRequestInvalid | ManagedCredentialNotFound | ManagedCredentialUnavailable failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private StoredCredential stored(AccountId owner, ManagedCredentialId id) {
        if (owner == null || id == null) throw invalid();
        return Objects.requireNonNull(store.find(owner, id)).orElseThrow(ManagedCredentialService::notFound);
    }

    private CredentialSecret requireSecret(CredentialSecret secret) {
        if (secret == null) throw invalid();
        return secret;
    }

    private static ManagedCredentialRequestInvalid invalid() { return new ManagedCredentialRequestInvalid(); }
    private static ManagedCredentialNotFound notFound() { return new ManagedCredentialNotFound(); }
    private static ManagedCredentialUnavailable unavailable() { return new ManagedCredentialUnavailable(); }

    public static final class ManagedCredentialRequestInvalid extends RuntimeException {
        public ManagedCredentialRequestInvalid() {
            super("Managed credential request is invalid", null, false, false);
        }
    }

    public static final class ManagedCredentialNotFound extends RuntimeException {
        public ManagedCredentialNotFound() {
            super("Managed credential was not found", null, false, false);
        }
    }

    public static final class ManagedCredentialUnavailable extends RuntimeException {
        public ManagedCredentialUnavailable() {
            super("Managed credential service is unavailable", null, false, false);
        }
    }
}

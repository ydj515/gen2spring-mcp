package io.gen2spring.mcp.application.managed.credential;

import io.gen2spring.mcp.application.managed.credential.ManagedCredentialStore.StoredCredential;
import io.gen2spring.mcp.application.managed.runtime.ManagedRuntimeStore;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential.CredentialState;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class RuntimeCredentialResolver {
    private final ManagedRuntimeStore runtimes;
    private final ManagedCredentialStore credentials;
    private final CredentialProtector protector;

    public RuntimeCredentialResolver(
            ManagedRuntimeStore runtimes,
            ManagedCredentialStore credentials,
            CredentialProtector protector) {
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    public ResolvedCredentials resolve(ManagedRuntimeInstance runtime, RuntimeTool tool) {
        if (runtime == null || tool == null) throw unavailable();
        if (tool.credentials().isEmpty()) return ResolvedCredentials.of(List.of());
        List<WireCredential> values = new ArrayList<>();
        try {
            var stored = Objects.requireNonNull(runtimes.find(runtime.id())).orElseThrow(RuntimeCredentialResolver::unavailable);
            if (!runtime.equals(stored.instance())) throw unavailable();
            for (RuntimeCredential slot : tool.credentials()) {
                var credentialId = stored.credentialBindings().get(slot.credentialSlot());
                if (credentialId == null) {
                    if (slot.required()) throw unavailable();
                    continue;
                }
                StoredCredential storedCredential = Objects.requireNonNull(
                                credentials.find(runtime.owner(), credentialId))
                        .orElseThrow(RuntimeCredentialResolver::unavailable);
                if (storedCredential.credential().state() != CredentialState.ACTIVE
                        || !compatible(storedCredential.credential().kind(), slot)) throw unavailable();
                try (CredentialSecret secret = protector.reveal(
                        runtime.owner(), credentialId, storedCredential.credential().version(),
                        storedCredential.protectedValue())) {
                    byte[] wire = secret.wireValue();
                    try {
                        values.add(new WireCredential(
                                slot.credentialSlot(), slot.targetLocation(), slot.targetName(), wire));
                    } finally {
                        Arrays.fill(wire, (byte) 0);
                    }
                }
            }
            return ResolvedCredentials.of(values);
        } catch (Error fatal) {
            close(values);
            throw fatal;
        } catch (RuntimeException failure) {
            close(values);
            throw unavailable();
        }
    }

    private boolean compatible(ManagedCredentialKind kind, RuntimeCredential slot) {
        return switch (kind) {
            case OPAQUE -> slot.targetLocation() == ParameterLocation.HEADER
                    || slot.targetLocation() == ParameterLocation.QUERY;
            case BEARER, BASIC -> slot.targetLocation() == ParameterLocation.HEADER
                    && "authorization".equalsIgnoreCase(slot.targetName());
        };
    }

    private static void close(List<WireCredential> values) {
        values.forEach(WireCredential::close);
    }

    private static RuntimeCredentialUnavailable unavailable() {
        return new RuntimeCredentialUnavailable();
    }

    public static final class WireCredential implements AutoCloseable {
        private static final Pattern SLOT = Pattern.compile("[a-z][a-z0-9_-]{0,127}");
        private final String slot;
        private final ParameterLocation location;
        private final String targetName;
        private byte[] wireValue;
        private boolean closed;

        public WireCredential(String slot, ParameterLocation location, String targetName, byte[] wireValue) {
            if (slot == null || !SLOT.matcher(slot).matches()
                    || location == null || location != ParameterLocation.HEADER && location != ParameterLocation.QUERY
                    || targetName == null || targetName.isBlank() || targetName.length() > 128
                    || targetName.chars().anyMatch(Character::isISOControl)
                    || wireValue == null || wireValue.length < 1 || wireValue.length > 8192) throw unavailable();
            this.slot = slot;
            this.location = location;
            this.targetName = targetName;
            this.wireValue = wireValue.clone();
        }

        public String slot() { return slot; }
        public ParameterLocation location() { return location; }
        public String targetName() { return targetName; }
        public byte[] wireValue() {
            if (closed) throw unavailable();
            return wireValue.clone();
        }

        @Override public void close() {
            if (closed) return;
            Arrays.fill(wireValue, (byte) 0);
            closed = true;
        }

        @Override public String toString() {
            return "WireCredential[slot=" + slot + ", location=" + location
                    + ", targetName=" + targetName + ", wireValue=redacted]";
        }
    }

    public static final class ResolvedCredentials implements AutoCloseable {
        private final List<WireCredential> values;
        private boolean closed;

        private ResolvedCredentials(List<WireCredential> values) {
            this.values = values;
        }

        public static ResolvedCredentials of(List<WireCredential> source) {
            if (source == null) throw unavailable();
            List<WireCredential> copy = new ArrayList<>();
            Set<String> slots = new HashSet<>();
            try {
                for (WireCredential value : source) {
                    if (value == null || !slots.add(value.slot())) throw unavailable();
                    copy.add(value);
                }
                copy.sort(Comparator.comparing(WireCredential::slot));
                return new ResolvedCredentials(Collections.unmodifiableList(copy));
            } catch (RuntimeException failure) {
                closeDistinct(source);
                throw unavailable();
            }
        }

        private static void closeDistinct(List<WireCredential> source) {
            Set<WireCredential> closed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (WireCredential value : source) {
                if (value != null && closed.add(value)) value.close();
            }
        }

        public List<WireCredential> values() {
            if (closed) throw unavailable();
            return values;
        }

        @Override public void close() {
            if (closed) return;
            values.forEach(WireCredential::close);
            closed = true;
        }

        @Override public String toString() {
            return "ResolvedCredentials[count=" + values.size() + ", values=redacted]";
        }
    }

    public static final class RuntimeCredentialUnavailable extends RuntimeException {
        public RuntimeCredentialUnavailable() {
            super("Managed runtime credential is unavailable", null, false, false);
        }
    }
}

package io.gen2spring.mcp.application.hosted.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CatalogDiff(
        CatalogEndpoint source,
        CatalogEndpoint target,
        Compatibility compatibility,
        List<ToolChange> changes,
        String checksum) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Comparator<ToolChange> CHANGE_ORDER = Comparator
            .comparing(ToolChange::toolName)
            .thenComparing(change -> change.kind().name())
            .thenComparing(ToolChange::field);

    public CatalogDiff {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(compatibility, "compatibility");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        if (!changes.equals(changes.stream().sorted(CHANGE_ORDER).toList())
                || changes.stream().map(ToolChange::compatibility)
                        .anyMatch(value -> value == Compatibility.BREAKING)
                        != (compatibility == Compatibility.BREAKING)
                || checksum == null
                || !SHA_256.matcher(checksum).matches()) {
            throw new IllegalArgumentException("Catalog diff is invalid");
        }
    }

    public record CatalogEndpoint(
            UUID catalogId,
            long revision,
            String metadataChecksum,
            String specificationChecksum) {
        public CatalogEndpoint {
            Objects.requireNonNull(catalogId, "catalogId");
            if (revision < 1
                    || metadataChecksum == null
                    || !SHA_256.matcher(metadataChecksum).matches()
                    || specificationChecksum == null
                    || !SHA_256.matcher(specificationChecksum).matches()) {
                throw new IllegalArgumentException("Catalog diff endpoint is invalid");
            }
        }
    }

    public record ToolChange(String toolName, ChangeKind kind, String field) {
        private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,127}");

        public ToolChange {
            Objects.requireNonNull(kind, "kind");
            if (toolName == null || !TOOL_NAME.matcher(toolName).matches()
                    || field == null || !kind.field().equals(field)) {
                throw new IllegalArgumentException("Catalog Tool change is invalid");
            }
        }

        public Compatibility compatibility() {
            return kind.compatibility();
        }
    }

    public enum Compatibility {
        COMPATIBLE,
        BREAKING
    }

    public enum ChangeKind {
        TOOL_ADDED(Compatibility.COMPATIBLE, "tool"),
        TOOL_REMOVED(Compatibility.BREAKING, "tool"),
        DESCRIPTION_CHANGED(Compatibility.COMPATIBLE, "description"),
        INPUT_CHANGED(Compatibility.BREAKING, "inputSchema"),
        OUTPUT_OPTIONAL_PROPERTY_ADDED(Compatibility.COMPATIBLE, "outputSchema"),
        OUTPUT_CHANGED(Compatibility.BREAKING, "outputSchema"),
        HTTP_CHANGED(Compatibility.BREAKING, "http"),
        POLICY_CHANGED(Compatibility.BREAKING, "policy"),
        CREDENTIAL_CHANGED(Compatibility.BREAKING, "credentials");

        private final Compatibility compatibility;
        private final String field;

        ChangeKind(Compatibility compatibility, String field) {
            this.compatibility = compatibility;
            this.field = field;
        }

        public Compatibility compatibility() {
            return compatibility;
        }

        public String field() {
            return field;
        }
    }
}

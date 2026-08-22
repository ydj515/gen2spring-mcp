package io.gen2spring.mcp.application.hosted.catalog;

import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.CREDENTIAL_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.DESCRIPTION_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.HTTP_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.INPUT_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.OUTPUT_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.OUTPUT_OPTIONAL_PROPERTY_ADDED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.POLICY_CHANGED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.TOOL_ADDED;
import static io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind.TOOL_REMOVED;

import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.CatalogEndpoint;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ChangeKind;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.Compatibility;
import io.gen2spring.mcp.application.hosted.catalog.CatalogDiff.ToolChange;
import io.gen2spring.mcp.application.hosted.catalog.ToolCatalogStore.CatalogDetails;
import io.gen2spring.mcp.application.runtime.metadata.CanonicalRuntimeMetadataCodec;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeCredential;
import io.gen2spring.mcp.domain.runtime.RuntimeMetadataDocument.RuntimeTool;
import io.gen2spring.mcp.domain.specification.OpenApiDocument.ParameterLocation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class CatalogDiffService {
    private static final Comparator<ToolChange> CHANGE_ORDER = Comparator
            .comparing(ToolChange::toolName)
            .thenComparing(change -> change.kind().name())
            .thenComparing(ToolChange::field);
    private final ToolCatalogStore catalogs;
    private final CanonicalRuntimeMetadataCodec codec = new CanonicalRuntimeMetadataCodec();

    public CatalogDiffService(ToolCatalogStore catalogs) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    public CatalogDiff compare(AccountId owner, UUID sourceCatalogId, UUID targetCatalogId) {
        if (owner == null || sourceCatalogId == null || targetCatalogId == null) {
            throw new CatalogDiffQueryInvalid();
        }
        try {
            CatalogDetails source = load(owner, sourceCatalogId);
            CatalogDetails target = load(owner, targetCatalogId);
            if (!source.summary().version().familyId().equals(target.summary().version().familyId())) {
                throw new CatalogDiffNotFound();
            }
            validateCanonical(source);
            validateCanonical(target);

            List<ToolChange> changes = changes(source, target).stream().sorted(CHANGE_ORDER).toList();
            Compatibility compatibility = changes.stream()
                    .map(ToolChange::compatibility)
                    .anyMatch(value -> value == Compatibility.BREAKING)
                    ? Compatibility.BREAKING
                    : Compatibility.COMPATIBLE;
            CatalogEndpoint sourceEndpoint = endpoint(source);
            CatalogEndpoint targetEndpoint = endpoint(target);
            String checksum = CatalogDiffChecksum.calculate(
                    sourceEndpoint, targetEndpoint, compatibility, changes);
            return new CatalogDiff(sourceEndpoint, targetEndpoint, compatibility, changes, checksum);
        } catch (CatalogDiffNotFound | CatalogDiffUnavailable known) {
            throw known;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new CatalogDiffUnavailable();
        }
    }

    private CatalogDetails load(AccountId owner, UUID catalogId) {
        try {
            Optional<CatalogDetails> found = Objects.requireNonNull(catalogs.find(owner, catalogId));
            return found.orElseThrow(CatalogDiffNotFound::new);
        } catch (CatalogDiffNotFound missing) {
            throw missing;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new CatalogDiffUnavailable();
        }
    }

    private void validateCanonical(CatalogDetails details) {
        try {
            var encoded = codec.encode(details.metadata().document());
            if (!encoded.checksum().equals(details.metadata().checksum())
                    || !Arrays.equals(encoded.content(), details.metadata().content())) {
                throw new CatalogDiffUnavailable();
            }
        } catch (CatalogDiffUnavailable unavailable) {
            throw unavailable;
        } catch (Error fatal) {
            throw fatal;
        } catch (RuntimeException failure) {
            throw new CatalogDiffUnavailable();
        }
    }

    private List<ToolChange> changes(CatalogDetails source, CatalogDetails target) {
        TreeMap<String, RuntimeTool> sourceTools = index(source);
        TreeMap<String, RuntimeTool> targetTools = index(target);
        Set<String> names = new LinkedHashSet<>(sourceTools.keySet());
        names.addAll(targetTools.keySet());
        List<ToolChange> changes = new ArrayList<>();
        for (String name : names) {
            RuntimeTool before = sourceTools.get(name);
            RuntimeTool after = targetTools.get(name);
            if (before == null) {
                changes.add(change(name, TOOL_ADDED));
            } else if (after == null) {
                changes.add(change(name, TOOL_REMOVED));
            } else {
                compareTool(before, after, changes);
            }
        }
        return changes;
    }

    private TreeMap<String, RuntimeTool> index(CatalogDetails details) {
        TreeMap<String, RuntimeTool> indexed = new TreeMap<>();
        for (RuntimeTool tool : details.metadata().document().tools()) {
            if (indexed.put(tool.name(), tool) != null) {
                throw new CatalogDiffUnavailable();
            }
        }
        return indexed;
    }

    private void compareTool(RuntimeTool before, RuntimeTool after, List<ToolChange> changes) {
        String name = before.name();
        if (!before.description().equals(after.description())) {
            changes.add(change(name, DESCRIPTION_CHANGED));
        }
        if (!before.inputSchema().equals(after.inputSchema())) {
            changes.add(change(name, INPUT_CHANGED));
        }
        if (!before.outputKind().equals(after.outputKind())
                || !before.outputSchema().equals(after.outputSchema())) {
            ChangeKind output = before.outputKind().equals(after.outputKind())
                            && outputChange(before.outputSchema(), after.outputSchema()) == OutputChange.OPTIONAL_ADDITION
                    ? OUTPUT_OPTIONAL_PROPERTY_ADDED
                    : OUTPUT_CHANGED;
            changes.add(change(name, output));
        }
        if (!before.operationId().equals(after.operationId()) || !before.http().equals(after.http())) {
            changes.add(change(name, HTTP_CHANGED));
        }
        if (!Objects.equals(before.responseNormalization(), after.responseNormalization())
                || !Objects.equals(before.retry(), after.retry())
                || !Objects.equals(before.pagination(), after.pagination())) {
            changes.add(change(name, POLICY_CHANGED));
        }
        if (!credentialSignatures(before.credentials()).equals(credentialSignatures(after.credentials()))) {
            changes.add(change(name, CREDENTIAL_CHANGED));
        }
    }

    private List<CredentialSignature> credentialSignatures(List<RuntimeCredential> credentials) {
        return credentials.stream()
                .map(credential -> new CredentialSignature(
                        credential.credentialSlot(),
                        credential.targetLocation(),
                        credential.targetLocation() == ParameterLocation.HEADER
                                ? credential.targetName().toLowerCase(Locale.ROOT)
                                : credential.targetName(),
                        credential.required()))
                .sorted(Comparator.comparing(CredentialSignature::slot)
                        .thenComparing(value -> value.location().name())
                        .thenComparing(CredentialSignature::targetName)
                        .thenComparing(CredentialSignature::required))
                .toList();
    }

    private OutputChange outputChange(Map<String, Object> before, Map<String, Object> after) {
        if (before.equals(after)) {
            return OutputChange.IDENTICAL;
        }
        if ("object".equals(before.get("type")) && "object".equals(after.get("type"))) {
            return objectOutputChange(before, after);
        }
        if ("array".equals(before.get("type")) && "array".equals(after.get("type"))) {
            if (!without(before, "items").equals(without(after, "items"))) {
                return OutputChange.BREAKING;
            }
            return outputChange(map(before.get("items")), map(after.get("items")));
        }
        if (before.containsKey("anyOf") && after.containsKey("anyOf")) {
            if (!without(before, "anyOf").equals(without(after, "anyOf"))) {
                return OutputChange.BREAKING;
            }
            List<?> left = list(before.get("anyOf"));
            List<?> right = list(after.get("anyOf"));
            if (left.size() != right.size()) {
                return OutputChange.BREAKING;
            }
            boolean addition = false;
            for (int index = 0; index < left.size(); index++) {
                OutputChange nested = outputChange(map(left.get(index)), map(right.get(index)));
                if (nested == OutputChange.BREAKING) {
                    return nested;
                }
                addition |= nested == OutputChange.OPTIONAL_ADDITION;
            }
            return addition ? OutputChange.OPTIONAL_ADDITION : OutputChange.BREAKING;
        }
        return OutputChange.BREAKING;
    }

    private OutputChange objectOutputChange(Map<String, Object> before, Map<String, Object> after) {
        if (!without(before, "properties").equals(without(after, "properties"))) {
            return OutputChange.BREAKING;
        }
        Map<String, Object> left = map(before.get("properties"));
        Map<String, Object> right = map(after.get("properties"));
        if (!right.keySet().containsAll(left.keySet())) {
            return OutputChange.BREAKING;
        }
        Set<?> required = Set.copyOf(list(after.get("required")));
        boolean addition = false;
        for (Map.Entry<String, Object> entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                if (required.contains(entry.getKey())) {
                    return OutputChange.BREAKING;
                }
                addition = true;
                continue;
            }
            OutputChange nested = outputChange(map(left.get(entry.getKey())), map(entry.getValue()));
            if (nested == OutputChange.BREAKING) {
                return nested;
            }
            addition |= nested == OutputChange.OPTIONAL_ADDITION;
        }
        return addition ? OutputChange.OPTIONAL_ADDITION : OutputChange.BREAKING;
    }

    private Map<String, Object> without(Map<String, Object> source, String key) {
        TreeMap<String, Object> result = new TreeMap<>(source);
        result.remove(key);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new CatalogDiffUnavailable();
        }
        return (Map<String, Object>) map;
    }

    private List<?> list(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new CatalogDiffUnavailable();
        }
        return list;
    }

    private ToolChange change(String toolName, ChangeKind kind) {
        return new ToolChange(toolName, kind, kind.field());
    }

    private CatalogEndpoint endpoint(CatalogDetails details) {
        return new CatalogEndpoint(
                details.summary().catalogId(),
                details.summary().version().revision(),
                details.summary().metadataChecksum(),
                details.specificationChecksum());
    }

    private enum OutputChange {
        IDENTICAL,
        OPTIONAL_ADDITION,
        BREAKING
    }

    private record CredentialSignature(
            String slot,
            ParameterLocation location,
            String targetName,
            boolean required) {}

    public static final class CatalogDiffQueryInvalid extends RuntimeException {
        public CatalogDiffQueryInvalid() {
            super("The Catalog diff query is invalid", null, false, false);
        }
    }

    public static final class CatalogDiffNotFound extends RuntimeException {
        public CatalogDiffNotFound() {
            super("The Catalog diff was not found", null, false, false);
        }
    }

    public static final class CatalogDiffUnavailable extends RuntimeException {
        public CatalogDiffUnavailable() {
            super("The Catalog diff is unavailable", null, false, false);
        }
    }
}

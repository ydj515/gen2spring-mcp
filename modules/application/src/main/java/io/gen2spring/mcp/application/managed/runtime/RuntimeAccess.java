package io.gen2spring.mcp.application.managed.runtime;

import io.gen2spring.mcp.domain.platform.runtime.ManagedRuntimeInstance;
import io.gen2spring.mcp.domain.platform.runtime.RuntimeGrantId;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

public record RuntimeAccess(
        ManagedRuntimeInstance instance,
        Optional<RuntimeGrantId> grantId,
        String principal,
        Set<String> allowedTools,
        int requestsPerMinute,
        boolean ownerGrant,
        String policyChecksum) {
    private static final Pattern PRINCIPAL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}");
    private static final Pattern TOOL = Pattern.compile("[a-z][a-z0-9_]{0,127}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    public RuntimeAccess {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(grantId, "grantId");
        if (principal == null || !PRINCIPAL.matcher(principal).matches()
                || allowedTools == null || allowedTools.isEmpty()
                || allowedTools.stream().anyMatch(value -> value == null || !TOOL.matcher(value).matches())
                || requestsPerMinute < 1 || requestsPerMinute > 6000
                || ownerGrant != grantId.isEmpty()
                || policyChecksum == null || !HASH.matcher(policyChecksum).matches()) {
            throw new IllegalArgumentException("Runtime access is invalid");
        }
        allowedTools = Collections.unmodifiableSet(new TreeSet<>(allowedTools));
    }
}

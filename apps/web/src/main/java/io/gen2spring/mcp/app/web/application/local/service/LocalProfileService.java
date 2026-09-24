package io.gen2spring.mcp.app.web.application.local.service;

import io.gen2spring.mcp.domain.profile.CompatibilityCatalog;
import io.gen2spring.mcp.domain.profile.CompatibilityNotice;
import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.List;
import java.util.Objects;

/** Read-only profile query used by the local and hosted HTTP views. */
public final class LocalProfileService {
    private final CompatibilityCatalog catalog;

    public LocalProfileService(CompatibilityCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public List<CompatibilityProfile> profiles() {
        return catalog.profiles().profiles();
    }

    public List<CompatibilityNotice> notices() {
        return catalog.notices();
    }
}

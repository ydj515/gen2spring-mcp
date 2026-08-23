package io.gen2spring.mcp.domain.profile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CompatibilityProfileRegistry {
    private static final String INVALID_PROFILES_MESSAGE = "Compatibility profiles are invalid";

    private final List<CompatibilityProfile> profiles;
    private final Map<String, CompatibilityProfile> profilesById;

    private CompatibilityProfileRegistry(
            List<CompatibilityProfile> profiles,
            Map<String, CompatibilityProfile> profilesById) {
        this.profiles = profiles;
        this.profilesById = profilesById;
    }

    public static CompatibilityProfileRegistry defaults() {
        return CompatibilityCatalog.defaults().profiles();
    }

    public static CompatibilityProfileRegistry of(List<CompatibilityProfile> profiles) {
        if (profiles == null) {
            throw invalidProfiles();
        }

        List<CompatibilityProfile> sortedProfiles = new ArrayList<>(profiles.size());
        Set<String> ids = new HashSet<>();
        Set<CompatibilityProfile.TargetPlatform> targets = new HashSet<>();
        for (CompatibilityProfile profile : profiles) {
            validate(profile);
            if (!ids.add(profile.id()) || !targets.add(profile.target())) {
                throw invalidProfiles();
            }
            sortedProfiles.add(profile);
        }
        sortedProfiles.sort(Comparator.comparing(CompatibilityProfile::id));

        Map<String, CompatibilityProfile> profilesById = new LinkedHashMap<>();
        for (CompatibilityProfile profile : sortedProfiles) {
            profilesById.put(profile.id(), profile);
        }
        return new CompatibilityProfileRegistry(List.copyOf(sortedProfiles), Map.copyOf(profilesById));
    }

    public List<CompatibilityProfile> profiles() {
        return profiles;
    }

    public Optional<CompatibilityProfile> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profilesById.get(id));
    }

    private static void validate(CompatibilityProfile profile) {
        if (profile == null
                || isBlank(profile.id())
                || profile.target() == null
                || isBlank(profile.generatorModule())
                || isBlank(profile.templateVersion())
                || isBlank(profile.runtimeVersion())
                || profile.buildToolchain() == null
                || isBlank(profile.buildToolchain().distributionVersion())
                || isBlank(profile.buildToolchain().wrapperVersion())
                || isBlank(profile.containerImage())) {
            throw invalidProfiles();
        }

        CompatibilityProfile.TargetPlatform target = profile.target();
        if (target.javaVersion() <= 0
                || isBlank(target.springBootVersion())
                || isBlank(target.springAiVersion())
                || isBlank(target.buildTool())
                || isBlank(target.webStack())
                || isBlank(target.programmingModel())
                || isBlank(target.transport())) {
            throw invalidProfiles();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalidProfiles() {
        return new IllegalArgumentException(INVALID_PROFILES_MESSAGE);
    }

}

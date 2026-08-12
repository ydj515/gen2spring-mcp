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
        return Defaults.INSTANCE;
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
                || isBlank(profile.gradleVersion())
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

    private static final class Defaults {
        private static final String JAVA_17_IMAGE = "eclipse-temurin:17.0.19_10-jre-noble@sha256:"
                + "543aebd60ff1deb9e906a8d4b117a7eda68a7f8e0d71041db2b5839d7fa057b8";
        private static final String JAVA_21_IMAGE = "eclipse-temurin:21.0.11_10-jre-noble@sha256:"
                + "373787d1d45a87f084fda43e7de0e9acf5eedee049446efac738f13587ec4c64";

        private static final CompatibilityProfileRegistry INSTANCE = CompatibilityProfileRegistry.of(List.of(
                profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1", "spring-ai-1-v2",
                        17, JAVA_17_IMAGE),
                profile("spring-ai-1.1", "3.5.16", "1.1.8", "generator-spring-ai-1", "spring-ai-1-v2",
                        21, JAVA_21_IMAGE),
                profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2", "spring-ai-2-v3",
                        17, JAVA_17_IMAGE),
                profile("spring-ai-2.0", "4.1.0", "2.0.0", "generator-spring-ai-2", "spring-ai-2-v3",
                        21, JAVA_21_IMAGE)));

        private static CompatibilityProfile profile(
                String family,
                String springBootVersion,
                String springAiVersion,
                String generatorModule,
                String templateVersion,
                int javaVersion,
                String containerImage) {
            return new CompatibilityProfile(
                    family + "-java" + javaVersion + "-mvc-streamable",
                    new CompatibilityProfile.TargetPlatform(
                            javaVersion,
                            springBootVersion,
                            springAiVersion,
                            "GRADLE_KOTLIN",
                            "MVC",
                            "SYNC",
                            "STREAMABLE_HTTP"),
                    generatorModule,
                    templateVersion,
                    "0.3.0",
                    "9.6.1",
                    containerImage);
        }

        private Defaults() {}
    }
}

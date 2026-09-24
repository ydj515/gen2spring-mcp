package io.gen2spring.mcp.adapter.emitter.support.project;

import io.gen2spring.mcp.domain.profile.CompatibilityProfile;
import java.util.List;
import java.util.Objects;

public record ProjectScaffoldModel(
        String groupId,
        String artifactId,
        String packageName,
        String applicationClassName,
        CompatibilityProfile profile,
        List<Dependency> dependencies,
        String applicationYaml,
        ProjectDocumentation projectDescription) {
    public ProjectScaffoldModel {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(applicationClassName, "applicationClassName");
        Objects.requireNonNull(profile, "profile");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        Objects.requireNonNull(applicationYaml, "applicationYaml");
        Objects.requireNonNull(projectDescription, "projectDescription");
    }

    public record Dependency(String groupId, String artifactId, Scope scope, String version) {
        public Dependency(String groupId, String artifactId, Scope scope) {
            this(groupId, artifactId, scope, null);
        }
        public Dependency {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(scope, "scope");
        }

        public String coordinate() {
            return groupId + ":" + artifactId + (version == null ? "" : ":" + version);
        }
    }

    public enum Scope {
        IMPLEMENTATION("implementation"),
        RUNTIME_ONLY("runtimeOnly"),
        TEST_IMPLEMENTATION("testImplementation");

        private final String gradleConfiguration;

        Scope(String gradleConfiguration) {
            this.gradleConfiguration = gradleConfiguration;
        }

        public String gradleConfiguration() {
            return gradleConfiguration;
        }
    }

    public record ProjectDocumentation(
            String summary,
            List<String> providerEnvironmentVariables,
            String dockerEnvironment,
            String tools,
            String observability,
            String responseHandling) {
        public ProjectDocumentation {
            Objects.requireNonNull(summary, "summary");
            providerEnvironmentVariables = List.copyOf(
                    Objects.requireNonNull(providerEnvironmentVariables, "providerEnvironmentVariables"));
            Objects.requireNonNull(dockerEnvironment, "dockerEnvironment");
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(observability, "observability");
            Objects.requireNonNull(responseHandling, "responseHandling");
        }
    }
}

ARG JDK17_IMAGE=eclipse-temurin:17.0.19_10-jdk-noble@sha256:f8857ccb2fe800666293d0da2526d162774e1314adfffb274fbadb877679d7a1
ARG JDK21_IMAGE=eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15

FROM ${JDK17_IMAGE} AS jdk17

FROM ${JDK21_IMAGE} AS builder
WORKDIR /workspace
COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk17
COPY . .
RUN ./gradlew :apps:cli:installDist --no-daemon --non-interactive
ENV GEN2SPRING_JAVA_17_HOME=/opt/java/openjdk17
ENV GEN2SPRING_JAVA_21_HOME=/opt/java/openjdk
RUN set -eu; \
    index=0; \
    for config in \
      weather-generation-spring-ai1-java17.yaml \
      weather-generation-spring-ai1-java21.yaml \
      weather-generation-java17.yaml \
      weather-generation.yaml; do \
        index=$((index + 1)); \
        apps/cli/build/install/openapi-mcp/bin/openapi-mcp generate \
          --spec apps/cli/src/integrationTest/resources/openapi/weather-api.yaml \
          --config "apps/cli/src/integrationTest/resources/config/${config}" \
          --output "/tmp/seed-${index}"; \
        rm -rf "/tmp/seed-${index}" "/tmp/seed-${index}.zip"; \
    done

FROM ${JDK21_IMAGE}
COPY --from=jdk17 /opt/java/openjdk /opt/java/openjdk17
COPY --from=builder /workspace/apps/cli/build/install/openapi-mcp /opt/gen2spring/openapi-mcp
COPY --from=builder /root/.gradle/caches /opt/gen2spring/gradle-seed/caches
COPY --from=builder /root/.gradle/wrapper /opt/gen2spring/gradle-seed/wrapper
COPY deploy/hosted/runner/job-entrypoint.sh /opt/gen2spring/job-entrypoint
COPY deploy/hosted/runner/job-result.schema.json /opt/gen2spring/job-result.schema.json
RUN set -eu; \
    groupadd --gid 10001 gen2spring; \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin gen2spring; \
    chown -R 10001:10001 /opt/gen2spring; \
    chmod 0500 /opt/gen2spring/job-entrypoint; \
    chmod -R a-w /opt/gen2spring/openapi-mcp /opt/gen2spring/gradle-seed; \
    rm -f /usr/bin/apt /usr/bin/apt-* /usr/bin/dpkg /usr/bin/dpkg-*; \
    rm -rf /etc/apt /var/cache/apt /var/lib/apt /var/lib/dpkg
ENV JAVA_HOME=/opt/java/openjdk
ENV GEN2SPRING_JAVA_17_HOME=/opt/java/openjdk17
ENV GEN2SPRING_JAVA_21_HOME=/opt/java/openjdk
ENV GRADLE_RO_DEP_CACHE=/opt/gen2spring/gradle-seed/caches
USER 10001:10001
ENTRYPOINT ["/opt/gen2spring/job-entrypoint"]

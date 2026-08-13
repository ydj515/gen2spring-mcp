ARG JDK21_IMAGE=eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15

FROM ${JDK21_IMAGE} AS builder
WORKDIR /workspace
COPY . .
RUN ./gradlew :apps:import-runner:installDist --no-daemon --non-interactive

FROM ${JDK21_IMAGE}
LABEL io.gen2spring.runner.protocol="1"
COPY --from=builder /workspace/apps/import-runner/build/install/import-runner /opt/gen2spring/import-runner
RUN set -eu; \
    groupadd --gid 10001 gen2spring; \
    useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin gen2spring; \
    chown -R 10001:10001 /opt/gen2spring; \
    chmod -R a-w /opt/gen2spring/import-runner; \
    rm -f /usr/bin/apt /usr/bin/apt-* /usr/bin/dpkg /usr/bin/dpkg-*; \
    rm -rf /etc/apt /var/cache/apt /var/lib/apt /var/lib/dpkg
USER 10001:10001
ENTRYPOINT ["/opt/gen2spring/import-runner/bin/import-runner"]

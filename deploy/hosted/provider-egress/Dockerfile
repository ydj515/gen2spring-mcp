FROM eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15 AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :apps:provider-egress:bootJar --no-daemon --non-interactive \
    && mkdir -p /out \
    && find apps/provider-egress/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' /out/app.jar ';'

FROM eclipse-temurin:21.0.11_10-jre-noble@sha256:ca397720325ceefe39ce397f186759fc87d9efafb2dc4ce53315980844c2f4f2
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin gen2spring \
    && mkdir -p /app \
    && chown -R 10001:10001 /app
COPY --from=builder --chown=10001:10001 /out/app.jar /app/app.jar
USER 10001:10001
EXPOSE 9443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

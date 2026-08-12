FROM eclipse-temurin:21.0.11_10-jdk-noble@sha256:a871f3e3caddad75608fd4531ed8bbca5cc42a27dc1da3ea3a2e554772b0ee15 AS builder
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew :apps:worker:bootJar --no-daemon --non-interactive \
    && mkdir -p /out \
    && find apps/worker/build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' /out/app.jar ';'

FROM eclipse-temurin:21.0.11_10-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS java
FROM docker:28.5.2-cli@sha256:625d9431a9f54c5a2bc90f24f0e1c3d55b1349fd857dd85035f98c2c9acbdd4d
COPY --from=java /opt/java/openjdk /opt/java/openjdk
COPY --from=builder /out/app.jar /app/app.jar
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:$PATH
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

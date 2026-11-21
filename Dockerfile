# syntax=docker/dockerfile:1.7
#
# Phase 15: ONE Dockerfile for all six Spring Boot services. The service is a build argument:
#   docker build --build-arg SERVICE=payment-service --build-arg PORT=8083 -t banking/payment-service .
# (docker compose --profile app build does this for every service.)
#
# Stage 1 builds the jar with Maven, stage 2 splits it into layers, stage 3 is the small runtime
# image: a JRE (no JDK, no Maven, no sources), a non-root user, the app in cache-friendly layers.

ARG BUILD_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.13_9-jre-noble

# ---------------------------------------------------------------------------- 1. build
FROM ${BUILD_IMAGE} AS build
ARG SERVICE
WORKDIR /workspace

# The reactor needs every module's pom; copy only poms first so this layer stays cached
# until a pom changes.
COPY pom.xml ./
COPY user-service/pom.xml user-service/
COPY account-service/pom.xml account-service/
COPY payment-service/pom.xml payment-service/
COPY transaction-service/pom.xml transaction-service/
COPY notification-service/pom.xml notification-service/
COPY api-gateway/pom.xml api-gateway/

COPY ${SERVICE}/src ${SERVICE}/src

# The cache mount keeps ~/.m2 between builds (and between the six services) WITHOUT putting it
# in the image. sharing=locked: found when Compose built all six at once: a Maven repository isn't
# safe for concurrent writers (half-downloaded files → DependencyResolutionException), so builds
# take turns on the cache. Tests are not run here: they need Docker themselves (Testcontainers) and already
# ran in `mvn verify` / CI before images are built.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests \
 && cp ${SERVICE}/target/${SERVICE}-*.jar /workspace/app.jar

# ---------------------------------------------------------------------------- 2. layers
# Spring Boot splits the fat jar: dependencies change rarely, our classes change every build.
# Separate image layers mean a code change re-ships ~100 KB, not ~80 MB of libraries.
FROM ${BUILD_IMAGE} AS layers
WORKDIR /workspace
COPY --from=build /workspace/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---------------------------------------------------------------------------- 3. runtime
FROM ${RUNTIME_IMAGE}
ARG SERVICE
ARG PORT=8080

LABEL org.opencontainers.image.title="banking-${SERVICE}" \
      org.opencontainers.image.source="banking-platform/${SERVICE}"

# Never run as root: a compromised app would otherwise own the container (and more, if
# it ever escapes). Fixed UID/GID so Kubernetes can enforce runAsNonRoot (Phase 16).
RUN groupadd --system --gid 10001 spring \
 && useradd --system --uid 10001 --gid spring --no-create-home --shell /usr/sbin/nologin spring

WORKDIR /app
# Least-changing layers first.
COPY --from=layers --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=spring:spring /workspace/extracted/application/ ./

USER 10001:10001

# Size the JVM from the CONTAINER's memory limit, not the host's: heap = 75 % of the limit,
# the rest for metaspace, threads, direct buffers. On OutOfMemoryError exit (and get restarted)
# instead of limping on in an unknown state.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    HEALTH_URL="http://localhost:${PORT}/actuator/health/readiness"

EXPOSE ${PORT}

# Docker's own health status (docker ps, Compose depends_on). Kubernetes ignores it and uses
# its probes instead (Phase 16).
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -fsS "$HEALTH_URL" > /dev/null || exit 1

# Exec form: java is PID 1 and receives SIGTERM directly → Spring's graceful shutdown runs.
# (Shell form would wrap it in /bin/sh, which doesn't forward signals.)
ENTRYPOINT ["java", "-jar", "app.jar"]

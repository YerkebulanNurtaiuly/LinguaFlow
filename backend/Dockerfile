# syntax=docker/dockerfile:1

# Production image of the backend (OPS-01): a layered Spring Boot app on a slim JRE,
# running as a non-root user, with a health check on the readiness probe.

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached until pom.xml changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY lombok.config ./
COPY src src
# Tests and the QA-06 gates (Checkstyle, JaCoCo) run in CI's backend job, not in the image build
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests -Dcheckstyle.skip -Djacoco.skip \
 && cp target/*.jar app.jar \
 # Split the jar into layers, least to most often changed, so an image update only
 # ships the small application layer
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="LinguaFlow backend" \
      org.opencontainers.image.source="https://github.com/YerkebulanNurtaiuly/LinguaFlow" \
      org.opencontainers.image.licenses="MIT"
RUN addgroup -S app && adduser -S -G app -H -s /sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
EXPOSE 8080
# Ready = the app has started and the database answers. Flyway runs at startup, so give it time.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
# Heap sized from the container's memory limit, not the host's; a dead heap restarts the container.
# Extra JVM options can be passed with JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]

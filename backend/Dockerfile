# syntax=docker/dockerfile:1

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
 && cp target/*.jar app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
# Size the heap from the container's memory limit, not the host's
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

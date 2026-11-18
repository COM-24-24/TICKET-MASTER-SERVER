# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app \
    && mkdir -p /app/uploads \
    && chown -R app:app /app
ENV TZ=Africa/Blantyre \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Africa/Blantyre -XX:MaxRAMPercentage=75"
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
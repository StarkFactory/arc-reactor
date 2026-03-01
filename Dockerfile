# ===========================================
# Arc Reactor - Multi-stage Docker Build
# ===========================================
# Usage:
#   docker build -t arc-reactor .
#   docker run -e GEMINI_API_KEY=your-key -p 8080:8080 arc-reactor

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# PostgreSQL/JDBC is required at runtime.
# Set ENABLE_DB=false only for specialized library-only builds.
ARG ENABLE_DB=true

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY arc-core/build.gradle.kts arc-core/
COPY arc-app/build.gradle.kts arc-app/
COPY arc-web/build.gradle.kts arc-web/
COPY arc-admin/build.gradle.kts arc-admin/
COPY arc-slack/build.gradle.kts arc-slack/
COPY arc-error-report/build.gradle.kts arc-error-report/
COPY arc-google/build.gradle.kts arc-google/
COPY arc-teams/build.gradle.kts arc-teams/
RUN ./gradlew dependencies --no-daemon || true
COPY arc-core/src/ arc-core/src/
COPY arc-web/src/ arc-web/src/
COPY arc-admin/src/ arc-admin/src/
COPY arc-slack/src/ arc-slack/src/
COPY arc-error-report/src/ arc-error-report/src/
COPY arc-google/src/ arc-google/src/
COPY arc-teams/src/ arc-teams/src/
RUN GRADLE_ARGS=":arc-app:bootJar --no-daemon -x test"; \
    if [ "$ENABLE_DB" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pdb=true"; fi; \
    ./gradlew $GRADLE_ARGS

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S arc && adduser -S arc -G arc
USER arc

COPY --from=build /workspace/arc-app/build/libs/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

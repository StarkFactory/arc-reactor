# ===========================================
# Arc Reactor - Multi-stage Docker Build
# ===========================================
# Usage:
#   docker build -t arc-reactor .
#   docker run -e GEMINI_API_KEY=your-key -p 8080:8080 arc-reactor

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Pass --build-arg ENABLE_DB=true to include PostgreSQL/JDBC in runtime
ARG ENABLE_DB=false
# Pass --build-arg ENABLE_AUTH=true to include JWT auth in runtime
ARG ENABLE_AUTH=false

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY arc-core/build.gradle.kts arc-core/
COPY arc-app/build.gradle.kts arc-app/
RUN ./gradlew dependencies --no-daemon || true
COPY arc-core/src/ arc-core/src/
RUN GRADLE_ARGS=":arc-app:bootJar --no-daemon -x test"; \
    if [ "$ENABLE_DB" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pdb=true"; fi; \
    if [ "$ENABLE_AUTH" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pauth=true"; fi; \
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

# ===========================================
# Arc Reactor - Multi-stage Docker Build
# ===========================================
# Usage:
#   docker build -t arc-reactor .
#   docker run -e GEMINI_API_KEY=your-key -p 8080:8080 arc-reactor

# Stage 1: Build
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Pass --build-arg ENABLE_DB=true to include PostgreSQL/JDBC in runtime
ARG ENABLE_DB=false
# Pass --build-arg ENABLE_AUTH=true to include JWT auth in runtime
ARG ENABLE_AUTH=false

COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN GRADLE_ARGS="bootJar --no-daemon -x test"; \
    if [ "$ENABLE_DB" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pdb=true"; fi; \
    if [ "$ENABLE_AUTH" = "true" ]; then GRADLE_ARGS="$GRADLE_ARGS -Pauth=true"; fi; \
    ./gradlew $GRADLE_ARGS

# Stage 2: Run
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S arc && adduser -S arc -G arc
USER arc

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

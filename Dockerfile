# ===========================================
# Arc Reactor - Multi-stage Docker Build
# ===========================================
# 사용법:
#   docker build -t arc-reactor .
#   docker run -e GEMINI_API_KEY=your-key -p 8080:8080 arc-reactor

# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S arc && adduser -S arc -G arc
USER arc

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

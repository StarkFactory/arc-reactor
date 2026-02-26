plugins {
    id("org.springframework.boot")
}

val springAiVersion = "1.1.2"

dependencies {
    implementation(project(":arc-core"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Cache
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")

    // Metrics + Tracing
    implementation("io.micrometer:micrometer-core")
    implementation("io.micrometer:micrometer-tracing")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    // OpenTelemetry SDK
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.44.1"))
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-sdk-trace")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.opentelemetry:opentelemetry-context")
    implementation("io.opentelemetry:opentelemetry-extension-trace-propagators")

    // Database
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // API Documentation (annotations only — runtime provided by arc-web's springdoc)
    compileOnly("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.6")

    // Spring AI (for observation auto-config detection)
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    compileOnly("org.springframework.ai:spring-ai-model")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.h2database:h2")
}

// Library module: no executable jar
tasks.bootJar {
    enabled = false
}

tasks.jar {
    enabled = true
    archiveClassifier.set("plain")
}

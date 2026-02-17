plugins {
    id("org.springframework.boot")
}

val springAiVersion = "1.1.2"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    // Arc Reactor Core
    implementation(project(":arc-core"))

    // Slack API Client
    implementation("com.slack.api:slack-api-client:1.47.0")
    implementation("com.slack.api:slack-api-model-kotlin-extension:1.47.0")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Spring Boot WebFlux (for WebFilter, WebClient, RestController)
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("io.micrometer:micrometer-core")

    // Swagger (for @Tag, @Operation)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.6")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}

// Library module: no bootJar, produce regular jar
tasks.named("bootJar") {
    enabled = false
}
tasks.jar {
    enabled = true
}

plugins {
    id("org.springframework.boot")
}

val springAiVersion = "1.1.2"

dependencies {
    // Arc Reactor Core (agent engine, stores, auth, etc.)
    implementation(project(":arc-core"))

    // Spring Boot WebFlux
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // API Documentation (SpringDoc OpenAPI + Swagger UI for WebFlux)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.6")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Spring AI BOM (for VectorStore, Document types used by DocumentController)
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    compileOnly("org.springframework.ai:spring-ai-vector-store")

    // Optional: JWT Auth (compileOnly since auth may not be enabled)
    compileOnly("io.jsonwebtoken:jjwt-api:0.13.0")
    compileOnly("org.springframework.security:spring-security-crypto")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-assertions-core:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.jsonwebtoken:jjwt-api:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.security:spring-security-crypto")
    testImplementation("org.springframework.ai:spring-ai-vector-store")
}

// Library module: no bootJar, produce regular jar
tasks.named("bootJar") {
    enabled = false
}
tasks.jar {
    enabled = true
}

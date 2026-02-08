import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.arc"
version = "1.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Spring AI 1.1.2
val springAiVersion = "1.1.2"

dependencies {
    // Spring Boot Core
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Spring AI BOM (platform for version management)
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))

    // Spring AI Core Model (base interfaces)
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.ai:spring-ai-client-chat")

    // MCP (Model Context Protocol) SDK
    implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")

    // Spring AI MCP Client Starter (optional - for auto-configuration)
    compileOnly("org.springframework.ai:spring-ai-starter-mcp-client")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Cache (for Rate Limiting)
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")

    // Optional: Micrometer (for metrics/observability)
    compileOnly("io.micrometer:micrometer-core")

    // LLM Providers
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")

    // Optional LLM Providers (switch to implementation when needed)
    compileOnly("org.springframework.ai:spring-ai-starter-model-openai")
    compileOnly("org.springframework.ai:spring-ai-starter-model-anthropic")

    // Optional: Google Vertex AI (alternative to google-genai)
    compileOnly("org.springframework.ai:spring-ai-starter-model-vertex-ai-gemini")

    // Spring AI Vector Store Core (for RAG)
    implementation("org.springframework.ai:spring-ai-vector-store")

    // Optional: Vector Store Providers (choose one)
    compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pinecone")
    compileOnly("org.springframework.ai:spring-ai-starter-vector-store-chroma")

    // Optional: JDBC + PostgreSQL (for JdbcMemoryStore)
    // Pass -Pdb=true to include in runtime classpath (e.g., Docker builds)
    if (project.hasProperty("db")) {
        implementation("org.springframework.boot:spring-boot-starter-jdbc")
        implementation("org.postgresql:postgresql")
        implementation("org.flywaydb:flyway-core")
        implementation("org.flywaydb:flyway-database-postgresql")
    } else {
        compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
        compileOnly("org.postgresql:postgresql")
        compileOnly("org.flywaydb:flyway-core")
        compileOnly("org.flywaydb:flyway-database-postgresql")
    }

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Application mode - fork and add your own tools
tasks.bootJar {
    enabled = true
}

tasks.jar {
    enabled = false
}

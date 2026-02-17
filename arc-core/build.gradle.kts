plugins {
    id("org.springframework.boot")
}

// Spring AI 1.1.2
val springAiVersion = "1.1.2"

dependencies {
    // Gateway modules (runtimeOnly — included in bootJar but no compile dependency)
    runtimeOnly(project(":arc-web"))
    runtimeOnly(project(":arc-slack"))
    runtimeOnly(project(":arc-discord"))
    runtimeOnly(project(":arc-line"))
    runtimeOnly(project(":arc-error-report"))

    // Spring Boot Core
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Spring AI BOM (platform for version management)
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))

    // Spring AI Core Model (base interfaces)
    implementation("org.springframework.ai:spring-ai-model")
    implementation("org.springframework.ai:spring-ai-client-chat")

    // MCP (Model Context Protocol) SDK
    implementation("io.modelcontextprotocol.sdk:mcp:0.17.2")

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
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // LLM Providers
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai-embedding")

    // Optional LLM Providers (switch to implementation when needed)
    compileOnly("org.springframework.ai:spring-ai-starter-model-openai")
    compileOnly("org.springframework.ai:spring-ai-starter-model-anthropic")

    // Optional: Google Vertex AI (alternative to google-genai)
    compileOnly("org.springframework.ai:spring-ai-starter-model-vertex-ai-gemini")

    // Spring AI Vector Store Core (for RAG)
    implementation("org.springframework.ai:spring-ai-vector-store")

    // Optional: Vector Store Providers (choose one, activated by -Pdb=true)
    compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pinecone")
    compileOnly("org.springframework.ai:spring-ai-starter-vector-store-chroma")

    // Optional: JDBC + PostgreSQL + PGVector (for JdbcMemoryStore + RAG)
    // Pass -Pdb=true to include in runtime classpath (e.g., Docker builds)
    if (project.hasProperty("db")) {
        implementation("org.springframework.boot:spring-boot-starter-jdbc")
        implementation("org.postgresql:postgresql")
        implementation("org.flywaydb:flyway-core")
        implementation("org.flywaydb:flyway-database-postgresql")
        implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    } else {
        compileOnly("org.springframework.boot:spring-boot-starter-jdbc")
        compileOnly("org.postgresql:postgresql")
        compileOnly("org.flywaydb:flyway-core")
        compileOnly("org.flywaydb:flyway-database-postgresql")
        compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    }

    // Optional: JWT Auth (JJWT + Spring Security Crypto for BCrypt)
    // Pass -Pauth=true to include in runtime classpath (e.g., Docker builds)
    if (project.hasProperty("auth")) {
        implementation("io.jsonwebtoken:jjwt-api:0.13.0")
        runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
        runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
        implementation("org.springframework.security:spring-security-crypto")
    } else {
        compileOnly("io.jsonwebtoken:jjwt-api:0.13.0")
        compileOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
        compileOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
        compileOnly("org.springframework.security:spring-security-crypto")
    }

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("com.h2database:h2")
    testImplementation("io.kotest:kotest-runner-junit5:6.1.3")
    testImplementation("io.kotest:kotest-assertions-core:6.1.3")
    testImplementation("io.kotest.extensions:kotest-extensions-spring:1.3.0")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.jsonwebtoken:jjwt-api:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    testRuntimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
    testImplementation("org.springframework.security:spring-security-crypto")
}

tasks.withType<Test> {
    useJUnitPlatform {
        // Exclude integration tests by default (require external dependencies like Node.js)
        // Run with: ./gradlew test -PincludeIntegration
        if (!project.hasProperty("includeIntegration")) {
            excludeTags("integration")
        }
    }
}

// Application mode - fork and add your own tools
tasks.bootJar {
    enabled = true
}

// Regular jar needed for submodule dependency (arc-slack uses project(":arc-core"))
tasks.jar {
    enabled = true
    archiveClassifier.set("plain")
}

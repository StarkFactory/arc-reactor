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

    // Swagger (for @Tag, @Operation)
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.8.6")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-assertions-core:6.1.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Library module: no bootJar, produce regular jar
tasks.named("bootJar") {
    enabled = false
}
tasks.jar {
    enabled = true
}

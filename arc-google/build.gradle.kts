plugins {
    id("org.springframework.boot")
}

dependencies {
    // Arc Reactor Core
    implementation(project(":arc-core"))

    // Spring Boot WebFlux
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Google API client libraries
    implementation("com.google.auth:google-auth-library-oauth2-http:1.30.0")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20250404-2.0.0")
    implementation("com.google.apis:google-api-services-sheets:v4-rev20250603-2.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20250511-2.0.0")
    implementation("com.google.apis:google-api-services-gmail:v1-rev20250331-2.0.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Library module: no bootJar, produce regular jar
tasks.named("bootJar") {
    enabled = false
}
tasks.jar {
    enabled = true
}

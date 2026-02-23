plugins {
    id("org.springframework.boot")
}

dependencies {
    // Arc Reactor Core
    implementation(project(":arc-core"))

    // Discord4J
    implementation("com.discord4j:discord4j-core:3.3.1")

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.10.2")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Library module: no bootJar, produce regular jar
tasks.named("bootJar") {
    enabled = false
}
tasks.jar {
    enabled = true
}

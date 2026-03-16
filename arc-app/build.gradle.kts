plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":arc-core"))
    // Enable Redis-backed optional runtime features (semantic cache / token revocation)
    runtimeOnly("org.springframework.boot:spring-boot-starter-data-redis")
    runtimeOnly(project(":arc-web"))
    runtimeOnly(project(":arc-slack"))
    runtimeOnly(project(":arc-error-report"))
    runtimeOnly(project(":arc-admin"))
}

springBoot {
    mainClass.set("com.arc.reactor.ArcReactorApplicationKt")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn(
        ":arc-core:jar",
        ":arc-web:jar",
        ":arc-slack:jar",
        ":arc-error-report:jar",
        ":arc-admin:jar"
    )
}

tasks.jar {
    enabled = false
}

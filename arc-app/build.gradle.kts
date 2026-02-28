plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":arc-core"))
    runtimeOnly(project(":arc-web"))
    runtimeOnly(project(":arc-slack"))
runtimeOnly(project(":arc-error-report"))
    runtimeOnly(project(":arc-admin"))
}

springBoot {
    mainClass.set("com.arc.reactor.ArcReactorApplicationKt")
}

tasks.jar {
    enabled = false
}

plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":arc-core"))
    runtimeOnly(project(":arc-web"))
}

springBoot {
    mainClass.set("com.arc.reactor.ArcReactorApplicationKt")
}

tasks.jar {
    enabled = false
}

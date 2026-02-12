rootProject.name = "arc-reactor"

include("arc-core", "arc-web", "arc-slack", "arc-discord", "arc-line")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

rootProject.name = "arc-reactor"

include("arc-core", "arc-web", "arc-slack", "arc-error-report", "arc-google", "arc-teams")
include("arc-admin")
include("arc-app")
include("arc-app-lite")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

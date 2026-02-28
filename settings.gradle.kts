rootProject.name = "arc-reactor"

include("arc-core", "arc-web", "arc-slack", "arc-error-report")
include("arc-admin")
include("arc-app")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
    }
}

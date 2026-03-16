import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val coroutinesVersion = "1.10.2"
val jacksonVersion = "2.21.1"       // GHSA-72hv-8253-57qq (async parser DoS)
val jackson3Version = "3.1.0"       // GHSA-72hv-8253-57qq (async parser DoS)

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "3.5.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.arc"
    version = "5.7.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")

    // Override Jackson 2.x BOM managed by Spring Boot (GHSA-72hv-8253-57qq)
    extra["jackson-bom.version"] = jacksonVersion

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test> {
        maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        jvmArgs(
            "-XX:+UseParallelGC",
            "-XX:+TieredCompilation",
            "-XX:TieredStopAtLevel=1"
        )
        useJUnitPlatform {
            // Keep integration tests opt-in across all modules.
            // Run with: ./gradlew test -PincludeIntegration
            if (!project.hasProperty("includeIntegration")) {
                excludeTags("integration")
            }
            // Large matrix/fuzz suites are opt-in for local/CI speed.
            // Run with: ./gradlew test -PincludeMatrix
            if (!project.hasProperty("includeMatrix")) {
                excludeTags("matrix")
            }
            // External dependency integration tests (network/npx/docker) stay explicit.
            // Run with: ./gradlew test -PincludeIntegration -PincludeExternalIntegration
            if (!project.hasProperty("includeExternalIntegration")) {
                excludeTags("external")
            }
            // Run ONLY safety-tagged tests (used by CI safety gate).
            // Run with: ./gradlew test -PincludeSafety
            if (project.hasProperty("includeSafety")) {
                includeTags("safety")
            }
        }
        testLogging {
            events("failed")
            showExceptions = true
            showCauses = true
            showStackTraces = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" &&
                requested.name.startsWith("kotlinx-coroutines")
            ) {
                useVersion(coroutinesVersion)
                because("Align coroutine modules to a single version and avoid mixed runtime classpath")
            }
            // Jackson 3.x: force upgrade for GHSA-72hv-8253-57qq (async parser DoS)
            if (requested.group == "tools.jackson.core" ||
                requested.group == "tools.jackson" ||
                requested.group == "tools.jackson.dataformat"
            ) {
                useVersion(jackson3Version)
                because("GHSA-72hv-8253-57qq: async parser number-length bypass DoS")
            }
        }
    }

    // Keep root `./gradlew bootRun` deterministic by exposing runnable boot tasks only from :arc-app.
    tasks.matching { it.name == "bootRun" || it.name == "resolveMainClassName" }.configureEach {
        enabled = project.name == "arc-app"
    }
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val coroutinesVersion = "1.10.2"

plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "3.5.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.arc"
    version = "3.9.7"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")

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
        }
    }
}

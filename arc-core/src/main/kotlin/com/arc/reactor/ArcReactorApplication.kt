package com.arc.reactor

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.SimpleCommandLinePropertySource
import org.springframework.core.env.StandardEnvironment

@SpringBootApplication
class ArcReactorApplication

fun main(args: Array<String>) {
    buildArcReactorApplication(args).run(*args)
}

internal fun buildArcReactorApplication(
    args: Array<String>,
    environment: ConfigurableEnvironment = StandardEnvironment()
): SpringApplication {
    if (args.isNotEmpty()) {
        environment.propertySources.addFirst(SimpleCommandLinePropertySource(*args))
    }

    val application = SpringApplication(ArcReactorApplication::class.java)
    resolveOptionalDatasourceExcludes(environment)?.let { merged ->
        application.setDefaultProperties(mapOf("spring.autoconfigure.exclude" to merged))
    }
    return application
}

internal fun resolveOptionalDatasourceExcludes(
    environment: ConfigurableEnvironment
): String? {
    val datasourceUrl = environment.getProperty("spring.datasource.url")?.trim()
    if (!datasourceUrl.isNullOrBlank()) {
        return null
    }

    val existing = environment.getProperty("spring.autoconfigure.exclude")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return (existing + OPTIONAL_DATASOURCE_AUTOCONFIGS).distinct().joinToString(",")
}

private val OPTIONAL_DATASOURCE_AUTOCONFIGS = listOf(
    DataSourceAutoConfiguration::class.java.name,
    DataSourceTransactionManagerAutoConfiguration::class.java.name,
    JdbcTemplateAutoConfiguration::class.java.name,
    DataSourceHealthContributorAutoConfiguration::class.java.name,
    FlywayAutoConfiguration::class.java.name
)

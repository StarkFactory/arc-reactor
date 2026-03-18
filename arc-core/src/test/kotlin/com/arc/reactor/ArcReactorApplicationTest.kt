package com.arc.reactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * ArcReactorApplication에 대한 테스트.
 *
 * 데이터소스 자동 설정 제외 로직을 검증합니다.
 */
class ArcReactorApplicationTest {

    @Test
    fun `datasource url is blank일 때 exclude datasource auto configuration해야 한다`() {
        val excludes = resolveOptionalDatasourceExcludes(
            isolatedEnvironment("arc.reactor.postgres.required" to "false")
        )
        assertNotNull(excludes) {
            "Datasource-related autoconfig exclusions should be set when datasource URL is blank"
        }
        assertTrue(excludes!!.contains(DataSourceAutoConfiguration::class.java.name)) {
            "DataSourceAutoConfiguration should be excluded when datasource URL is blank"
        }
        assertTrue(excludes.contains(DataSourceTransactionManagerAutoConfiguration::class.java.name)) {
            "DataSourceTransactionManagerAutoConfiguration should be excluded when datasource URL is blank"
        }
        assertTrue(excludes.contains(JdbcTemplateAutoConfiguration::class.java.name)) {
            "JdbcTemplateAutoConfiguration should be excluded when datasource URL is blank"
        }
        assertTrue(excludes.contains(DataSourceHealthContributorAutoConfiguration::class.java.name)) {
            "DataSourceHealthContributorAutoConfiguration should be excluded when datasource URL is blank"
        }
        assertTrue(excludes.contains(FlywayAutoConfiguration::class.java.name)) {
            "FlywayAutoConfiguration should be excluded when datasource URL is blank"
        }
    }

    @Test
    fun `datasource url is provided일 때 not exclude datasource auto configuration해야 한다`() {
        val excludes = resolveOptionalDatasourceExcludes(
            isolatedEnvironment("spring.datasource.url" to "jdbc:postgresql://localhost:5432/arcreactor")
        )

        assertEquals(null, excludes) {
            "Datasource autoconfig exclusions should not be set when datasource URL is provided"
        }
    }

    @Test
    fun `preserve existing exclusions while appending datasource exclusions해야 한다`() {
        val excludes = resolveOptionalDatasourceExcludes(
            isolatedEnvironment(
                "arc.reactor.postgres.required" to "false",
                "spring.autoconfigure.exclude" to "com.example.CustomAutoConfiguration"
            )
        ).orEmpty()
        assertTrue(excludes.contains("com.example.CustomAutoConfiguration")) {
            "Existing exclusions should be preserved when datasource exclusions are appended"
        }
        assertTrue(excludes.contains(DataSourceAutoConfiguration::class.java.name)) {
            "Datasource exclusions should still be appended when exclusions already exist"
        }
        assertFalse(excludes.contains(",,") || excludes.endsWith(",")) {
            "Merged exclusions should remain a valid comma-separated list: $excludes"
        }
    }

    private fun isolatedEnvironment(vararg properties: Pair<String, String>): StandardEnvironment {
        return StandardEnvironment().apply {
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.addFirst(MapPropertySource("test", properties.toMap()))
        }
    }
}

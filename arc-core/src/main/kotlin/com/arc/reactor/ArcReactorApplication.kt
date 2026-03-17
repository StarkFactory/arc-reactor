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

/**
 * Arc Reactor 애플리케이션 엔트리포인트.
 *
 * Spring Boot 자동 구성을 기반으로 하며, 데이터소스가 설정되지 않은 환경에서는
 * JDBC/Flyway 관련 자동 구성을 동적으로 제외하여 DB 없이도 기동할 수 있다.
 */
@SpringBootApplication
class ArcReactorApplication

/**
 * 애플리케이션 메인 함수.
 *
 * [buildArcReactorApplication]으로 [SpringApplication]을 구성한 뒤 실행한다.
 */
fun main(args: Array<String>) {
    buildArcReactorApplication(args).run(*args)
}

/**
 * 커맨드라인 인자를 분석하여 [SpringApplication] 인스턴스를 구성한다.
 *
 * 데이터소스 URL이 비어 있으면 JDBC/Flyway 관련 자동 구성을 제외 목록에 추가하여
 * DB 없이도 애플리케이션이 정상 기동되도록 보장한다.
 *
 * @param args 커맨드라인 인자
 * @param environment 프로퍼티 소스를 주입할 환경 (테스트에서 오버라이드 가능)
 * @return 구성 완료된 [SpringApplication]
 */
internal fun buildArcReactorApplication(
    args: Array<String>,
    environment: ConfigurableEnvironment = StandardEnvironment()
): SpringApplication {
    // 커맨드라인 인자가 있으면 환경 프로퍼티 소스에 최우선으로 등록
    if (args.isNotEmpty()) {
        environment.propertySources.addFirst(SimpleCommandLinePropertySource(*args))
    }

    val application = SpringApplication(ArcReactorApplication::class.java)
    // 데이터소스 미설정 시 JDBC/Flyway 자동 구성 제외
    resolveOptionalDatasourceExcludes(environment)?.let { merged ->
        application.setDefaultProperties(mapOf("spring.autoconfigure.exclude" to merged))
    }
    return application
}

/**
 * 데이터소스 URL이 비어 있을 때 제외해야 할 자동 구성 클래스 목록을 반환한다.
 *
 * 이미 `spring.autoconfigure.exclude`에 지정된 항목이 있으면 병합하고 중복을 제거한다.
 *
 * @param environment 현재 환경
 * @return 쉼표로 연결된 제외 클래스 문자열. 데이터소스가 설정되어 있으면 `null`
 */
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

/** DB 없는 환경에서 제외할 JDBC/Flyway 자동 구성 클래스 목록. */
private val OPTIONAL_DATASOURCE_AUTOCONFIGS = listOf(
    DataSourceAutoConfiguration::class.java.name,
    DataSourceTransactionManagerAutoConfiguration::class.java.name,
    JdbcTemplateAutoConfiguration::class.java.name,
    DataSourceHealthContributorAutoConfiguration::class.java.name,
    FlywayAutoConfiguration::class.java.name
)

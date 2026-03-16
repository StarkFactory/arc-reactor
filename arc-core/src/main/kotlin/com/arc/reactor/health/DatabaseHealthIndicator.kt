package com.arc.reactor.health

import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 데이터베이스 연결 상태를 확인하는 헬스 인디케이터.
 *
 * 단순 쿼리(`SELECT 1`)를 실행하여 DB 접근 가능 여부와 응답 지연 시간을 측정한다.
 *
 * WHY: Spring Boot Actuator의 `/actuator/health` 엔드포인트에 DB 상태를 노출하여
 * 운영자가 데이터베이스 문제를 빠르게 감지할 수 있게 한다.
 * 지연 시간(latencyMs)을 함께 보고하여 성능 저하 조기 발견에 활용한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see com.arc.reactor.autoconfigure.ArcReactorAutoConfiguration 에서 빈 등록
 */
class DatabaseHealthIndicator(
    private val jdbcTemplate: JdbcTemplate
) : HealthIndicator {

    /**
     * 데이터베이스 상태를 확인하고 Health 객체를 반환한다.
     *
     * @return UP (정상) 또는 DOWN (DB 접근 불가) 상태
     */
    override fun health(): Health {
        return try {
            val start = System.nanoTime()
            jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            Health.up()
                .withDetail("latencyMs", latencyMs)
                .build()
        } catch (e: Exception) {
            Health.down(e).build()
        }
    }
}

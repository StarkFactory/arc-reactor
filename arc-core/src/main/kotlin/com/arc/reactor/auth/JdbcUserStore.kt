package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 사용자 저장소
 *
 * `users` 테이블에 사용자 정보를 영구 저장한다 (Flyway 마이그레이션 V3 참조).
 *
 * ## 특징
 * - 서버 재시작에도 데이터 유지
 * - 이메일 고유성은 데이터베이스 제약 조건으로 보장
 * - 데이터베이스 트랜잭션을 통한 스레드 안전성
 *
 * @param jdbcTemplate Spring JdbcTemplate
 *
 * @see UserStore 사용자 저장소 인터페이스
 * @see InMemoryUserStore 메모리 기반 대안
 */
class JdbcUserStore(
    private val jdbcTemplate: JdbcTemplate
) : UserStore {

    override fun findByEmail(email: String): User? {
        val results = jdbcTemplate.query(
            "SELECT id, email, name, password_hash, role, created_at FROM users WHERE email = ?",
            ROW_MAPPER,
            email
        )
        return results.firstOrNull()
    }

    override fun findById(id: String): User? {
        val results = jdbcTemplate.query(
            "SELECT id, email, name, password_hash, role, created_at FROM users WHERE id = ?",
            ROW_MAPPER,
            id
        )
        return results.firstOrNull()
    }

    override fun save(user: User): User {
        jdbcTemplate.update(
            "INSERT INTO users (id, email, name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            user.id,
            user.email,
            user.name,
            user.passwordHash,
            user.role.name,
            java.sql.Timestamp.from(user.createdAt)
        )
        logger.debug { "Saved user: id=${user.id}, email=${user.email}, role=${user.role}" }
        return user
    }

    override fun update(user: User): User {
        jdbcTemplate.update(
            "UPDATE users SET name = ?, password_hash = ?, role = ? WHERE id = ?",
            user.name,
            user.passwordHash,
            user.role.name,
            user.id
        )
        logger.debug { "Updated user: id=${user.id}" }
        return user
    }

    override fun existsByEmail(email: String): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?",
            Int::class.java,
            email
        )
        return (count ?: 0) > 0
    }

    override fun count(): Long {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users",
            Long::class.java
        ) ?: 0L
    }

    companion object {
        /** ResultSet → User 매핑 함수 */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            User(
                id = rs.getString("id"),
                email = rs.getString("email"),
                name = rs.getString("name"),
                passwordHash = rs.getString("password_hash"),
                role = UserRole.valueOf(rs.getString("role")),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}

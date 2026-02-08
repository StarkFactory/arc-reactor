package com.arc.reactor.auth

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-based User Store for persistent user storage.
 *
 * Stores users in the `users` table — see Flyway migration V3.
 *
 * ## Features
 * - Persistent across server restarts
 * - Email uniqueness enforced by database constraint
 * - Thread-safe via database transactions
 */
class JdbcUserStore(
    private val jdbcTemplate: JdbcTemplate
) : UserStore {

    override fun findByEmail(email: String): User? {
        val results = jdbcTemplate.query(
            "SELECT id, email, name, password_hash, created_at FROM users WHERE email = ?",
            ROW_MAPPER,
            email
        )
        return results.firstOrNull()
    }

    override fun findById(id: String): User? {
        val results = jdbcTemplate.query(
            "SELECT id, email, name, password_hash, created_at FROM users WHERE id = ?",
            ROW_MAPPER,
            id
        )
        return results.firstOrNull()
    }

    override fun save(user: User): User {
        jdbcTemplate.update(
            "INSERT INTO users (id, email, name, password_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            user.id,
            user.email,
            user.name,
            user.passwordHash,
            java.sql.Timestamp.from(user.createdAt)
        )
        logger.debug { "Saved user: id=${user.id}, email=${user.email}" }
        return user
    }

    override fun update(user: User): User {
        jdbcTemplate.update(
            "UPDATE users SET name = ?, password_hash = ? WHERE id = ?",
            user.name,
            user.passwordHash,
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

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            User(
                id = rs.getString("id"),
                email = rs.getString("email"),
                name = rs.getString("name"),
                passwordHash = rs.getString("password_hash"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}

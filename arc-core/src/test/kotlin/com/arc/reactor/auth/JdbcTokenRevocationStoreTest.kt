package com.arc.reactor.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant

/**
 * JdbcTokenRevocationStore에 대한 테스트.
 *
 * JDBC 기반 토큰 폐기 저장소의 동작을 검증합니다.
 */
class JdbcTokenRevocationStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.h2.Driver")
            url = "jdbc:h2:mem:revocation-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
        }
        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS auth_token_revocations (
                token_id VARCHAR(255) PRIMARY KEY,
                expires_at TIMESTAMP NOT NULL,
                revoked_at TIMESTAMP NOT NULL
            )
            """.trimIndent()
        )
        jdbcTemplate.update("DELETE FROM auth_token_revocations")
    }

    @Test
    fun `persist revoked token across store instances해야 한다`() {
        val storeA = JdbcTokenRevocationStore(jdbcTemplate)
        val storeB = JdbcTokenRevocationStore(jdbcTemplate)
        val tokenId = "jti-jdbc-1"

        storeA.revoke(tokenId, Instant.now().plusSeconds(120))

        assertTrue(storeB.isRevoked(tokenId)) {
            "Revoked token must be visible across JdbcTokenRevocationStore instances"
        }
    }

    @Test
    fun `expiration time 후 expire revoked token해야 한다`() {
        val store = JdbcTokenRevocationStore(jdbcTemplate)
        val tokenId = "jti-jdbc-2"

        store.revoke(tokenId, Instant.now().minusSeconds(1))

        assertFalse(store.isRevoked(tokenId)) {
            "Expired JDBC revocation entry must not be treated as revoked"
        }
    }

    @Test
    fun `token is revoked again일 때 update expiration해야 한다`() {
        val store = JdbcTokenRevocationStore(jdbcTemplate)
        val tokenId = "jti-jdbc-3"

        store.revoke(tokenId, Instant.now().plusSeconds(30))
        store.revoke(tokenId, Instant.now().plusSeconds(180))

        assertTrue(store.isRevoked(tokenId)) {
            "Latest revoke call should keep token revoked until the latest expiration"
        }
    }
}

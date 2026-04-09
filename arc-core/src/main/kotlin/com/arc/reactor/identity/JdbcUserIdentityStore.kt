package com.arc.reactor.identity

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet

private val logger = KotlinLogging.logger {}

/**
 * JDBC 기반 사용자 신원 정보 저장소.
 *
 * `user_identities` 테이블에 크로스플랫폼 계정 매핑을 영속 저장한다.
 * INSERT ON CONFLICT DO UPDATE (UPSERT) 패턴으로 중복 키 충돌을 처리한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see UserIdentityStore 인터페이스 정의
 */
class JdbcUserIdentityStore(
    private val jdbcTemplate: JdbcTemplate
) : UserIdentityStore {

    override fun findBySlackUserId(slackUserId: String): UserIdentity? {
        val results = jdbcTemplate.query(
            "SELECT * FROM user_identities WHERE slack_user_id = ?",
            ROW_MAPPER,
            slackUserId
        )
        return results.firstOrNull()
    }

    override fun findByEmail(email: String): UserIdentity? {
        val results = jdbcTemplate.query(
            "SELECT * FROM user_identities WHERE email = ?",
            ROW_MAPPER,
            email
        )
        return results.firstOrNull()
    }

    override fun findAll(): List<UserIdentity> {
        return jdbcTemplate.query(
            "SELECT * FROM user_identities ORDER BY updated_at DESC",
            ROW_MAPPER
        )
    }

    override fun save(identity: UserIdentity): UserIdentity {
        jdbcTemplate.update(
            """
            INSERT INTO user_identities (
                slack_user_id, email, display_name, jira_account_id, bitbucket_uuid, updated_at
            ) VALUES (?, ?, ?, ?, ?, NOW())
            ON CONFLICT (slack_user_id) DO UPDATE SET
                email = EXCLUDED.email,
                display_name = EXCLUDED.display_name,
                jira_account_id = EXCLUDED.jira_account_id,
                bitbucket_uuid = EXCLUDED.bitbucket_uuid,
                updated_at = NOW()
            """.trimIndent(),
            identity.slackUserId,
            identity.email,
            identity.displayName,
            identity.jiraAccountId,
            identity.bitbucketUuid
        )
        logger.debug { "사용자 신원 저장 완료: slackUserId=${identity.slackUserId}" }
        return identity
    }

    override fun deleteBySlackUserId(slackUserId: String): Boolean {
        val deleted = jdbcTemplate.update(
            "DELETE FROM user_identities WHERE slack_user_id = ?",
            slackUserId
        )
        return deleted > 0
    }

    companion object {
        /** ResultSet → UserIdentity 변환 매퍼 */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            UserIdentity(
                slackUserId = rs.getString("slack_user_id"),
                email = rs.getString("email"),
                displayName = rs.getString("display_name"),
                jiraAccountId = rs.getString("jira_account_id"),
                bitbucketUuid = rs.getString("bitbucket_uuid")
            )
        }
    }
}

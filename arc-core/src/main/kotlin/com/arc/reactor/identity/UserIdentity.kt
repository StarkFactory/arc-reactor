package com.arc.reactor.identity

/**
 * 사용자 크로스플랫폼 신원 정보.
 *
 * Slack User ID를 기본 키로 사용하며, 이메일·Jira·Bitbucket 등
 * 외부 플랫폼 계정 식별자를 통합 관리한다.
 *
 * @property slackUserId Slack 사용자 ID (예: U088X6MECJD)
 * @property email 사용자 이메일 주소
 * @property displayName 표시 이름 (Slack 프로필 기반)
 * @property jiraAccountId Jira Cloud 계정 ID
 * @property bitbucketUuid Bitbucket UUID
 */
data class UserIdentity(
    val slackUserId: String,
    val email: String,
    val displayName: String? = null,
    val jiraAccountId: String? = null,
    val bitbucketUuid: String? = null
)

/**
 * 사용자 신원 정보 저장소 인터페이스.
 *
 * Slack User ID 기반으로 크로스플랫폼 계정 매핑을 관리한다.
 */
interface UserIdentityStore {

    /** Slack User ID로 신원 정보를 조회한다. */
    fun findBySlackUserId(slackUserId: String): UserIdentity?

    /** 이메일로 신원 정보를 조회한다. */
    fun findByEmail(email: String): UserIdentity?

    /** 전체 신원 정보를 조회한다. */
    fun findAll(): List<UserIdentity>

    /** 신원 정보를 저장(UPSERT)한다. */
    fun save(identity: UserIdentity): UserIdentity

    /** Slack User ID로 신원 정보를 삭제한다. 삭제 성공 시 true 반환. */
    fun deleteBySlackUserId(slackUserId: String): Boolean
}

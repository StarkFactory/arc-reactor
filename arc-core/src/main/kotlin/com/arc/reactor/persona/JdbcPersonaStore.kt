package com.arc.reactor.persona

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

/**
 * JDBC 기반 페르소나 저장소 — 영속적 페르소나 스토리지.
 *
 * 페르소나를 `personas` 테이블에 저장한다 — Flyway 마이그레이션 V2 참조.
 * 트랜잭션 갱신을 통해 최대 하나의 기본 페르소나를 보장한다.
 *
 * ## 특징
 * - 서버 재시작에도 영속적
 * - 단일 기본 페르소나 강제
 * - 데이터베이스 트랜잭션으로 스레드 안전
 *
 * WHY: 운영 환경에서 페르소나를 영구 저장하고, 트랜잭션으로
 * 기본 페르소나의 유일성 제약을 안전하게 보장한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @param transactionTemplate 트랜잭션 지원 (기본 페르소나 전환 원자성)
 * @see PersonaStore 인터페이스 정의
 * @see InMemoryPersonaStore 인메모리 대안
 */
class JdbcPersonaStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : PersonaStore {

    override fun list(): List<Persona> {
        return jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, prompt_template_id, created_at, updated_at " +
                "FROM personas ORDER BY created_at ASC",
            ROW_MAPPER
        )
    }

    override fun get(personaId: String): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, prompt_template_id, created_at, updated_at " +
                "FROM personas WHERE id = ?",
            ROW_MAPPER,
            personaId
        )
        return results.firstOrNull()
    }

    override fun getDefault(): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, prompt_template_id, created_at, updated_at " +
                "FROM personas WHERE is_default = TRUE LIMIT 1",
            ROW_MAPPER
        )
        return results.firstOrNull()
    }

    /**
     * 새 페르소나를 저장한다.
     * isDefault=true인 경우, 트랜잭션 내에서 기존 기본 페르소나를 먼저 해제한다.
     */
    override fun save(persona: Persona): Persona {
        transactionTemplate.execute {
            if (persona.isDefault) {
                clearDefault()
            }
            jdbcTemplate.update(
                "INSERT INTO personas (id, name, system_prompt, is_default, description, " +
                    "response_guideline, welcome_message, icon, is_active, prompt_template_id, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                persona.id,
                persona.name,
                persona.systemPrompt,
                persona.isDefault,
                persona.description,
                persona.responseGuideline,
                persona.welcomeMessage,
                persona.icon,
                persona.isActive,
                persona.promptTemplateId,
                java.sql.Timestamp.from(persona.createdAt),
                java.sql.Timestamp.from(persona.updatedAt)
            )
        }
        return persona
    }

    /**
     * 페르소나를 부분 갱신한다.
     *
     * R284 fix: TOCTOU race 제거. 이전 구현은 `get()`을 transaction 밖에서 호출한 뒤
     * merge한 값을 transaction 안에서 UPDATE하여, 두 동시 PATCH 요청이 같은 stale snapshot
     * 기준으로 merge할 때 다음 race가 발생할 수 있었다:
     * 1. T1: 두 PATCH 요청이 동시에 `get()` → 같은 snapshot
     * 2. T2: 두 요청이 같은 existing 기준으로 merge (다른 필드 업데이트라도 stale)
     * 3. T3: 두 UPDATE 모두 실행 → 두 번째가 첫 번째의 일부 필드를 stale 값으로 덮어씀
     * 4. 추가: 둘 다 isDefault=true면 양쪽 clearDefault → 양쪽 set true → dual-default
     *
     * R284 fix: SELECT...FOR UPDATE를 transaction 안에서 사용하여 row-level lock 획득.
     * 두 번째 transaction은 첫 번째가 commit/rollback할 때까지 blocking → 항상 fresh 값
     * 기준으로 merge. R275/R278 race fix와 동일한 "lock 안에서 read+merge+write" 패턴.
     */
    override fun update(
        personaId: String,
        name: String?,
        systemPrompt: String?,
        isDefault: Boolean?,
        description: String?,
        responseGuideline: String?,
        welcomeMessage: String?,
        icon: String?,
        promptTemplateId: String?,
        isActive: Boolean?
    ): Persona? {
        return transactionTemplate.execute<Persona?> { _ ->
            // R284 fix: SELECT FOR UPDATE inside transaction prevents TOCTOU race
            val existing = lockAndGet(personaId) ?: return@execute null

            val updatedName = name ?: existing.name
            val updatedPrompt = systemPrompt ?: existing.systemPrompt
            val updatedDefault = isDefault ?: existing.isDefault
            val updatedDescription = resolveNullableField(description, existing.description)
            val updatedGuideline = resolveNullableField(responseGuideline, existing.responseGuideline)
            val updatedWelcome = resolveNullableField(welcomeMessage, existing.welcomeMessage)
            val updatedIcon = resolveNullableField(icon, existing.icon)
            val updatedPromptTemplateId = resolveNullableField(promptTemplateId, existing.promptTemplateId)
            val updatedActive = isActive ?: existing.isActive
            val updatedAt = Instant.now()

            if (isDefault == true) {
                clearDefault()
            }
            jdbcTemplate.update(
                "UPDATE personas SET name = ?, system_prompt = ?, is_default = ?, description = ?, " +
                    "response_guideline = ?, welcome_message = ?, icon = ?, is_active = ?, prompt_template_id = ?, " +
                    "updated_at = ? WHERE id = ?",
                updatedName,
                updatedPrompt,
                updatedDefault,
                updatedDescription,
                updatedGuideline,
                updatedWelcome,
                updatedIcon,
                updatedActive,
                updatedPromptTemplateId,
                java.sql.Timestamp.from(updatedAt),
                personaId
            )

            existing.copy(
                name = updatedName,
                systemPrompt = updatedPrompt,
                isDefault = updatedDefault,
                description = updatedDescription,
                responseGuideline = updatedGuideline,
                welcomeMessage = updatedWelcome,
                icon = updatedIcon,
                isActive = updatedActive,
                promptTemplateId = updatedPromptTemplateId,
                updatedAt = updatedAt
            )
        }
    }

    /**
     * R284: SELECT...FOR UPDATE로 row-level lock을 획득하며 페르소나를 조회한다.
     *
     * 반드시 active transaction 안에서 호출되어야 한다 (그렇지 않으면 lock이 즉시 해제된다).
     * H2와 PostgreSQL 모두 FOR UPDATE 절을 지원한다.
     */
    private fun lockAndGet(personaId: String): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, prompt_template_id, created_at, updated_at " +
                "FROM personas WHERE id = ? FOR UPDATE",
            ROW_MAPPER,
            personaId
        )
        return results.firstOrNull()
    }

    override fun delete(personaId: String) {
        jdbcTemplate.update("DELETE FROM personas WHERE id = ?", personaId)
    }

    /**
     * nullable 필드 갱신을 해석한다: null = 변경 없음, 빈 문자열 = null로 클리어, 값 = 설정.
     * @see InMemoryPersonaStore.resolveNullableField 동일 규약
     */
    private fun resolveNullableField(newValue: String?, existing: String?): String? {
        return when {
            newValue == null -> existing
            newValue.isEmpty() -> null
            else -> newValue
        }
    }

    /** 기존 기본 페르소나의 is_default를 FALSE로 설정한다 */
    private fun clearDefault() {
        jdbcTemplate.update("UPDATE personas SET is_default = FALSE, updated_at = ? WHERE is_default = TRUE",
            java.sql.Timestamp.from(Instant.now()))
    }

    companion object {
        /** ResultSet 행을 Persona로 변환하는 RowMapper */
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            Persona(
                id = rs.getString("id"),
                name = rs.getString("name"),
                systemPrompt = rs.getString("system_prompt"),
                isDefault = rs.getBoolean("is_default"),
                description = rs.getString("description"),
                responseGuideline = rs.getString("response_guideline"),
                welcomeMessage = rs.getString("welcome_message"),
                icon = rs.getString("icon"),
                isActive = rs.getBoolean("is_active"),
                promptTemplateId = rs.getString("prompt_template_id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }
    }
}

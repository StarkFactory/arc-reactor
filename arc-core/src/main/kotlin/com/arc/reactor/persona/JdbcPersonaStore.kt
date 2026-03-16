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
        val existing = get(personaId) ?: return null

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

        transactionTemplate.execute {
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
        }

        return existing.copy(
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

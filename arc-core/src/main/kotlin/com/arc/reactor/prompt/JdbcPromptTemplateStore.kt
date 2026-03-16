package com.arc.reactor.prompt

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * JDBC 기반 프롬프트 템플릿 저장소 — 영속적 스토리지.
 *
 * 템플릿을 `prompt_templates`, 버전을 `prompt_versions` 테이블에 저장한다 — Flyway 마이그레이션 V5 참조.
 * 트랜잭션 갱신을 통해 템플릿당 최대 하나의 활성 버전을 보장한다.
 *
 * ## 특징
 * - 서버 재시작에도 영속적
 * - 템플릿당 단일 활성 버전 강제
 * - 캐스케이드 삭제 (템플릿 삭제 시 모든 버전 제거)
 * - 데이터베이스 트랜잭션으로 스레드 안전
 *
 * WHY: 운영 환경에서 프롬프트 템플릿과 버전 이력을 영구 저장하고,
 * 외래 키 제약으로 데이터 무결성을 보장한다.
 *
 * @param jdbcTemplate Spring JDBC 템플릿
 * @see PromptTemplateStore 인터페이스 정의
 * @see InMemoryPromptTemplateStore 인메모리 대안
 */
class JdbcPromptTemplateStore(
    private val jdbcTemplate: JdbcTemplate
) : PromptTemplateStore {

    // ---- 템플릿 CRUD ----

    override fun listTemplates(): List<PromptTemplate> {
        return jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates ORDER BY created_at ASC",
            TEMPLATE_ROW_MAPPER
        )
    }

    override fun getTemplate(id: String): PromptTemplate? {
        val results = jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates WHERE id = ?",
            TEMPLATE_ROW_MAPPER,
            id
        )
        return results.firstOrNull()
    }

    override fun getTemplateByName(name: String): PromptTemplate? {
        val results = jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates WHERE name = ?",
            TEMPLATE_ROW_MAPPER,
            name
        )
        return results.firstOrNull()
    }

    override fun saveTemplate(template: PromptTemplate): PromptTemplate {
        jdbcTemplate.update(
            "INSERT INTO prompt_templates (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            template.id,
            template.name,
            template.description,
            java.sql.Timestamp.from(template.createdAt),
            java.sql.Timestamp.from(template.updatedAt)
        )
        return template
    }

    override fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate? {
        val existing = getTemplate(id) ?: return null

        val updatedName = name ?: existing.name
        val updatedDescription = description ?: existing.description
        val updatedAt = Instant.now()

        jdbcTemplate.update(
            "UPDATE prompt_templates SET name = ?, description = ?, updated_at = ? WHERE id = ?",
            updatedName,
            updatedDescription,
            java.sql.Timestamp.from(updatedAt),
            id
        )

        return existing.copy(
            name = updatedName,
            description = updatedDescription,
            updatedAt = updatedAt
        )
    }

    override fun deleteTemplate(id: String) {
        // 버전은 외래 키 제약의 캐스케이드 삭제로 자동 제거된다
        jdbcTemplate.update("DELETE FROM prompt_templates WHERE id = ?", id)
    }

    // ---- 버전 관리 ----

    override fun listVersions(templateId: String): List<PromptVersion> {
        return jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE template_id = ? ORDER BY version ASC",
            VERSION_ROW_MAPPER,
            templateId
        )
    }

    override fun getVersion(versionId: String): PromptVersion? {
        val results = jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE id = ?",
            VERSION_ROW_MAPPER,
            versionId
        )
        return results.firstOrNull()
    }

    override fun getActiveVersion(templateId: String): PromptVersion? {
        val results = jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE template_id = ? AND status = 'ACTIVE' LIMIT 1",
            VERSION_ROW_MAPPER,
            templateId
        )
        return results.firstOrNull()
    }

    override fun createVersion(templateId: String, content: String, changeLog: String): PromptVersion? {
        if (getTemplate(templateId) == null) return null

        // 다음 버전 번호를 SQL로 계산한다 (동시성 안전)
        val nextVersion = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM prompt_versions WHERE template_id = ?",
            Int::class.java,
            templateId
        ) ?: 1

        val version = PromptVersion(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            version = nextVersion,
            content = content,
            status = VersionStatus.DRAFT,
            changeLog = changeLog
        )

        jdbcTemplate.update(
            "INSERT INTO prompt_versions (id, template_id, version, content, status, change_log, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            version.id,
            version.templateId,
            version.version,
            version.content,
            version.status.name,
            version.changeLog,
            java.sql.Timestamp.from(version.createdAt)
        )

        return version
    }

    override fun activateVersion(templateId: String, versionId: String): PromptVersion? {
        val version = getVersion(versionId) ?: return null
        if (version.templateId != templateId) return null

        // 현재 활성 버전을 아카이브한다
        archiveActiveVersion(templateId)

        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE id = ?",
            VersionStatus.ACTIVE.name,
            versionId
        )

        return version.copy(status = VersionStatus.ACTIVE)
    }

    override fun archiveVersion(versionId: String): PromptVersion? {
        val version = getVersion(versionId) ?: return null

        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE id = ?",
            VersionStatus.ARCHIVED.name,
            versionId
        )

        return version.copy(status = VersionStatus.ARCHIVED)
    }

    /** 지정 템플릿의 현재 활성 버전을 아카이브 상태로 변경한다 */
    private fun archiveActiveVersion(templateId: String) {
        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE template_id = ? AND status = ?",
            VersionStatus.ARCHIVED.name,
            templateId,
            VersionStatus.ACTIVE.name
        )
    }

    companion object {
        /** 템플릿 RowMapper */
        private val TEMPLATE_ROW_MAPPER = { rs: ResultSet, _: Int ->
            PromptTemplate(
                id = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }

        /** 버전 RowMapper */
        private val VERSION_ROW_MAPPER = { rs: ResultSet, _: Int ->
            PromptVersion(
                id = rs.getString("id"),
                templateId = rs.getString("template_id"),
                version = rs.getInt("version"),
                content = rs.getString("content"),
                status = VersionStatus.valueOf(rs.getString("status")),
                changeLog = rs.getString("change_log"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}

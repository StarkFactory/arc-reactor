package com.arc.reactor.prompt

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 프롬프트 템플릿 저장소 인터페이스
 *
 * [PromptTemplate]과 그 [PromptVersion]의 CRUD 작업을 관리한다.
 * 구현체는 템플릿당 최대 하나의 버전만 [VersionStatus.ACTIVE]임을 보장해야 한다.
 *
 * WHY: 프롬프트 템플릿 관리를 인터페이스로 추상화하여
 * 인메모리/JDBC 구현을 교체할 수 있게 한다.
 *
 * @see InMemoryPromptTemplateStore 기본 인메모리 구현
 * @see JdbcPromptTemplateStore PostgreSQL 영속 구현
 * @see com.arc.reactor.persona.Persona 페르소나와의 연계
 */
interface PromptTemplateStore {

    // ---- 템플릿 CRUD ----

    /** 모든 템플릿을 생성 시간 오름차순으로 조회한다. */
    fun listTemplates(): List<PromptTemplate>

    /** ID로 템플릿을 조회한다. 없으면 null. */
    fun getTemplate(id: String): PromptTemplate?

    /** 고유 이름으로 템플릿을 조회한다. 없으면 null. */
    fun getTemplateByName(name: String): PromptTemplate?

    /** 새 템플릿을 저장한다. */
    fun saveTemplate(template: PromptTemplate): PromptTemplate

    /** 템플릿을 부분 갱신한다. non-null 필드만 적용. 없으면 null. */
    fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate?

    /** 템플릿과 모든 버전을 삭제한다. 멱등성. */
    fun deleteTemplate(id: String)

    // ---- 버전 관리 ----

    /** 템플릿의 모든 버전을 버전 번호 오름차순으로 조회한다. */
    fun listVersions(templateId: String): List<PromptVersion>

    /** ID로 특정 버전을 조회한다. 없으면 null. */
    fun getVersion(versionId: String): PromptVersion?

    /** 템플릿의 현재 활성 버전을 조회한다. 없으면 null. */
    fun getActiveVersion(templateId: String): PromptVersion?

    /**
     * 템플릿에 새 버전을 생성한다. 버전 번호는 자동 증가된다.
     * 새 버전은 [VersionStatus.DRAFT] 상태로 시작한다.
     *
     * @return 생성된 버전, 또는 템플릿이 없으면 null
     */
    fun createVersion(templateId: String, content: String, changeLog: String = ""): PromptVersion?

    /**
     * 버전을 활성화한다. 이전 활성 버전은 아카이브된다.
     * 템플릿당 최대 하나의 버전만 활성 상태일 수 있다.
     *
     * @return 활성화된 버전, 또는 템플릿/버전이 없으면 null
     */
    fun activateVersion(templateId: String, versionId: String): PromptVersion?

    /**
     * 버전을 아카이브한다. 상태를 [VersionStatus.ARCHIVED]로 설정한다.
     *
     * @return 아카이브된 버전, 또는 없으면 null
     */
    fun archiveVersion(versionId: String): PromptVersion?
}

/**
 * 인메모리 프롬프트 템플릿 저장소
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현.
 * 영속적이지 않음 — 서버 재시작 시 데이터가 소실된다.
 *
 * WHY: DB 없이도 기본 동작을 보장하기 위한 기본 구현.
 *
 * @see JdbcPromptTemplateStore 운영 환경용 JDBC 구현
 */
class InMemoryPromptTemplateStore : PromptTemplateStore {

    /** 템플릿 ID -> 템플릿 */
    private val templates = ConcurrentHashMap<String, PromptTemplate>()
    /** 버전 ID -> 버전 */
    private val versions = ConcurrentHashMap<String, PromptVersion>()

    override fun listTemplates(): List<PromptTemplate> {
        return templates.values.sortedBy { it.createdAt }
    }

    override fun getTemplate(id: String): PromptTemplate? = templates[id]

    override fun getTemplateByName(name: String): PromptTemplate? {
        return templates.values.firstOrNull { it.name == name }
    }

    override fun saveTemplate(template: PromptTemplate): PromptTemplate {
        templates[template.id] = template
        return template
    }

    override fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate? {
        val existing = templates[id] ?: return null
        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            updatedAt = Instant.now()
        )
        templates[id] = updated
        return updated
    }

    override fun deleteTemplate(id: String) {
        templates.remove(id)
        // 템플릿에 속한 모든 버전도 삭제한다
        versions.values.removeIf { it.templateId == id }
    }

    override fun listVersions(templateId: String): List<PromptVersion> {
        return versions.values
            .filter { it.templateId == templateId }
            .sortedBy { it.version }
    }

    override fun getVersion(versionId: String): PromptVersion? = versions[versionId]

    override fun getActiveVersion(templateId: String): PromptVersion? {
        return versions.values.firstOrNull {
            it.templateId == templateId && it.status == VersionStatus.ACTIVE
        }
    }

    override fun createVersion(templateId: String, content: String, changeLog: String): PromptVersion? {
        if (templates[templateId] == null) return null

        // 다음 버전 번호를 계산한다
        val nextVersion = versions.values
            .filter { it.templateId == templateId }
            .maxOfOrNull { it.version }
            ?.plus(1) ?: 1

        val version = PromptVersion(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            version = nextVersion,
            content = content,
            status = VersionStatus.DRAFT,
            changeLog = changeLog
        )
        versions[version.id] = version
        return version
    }

    override fun activateVersion(templateId: String, versionId: String): PromptVersion? {
        val version = versions[versionId] ?: return null
        if (version.templateId != templateId) return null

        // 현재 활성 버전을 아카이브한다
        versions.values
            .filter { it.templateId == templateId && it.status == VersionStatus.ACTIVE }
            .forEach { versions[it.id] = it.copy(status = VersionStatus.ARCHIVED) }

        val activated = version.copy(status = VersionStatus.ACTIVE)
        versions[versionId] = activated
        return activated
    }

    override fun archiveVersion(versionId: String): PromptVersion? {
        val version = versions[versionId] ?: return null
        val archived = version.copy(status = VersionStatus.ARCHIVED)
        versions[versionId] = archived
        return archived
    }
}

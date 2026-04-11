package com.arc.reactor.prompt

import java.time.Instant
import java.util.UUID
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

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
/**
 * 인메모리 프롬프트 템플릿 저장소.
 *
 * R313 fix: ConcurrentHashMap → Caffeine bounded cache (2 필드). 기존 구현은
 * 템플릿/버전이 반복 저장되면 무제한 성장 가능성이 있었다. 이제 [maxTemplates]
 * 상한(기본 1000)과 [maxVersions] 상한(기본 10,000)을 넘으면 W-TinyLFU 정책으로 evict.
 */
class InMemoryPromptTemplateStore(
    maxTemplates: Long = DEFAULT_MAX_TEMPLATES,
    maxVersions: Long = DEFAULT_MAX_VERSIONS
) : PromptTemplateStore {

    /** 템플릿 ID -> 템플릿 */
    private val templates: Cache<String, PromptTemplate> = Caffeine.newBuilder()
        .maximumSize(maxTemplates)
        .build()
    /** 버전 ID -> 버전 */
    private val versions: Cache<String, PromptVersion> = Caffeine.newBuilder()
        .maximumSize(maxVersions)
        .build()

    override fun listTemplates(): List<PromptTemplate> {
        return templates.asMap().values.sortedBy { it.createdAt }
    }

    override fun getTemplate(id: String): PromptTemplate? = templates.getIfPresent(id)

    override fun getTemplateByName(name: String): PromptTemplate? {
        return templates.asMap().values.firstOrNull { it.name == name }
    }

    override fun saveTemplate(template: PromptTemplate): PromptTemplate {
        templates.put(template.id, template)
        return template
    }

    override fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate? {
        val existing = templates.getIfPresent(id) ?: return null
        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            updatedAt = Instant.now()
        )
        templates.put(id, updated)
        return updated
    }

    override fun deleteTemplate(id: String) {
        templates.invalidate(id)
        // 템플릿에 속한 모든 버전도 삭제한다
        val versionIdsToRemove = versions.asMap()
            .filterValues { it.templateId == id }
            .keys
            .toList()
        versions.invalidateAll(versionIdsToRemove)
    }

    override fun listVersions(templateId: String): List<PromptVersion> {
        return versions.asMap().values
            .filter { it.templateId == templateId }
            .sortedBy { it.version }
    }

    override fun getVersion(versionId: String): PromptVersion? = versions.getIfPresent(versionId)

    override fun getActiveVersion(templateId: String): PromptVersion? {
        return versions.asMap().values.firstOrNull {
            it.templateId == templateId && it.status == VersionStatus.ACTIVE
        }
    }

    override fun createVersion(templateId: String, content: String, changeLog: String): PromptVersion? {
        if (templates.getIfPresent(templateId) == null) return null

        // 다음 버전 번호를 계산한다
        val nextVersion = versions.asMap().values
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
        versions.put(version.id, version)
        return version
    }

    override fun activateVersion(templateId: String, versionId: String): PromptVersion? {
        val version = versions.getIfPresent(versionId) ?: return null
        if (version.templateId != templateId) return null

        // 현재 활성 버전을 아카이브한다
        versions.asMap().values
            .filter { it.templateId == templateId && it.status == VersionStatus.ACTIVE }
            .forEach { versions.put(it.id, it.copy(status = VersionStatus.ARCHIVED)) }

        val activated = version.copy(status = VersionStatus.ACTIVE)
        versions.put(versionId, activated)
        return activated
    }

    override fun archiveVersion(versionId: String): PromptVersion? {
        val version = versions.getIfPresent(versionId) ?: return null
        val archived = version.copy(status = VersionStatus.ARCHIVED)
        versions.put(versionId, archived)
        return archived
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        templates.cleanUp()
        versions.cleanUp()
    }

    companion object {
        /** 기본 템플릿 상한. 초과 시 W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_TEMPLATES: Long = 1_000L
        /** 기본 버전 상한. 템플릿당 수십 개 버전을 가정. */
        const val DEFAULT_MAX_VERSIONS: Long = 10_000L
    }
}

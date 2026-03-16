package com.arc.reactor.persona

import com.arc.reactor.prompt.PromptTemplateStore
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 페르소나 — 명명된 시스템 프롬프트 템플릿.
 *
 * 페르소나를 통해 관리자가 시스템 프롬프트를 중앙에서 관리한다.
 * 사용자는 시스템 프롬프트를 직접 입력하는 대신 페르소나 ID로 선택한다.
 *
 * WHY: 시스템 프롬프트를 코드에 하드코딩하지 않고 관리자가 REST API로
 * 동적으로 관리할 수 있게 한다. 프롬프트 템플릿과 연계하여 버전 관리도 가능하다.
 *
 * @param id 고유 식별자 (UUID 또는 "default")
 * @param name 표시 이름 (예: "고객 지원 에이전트", "Python 전문가")
 * @param systemPrompt LLM에 전송되는 실제 시스템 프롬프트 텍스트
 * @param isDefault 기본 페르소나 여부 (최대 하나)
 * @param description 이 페르소나의 역할에 대한 짧은 설명
 * @param responseGuideline systemPrompt에 추가되는 응답 스타일/형식 지침
 * @param welcomeMessage 페르소나 선택 시 표시되는 초기 인사 메시지
 * @param icon UI 표시용 이모지 또는 짧은 아이콘 식별자
 * @param isActive 이 페르소나가 선택 가능한지 여부
 * @param promptTemplateId 런타임에 systemPrompt를 오버라이드하는 연결된 버전 관리 프롬프트 템플릿 (선택)
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see PersonaStore 페르소나 저장소
 * @see PromptTemplateStore 프롬프트 템플릿 저장소
 */
data class Persona(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean = false,
    val description: String? = null,
    val responseGuideline: String? = null,
    val welcomeMessage: String? = null,
    val icon: String? = null,
    val isActive: Boolean = true,
    val promptTemplateId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 페르소나 저장소 인터페이스
 *
 * 페르소나의 CRUD 작업을 관리한다.
 * 구현체는 최대 하나의 페르소나만 [isDefault] = true를 보장해야 한다.
 *
 * WHY: 인터페이스를 분리하여 인메모리/JDBC 구현을 교체할 수 있게 한다.
 *
 * @see InMemoryPersonaStore 기본 인메모리 구현
 * @see JdbcPersonaStore JDBC 영속 구현
 */
interface PersonaStore {

    /** 모든 페르소나를 생성 시간 오름차순으로 조회한다. */
    fun list(): List<Persona>

    /** ID로 페르소나를 조회한다. 없으면 null. */
    fun get(personaId: String): Persona?

    /** 기본 페르소나(isDefault = true)를 조회한다. 없으면 null. */
    fun getDefault(): Persona?

    /** 새 페르소나를 저장한다. isDefault=true이면 기존 기본을 해제한다. */
    fun save(persona: Persona): Persona

    /**
     * 페르소나를 부분 갱신한다. non-null 필드만 적용된다.
     * isDefault=true로 설정하면 기존 기본이 해제된다.
     *
     * @return 갱신된 페르소나, 또는 존재하지 않으면 null
     */
    fun update(
        personaId: String,
        name: String? = null,
        systemPrompt: String? = null,
        isDefault: Boolean? = null,
        description: String? = null,
        responseGuideline: String? = null,
        welcomeMessage: String? = null,
        icon: String? = null,
        promptTemplateId: String? = null,
        isActive: Boolean? = null
    ): Persona?

    /** ID로 페르소나를 삭제한다. 멱등성 — 없어도 에러 없음. */
    fun delete(personaId: String)
}

/**
 * 기본 시스템 프롬프트 — ChatController의 폴백과 동일.
 *
 * WHY: 페르소나가 없거나 설정이 불완전한 경우의 폴백 프롬프트.
 * 다국어 응답, 도구 활용, 환각 방지 등 기본 지침을 포함한다.
 */
internal const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful AI assistant powered by Arc Reactor. " +
        "Answer in the same language as the user's message. " +
        "Be concise and direct. " +
        "When you have tools available, use them to provide accurate, grounded answers. " +
        "Do not fabricate citations, references, or sources. " +
        "If you are unsure about something, say so honestly rather than guessing. " +
        "Do not add a Sources section for casual greetings or simple questions that do not require evidence. " +
        "Distinguish between RAG-grounded answers and general knowledge answers."

/**
 * 인메모리 페르소나 저장소
 *
 * [ConcurrentHashMap]을 사용한 스레드 안전 구현.
 * 생성 시 기본 페르소나를 사전 로딩한다.
 * 영속적이지 않음 — 서버 재시작 시 데이터가 소실된다.
 *
 * WHY: DB 없이도 기본 동작을 보장하기 위한 기본 구현.
 *
 * @see JdbcPersonaStore 운영 환경용 JDBC 구현
 */
class InMemoryPersonaStore : PersonaStore {

    private val personas = ConcurrentHashMap<String, Persona>()

    init {
        // 기본 페르소나를 사전 로딩한다
        val defaultPersona = Persona(
            id = "default",
            name = "Default Assistant",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            isDefault = true
        )
        personas[defaultPersona.id] = defaultPersona
    }

    override fun list(): List<Persona> {
        return personas.values.sortedBy { it.createdAt }
    }

    override fun get(personaId: String): Persona? = personas[personaId]

    override fun getDefault(): Persona? = personas.values.firstOrNull { it.isDefault }

    override fun save(persona: Persona): Persona {
        synchronized(this) {
            // 새 페르소나가 기본이면 기존 기본을 해제한다
            if (persona.isDefault) {
                clearDefault()
            }
            personas[persona.id] = persona
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
        synchronized(this) {
            val existing = personas[personaId] ?: return null

            if (isDefault == true) {
                clearDefault()
            }

            val updated = existing.copy(
                name = name ?: existing.name,
                systemPrompt = systemPrompt ?: existing.systemPrompt,
                isDefault = isDefault ?: existing.isDefault,
                description = resolveNullableField(description, existing.description),
                responseGuideline = resolveNullableField(responseGuideline, existing.responseGuideline),
                welcomeMessage = resolveNullableField(welcomeMessage, existing.welcomeMessage),
                icon = resolveNullableField(icon, existing.icon),
                promptTemplateId = resolveNullableField(promptTemplateId, existing.promptTemplateId),
                isActive = isActive ?: existing.isActive,
                updatedAt = Instant.now()
            )
            personas[personaId] = updated
            return updated
        }
    }

    override fun delete(personaId: String) {
        personas.remove(personaId)
    }

    /**
     * nullable 필드 갱신을 해석한다: null = 변경 없음, 빈 문자열 = null로 클리어, 값 = 설정.
     *
     * WHY: 부분 갱신(PATCH) API에서 "필드를 삭제"와 "필드를 변경하지 않음"을
     * 구분하기 위한 규약이다. 빈 문자열을 전송하면 해당 필드가 null로 초기화된다.
     */
    private fun resolveNullableField(newValue: String?, existing: String?): String? {
        return when {
            newValue == null -> existing
            newValue.isEmpty() -> null
            else -> newValue
        }
    }

    /** 모든 페르소나의 isDefault를 false로 설정한다 */
    private fun clearDefault() {
        for ((id, persona) in personas) {
            if (persona.isDefault) {
                personas[id] = persona.copy(isDefault = false, updatedAt = Instant.now())
            }
        }
    }
}

/**
 * 페르소나의 유효 프롬프트를 결정한다.
 *
 * 프롬프트 결정 우선순위:
 * 1. promptTemplateId가 있고 활성 버전이 있으면 → 템플릿 버전의 내용
 * 2. 그렇지 않으면 → systemPrompt (직접 설정값)
 * 3. responseGuideline이 있으면 기본 프롬프트에 추가한다
 *
 * WHY: 프롬프트 템플릿 시스템과 페르소나를 연계하여
 * 버전 관리된 프롬프트를 페르소나에 적용할 수 있게 한다.
 *
 * @param promptTemplateStore 프롬프트 템플릿 저장소 (선택)
 * @return 최종 시스템 프롬프트 문자열
 * @see PromptTemplateStore 프롬프트 템플릿 관리
 */
fun Persona.resolveEffectivePrompt(promptTemplateStore: PromptTemplateStore?): String {
    val base = resolveBasePrompt(promptTemplateStore)
    val guideline = responseGuideline?.trim()
    return if (!guideline.isNullOrBlank()) "$base\n\n$guideline" else base
}

/**
 * 기본 프롬프트를 결정한다.
 * 프롬프트 템플릿이 연결되어 있으면 활성 버전의 내용을 사용하고,
 * 그렇지 않으면 직접 설정된 systemPrompt를 사용한다.
 */
private fun Persona.resolveBasePrompt(promptTemplateStore: PromptTemplateStore?): String {
    if (promptTemplateId.isNullOrBlank() || promptTemplateStore == null) return systemPrompt
    return try {
        promptTemplateStore.getActiveVersion(promptTemplateId)?.content?.takeIf { it.isNotBlank() } ?: systemPrompt
    } catch (e: Exception) {
        logger.warn(e) { "프롬프트 템플릿 조회 실패: personaId='$id' promptTemplateId='$promptTemplateId'" }
        systemPrompt
    }
}

package com.arc.reactor.memory

import com.arc.reactor.memory.model.UserMemory
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [UserMemoryStore] 위의 서비스 계층.
 *
 * 저장된 기억을 시스템 프롬프트에 주입 가능한
 * 자연어 컨텍스트 문자열로 변환하는 고수준 기능을 제공한다.
 *
 * @param store 기저 사용자 기억 저장소
 * @param maxRecentTopics 유지할 최대 최근 토픽 수 (기본값 10)
 * @param maxPromptInjectionChars 주입되는 컨텍스트의 최대 문자 수 (기본값 1000)
 */
class UserMemoryManager(
    private val store: UserMemoryStore,
    private val maxRecentTopics: Int = 10,
    private val maxPromptInjectionChars: Int = DEFAULT_MAX_PROMPT_INJECTION_CHARS
) {

    /**
     * 사용자의 저장된 기억을 구조화된 컨텍스트 문자열로 변환한다.
     *
     * 출력 예시:
     * ```
     * Facts: team=backend, role=senior engineer
     * Preferences: language=Korean, detail_level=brief
     * ```
     *
     * 기억이 없거나 사용 가능한 데이터가 없으면 빈 문자열을 반환한다.
     * 출력은 [maxPromptInjectionChars]로 잘린다.
     */
    suspend fun getContextPrompt(userId: String): String {
        val memory = store.get(userId) ?: return ""
        return buildContextPrompt(memory)
    }

    /**
     * 사용자의 최근 토픽에 [topic]을 추가한다. [maxRecentTopics]를 준수한다.
     */
    suspend fun recordTopic(userId: String, topic: String) {
        try {
            store.addRecentTopic(userId, topic, maxRecentTopics)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "사용자 토픽 기록 실패: $userId" }
        }
    }

    /** 기저 저장소에 직접 위임한다. */
    suspend fun get(userId: String): UserMemory? = store.get(userId)

    /** 기저 저장소에 직접 위임한다. */
    suspend fun save(userId: String, memory: UserMemory) = store.save(userId, memory)

    /** 기저 저장소에 직접 위임한다. */
    suspend fun delete(userId: String) = store.delete(userId)

    /** 기저 저장소에 직접 위임한다. */
    suspend fun updateFact(userId: String, key: String, value: String) =
        store.updateFact(userId, key, value)

    /** 기저 저장소에 직접 위임한다. */
    suspend fun updatePreference(userId: String, key: String, value: String) =
        store.updatePreference(userId, key, value)

    /**
     * 기억으로부터 컨텍스트 프롬프트를 빌드한다.
     * Facts와 Preferences를 줄 단위로 조합하고 최대 길이로 잘른다.
     */
    private fun buildContextPrompt(memory: UserMemory): String {
        val lines = mutableListOf<String>()

        if (memory.facts.isNotEmpty()) {
            val factsStr = memory.facts.entries.joinToString(", ") { "${it.key}=${it.value}" }
            lines.add("Facts: $factsStr")
        }

        if (memory.preferences.isNotEmpty()) {
            val prefsStr = memory.preferences.entries.joinToString(", ") { "${it.key}=${it.value}" }
            lines.add("Preferences: $prefsStr")
        }

        if (lines.isEmpty()) return ""

        val result = lines.joinToString("\n")
        return if (result.length > maxPromptInjectionChars) {
            result.take(maxPromptInjectionChars)
        } else {
            result
        }
    }

    companion object {
        /** 주입되는 사용자 기억 컨텍스트의 기본 최대 문자 수 */
        const val DEFAULT_MAX_PROMPT_INJECTION_CHARS = 1000
    }
}

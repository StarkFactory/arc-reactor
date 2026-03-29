package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * 대화 맥락 인식 쿼리 변환기.
 *
 * 대화 맥락을 반영하여 사용자 쿼리를 재작성한다.
 * 대명사와 참조를 해소하여 독립적인(standalone) 검색 쿼리를 생성한다.
 *
 * ## 동작 원리
 * ```
 * 대화 이력:
 *   사용자: "반품 정책에 대해 알려줘"
 *   AI: "구매 후 30일 이내에 반품 가능합니다..."
 *   사용자: "전자제품은?"        ← 맥락 없이는 모호함
 *
 * → LLM이 재작성: "전자제품의 반품 정책은 무엇인가요?"
 * → 이 독립적인 쿼리로 더 정확한 문서를 검색한다
 * ```
 *
 * ## 왜 이것이 효과적인가
 * 멀티턴 대화에서 사용자는 자연스럽게 대명사("그것", "거기")와
 * 암시적 참조를 사용한다. 이런 표현은 검색 쿼리로는 부적합하다.
 * 쿼리를 자기 완결적으로 재작성하면 검색 정확도가 크게 향상된다.
 *
 * @param chatClient 쿼리 재작성에 사용하는 Spring AI ChatClient
 * @param maxHistoryTurns 포함할 최대 대화 턴 수 (기본값: 5)
 */
class ConversationAwareQueryTransformer(
    private val chatClient: ChatClient,
    private val maxHistoryTurns: Int = 5
) : QueryTransformer {

    /**
     * 대화 이력을 사용하여 맥락 인식 쿼리 재작성을 수행한다.
     * 이력이 비어있거나 재작성에 실패하면 원본 쿼리로 폴백한다.
     */
    suspend fun transformWithHistory(query: String, history: List<String>): List<String> {
        if (history.isEmpty()) return listOf(query)
        return try {
            val rewrittenQuery = rewriteQuery(query, history.takeLast(maxHistoryTurns))
            if (rewrittenQuery.isNullOrBlank() || rewrittenQuery == query) listOf(query)
            else listOf(rewrittenQuery)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "대화 맥락 인식 쿼리 재작성 실패, 원본 쿼리로 폴백" }
            listOf(query)
        }
    }

    /** 대화 이력 없이 호출되면 원본 쿼리를 그대로 반환한다. */
    override suspend fun transform(query: String): List<String> = listOf(query)

    /** LLM을 호출하여 대화 맥락 기반 쿼리 재작성을 수행한다. */
    private suspend fun rewriteQuery(query: String, history: List<String>): String? {
        val historyText = history.joinToString("\n")
        val userMessage = "Conversation history:\n$historyText\n\nCurrent query: $query"
        return withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .call()
                .chatResponse()
                ?.result?.output?.text?.trim()
        }
    }

    companion object {
        /** LLM에 전달하는 쿼리 재작성 시스템 프롬프트 */
        internal const val SYSTEM_PROMPT =
            "You are a query rewriter. Given a conversation history and a current query, " +
                "rewrite the query to be a standalone search query that doesn't need conversation context. " +
                "Resolve all pronouns (it, that, they, etc.) and implicit references. " +
                "Output ONLY the rewritten query, nothing else. " +
                "If the query is already self-contained, return it unchanged."
    }
}

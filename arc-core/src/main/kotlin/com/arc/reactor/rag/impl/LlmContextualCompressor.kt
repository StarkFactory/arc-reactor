package com.arc.reactor.rag.impl

import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 문맥 압축기.
 *
 * LLM을 사용하여 각 문서에서 쿼리와 관련된 정보만 추출한다.
 * 관련 내용이 없는 문서는 완전히 제거된다.
 *
 * RECOMP (Xu et al., 2024) 기반: 추출적 압축을 통한 선택적 보강.
 *
 * ## 동작 방식
 * - 짧은 문서 (< [minContentLength]자)는 LLM 호출 없이 그대로 통과
 *   → 왜: 짧은 문서는 이미 충분히 집중되어 있어 압축 효과가 미미하며 LLM 비용만 낭비
 * - 각 문서를 코루틴으로 병렬 압축
 * - LLM이 "IRRELEVANT"로 응답하면 해당 문서를 제거
 * - LLM 호출 실패 시 원본 문서를 보존 (우아한 성능 저하)
 *
 * @param chatClient 압축 호출에 사용하는 Spring AI ChatClient
 * @param minContentLength 이 길이 미만의 문서는 압축을 건너뛴다 (기본값: 200자)
 */
class LlmContextualCompressor(
    private val chatClient: ChatClient,
    private val minContentLength: Int = DEFAULT_MIN_CONTENT_LENGTH
) : ContextCompressor {

    override suspend fun compress(
        query: String,
        documents: List<RetrievedDocument>
    ): List<RetrievedDocument> {
        if (documents.isEmpty()) return emptyList()

        logger.debug { "${documents.size}개 문서 압축 시작: $query" }

        // coroutineScope로 모든 문서를 병렬 압축. 하나가 실패해도 다른 것에 영향 없음.
        val compressed = coroutineScope {
            documents.map { doc ->
                async { compressDocument(query, doc) }
            }.mapNotNull { it.await() }
        }

        logger.debug {
            "압축 완료: ${documents.size} -> ${compressed.size}개 문서"
        }
        return compressed
    }

    /**
     * 단일 문서를 압축한다.
     * 짧은 문서는 그대로 반환, LLM 실패 시 원본 보존.
     *
     * @return 압축된 문서, 관련 없으면 null
     */
    private suspend fun compressDocument(
        query: String,
        document: RetrievedDocument
    ): RetrievedDocument? {
        // 짧은 문서는 압축 효과가 미미하므로 그대로 통과
        if (document.content.length < minContentLength) {
            return document
        }

        return try {
            val extracted = callLlm(query, document.content)
            when {
                extracted == null -> document  // LLM 응답 없음 → 원본 유지
                IRRELEVANT_PATTERN.matches(extracted.trim()) -> {
                    logger.debug { "문서 ${document.id}이(가) 쿼리와 무관하여 제거" }
                    null  // 관련 없음 → 제거
                }
                extracted.isBlank() -> document  // 빈 응답 → 원본 유지
                else -> document.copy(content = extracted.trim())  // 압축된 내용으로 교체
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            // 압축 실패 시 원본을 보존하여 정보 손실을 방지
            logger.warn(e) {
                "문서 ${document.id} 압축 실패, 원본 유지"
            }
            document
        }
    }

    /** LLM을 호출하여 쿼리 관련 내용을 추출한다. */
    private suspend fun callLlm(query: String, content: String): String? {
        return withContext(Dispatchers.IO) {
            val userPrompt = EXTRACTION_PROMPT
                .replace("{query}", query)
                .replace("{content}", content)

            chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse()
                ?.result
                ?.output
                ?.text
        }
    }

    companion object {
        /** 압축을 건너뛰는 최소 문서 길이 (자 수) */
        internal const val DEFAULT_MIN_CONTENT_LENGTH = 200

        /** "IRRELEVANT" 응답을 매칭하는 정규식 (대소문자 무시) */
        private val IRRELEVANT_PATTERN = Regex("(?i)irrelevant[.!]?")

        /** 압축 시스템 프롬프트 */
        internal const val SYSTEM_PROMPT =
            "You are a document compression assistant. " +
                "Extract only the information relevant to the user's query. " +
                "Remove all irrelevant content. " +
                "If nothing is relevant, respond with exactly \"IRRELEVANT\"."

        /** 추출 요청 프롬프트 템플릿 */
        internal const val EXTRACTION_PROMPT =
            "Query: {query}\n\nDocument:\n{content}\n\nRelevant extract:"
    }
}

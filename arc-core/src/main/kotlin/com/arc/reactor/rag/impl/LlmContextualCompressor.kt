package com.arc.reactor.rag.impl

import com.arc.reactor.rag.ContextCompressor
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val minContentLength: Int = DEFAULT_MIN_CONTENT_LENGTH,
    /**
     * R328 fix: 동시 LLM 압축 호출 상한. 기본 5개.
     *
     * 기존 구현은 N개의 문서를 `async`로 모두 동시에 launch하여 무제한 fan-out → 예: reranker가
     * 30개 문서 반환 시 30개 동시 LLM 호출로 HTTP connection pool 포화 + 토큰 과금 폭주.
     * `Semaphore(maxConcurrent)`로 동시 실행을 제한하여 cost/latency spike를 방지한다.
     */
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT
) : ContextCompressor {

    /** R328 fix: 병렬 압축 fan-out을 제한하는 세마포어 */
    private val compressionSemaphore = Semaphore(maxConcurrent)

    override suspend fun compress(
        query: String,
        documents: List<RetrievedDocument>
    ): List<RetrievedDocument> {
        if (documents.isEmpty()) return emptyList()

        logger.debug { "${documents.size}개 문서 압축 시작 (maxConcurrent=$maxConcurrent)" }

        // coroutineScope로 모든 문서를 launch하되, Semaphore로 동시 실행 수를 maxConcurrent로 제한.
        // 하나가 실패해도 다른 것에 영향 없음.
        val compressed = coroutineScope {
            documents.map { doc ->
                async {
                    compressionSemaphore.withPermit { compressDocument(query, doc) }
                }
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

    /**
     * LLM을 호출하여 쿼리 관련 내용을 추출한다.
     *
     * R329 fix: 기존 구현은 `EXTRACTION_PROMPT.replace("{query}", query).replace("{content}", content)`
     * 순서로 템플릿 치환을 수행했다. `String.replace`는 **모든 occurrence**를 치환하므로:
     * - `query` 값에 리터럴 `{content}`가 포함되면 → 첫 `replace("{query}", query)` 결과에 `{content}`가
     *   2번(템플릿 원본 + query에서 유래) 나타남
     * - 두 번째 `replace("{content}", content)`가 **두 자리 모두** 문서 본문으로 치환
     * - 결과: 프롬프트 구조가 망가지고, 사용자 query 위치에 문서 본문이 삽입되어 LLM이 엉뚱한
     *   지시를 받음 (prompt injection / template corruption)
     *
     * 대칭 케이스: 문서에 `{query}` 리터럴 포함 → 이미 첫 replace가 실행된 후라 안전하지만 query가
     * 문서에 주입되는 대칭 버그 발생 가능.
     *
     * 수정: `replace` 대신 직접 문자열 조립. 템플릿 상수는 이제 placeholder 없이 고정 헤더만 사용.
     */
    private suspend fun callLlm(query: String, content: String): String? {
        return withContext(Dispatchers.IO) {
            // R329: 직접 조립으로 template replace 재귀 우회. 순서와 레이아웃은 기존과 byte-identical.
            val userPrompt = buildString {
                append("Query: ")
                append(query)
                append("\n\nDocument:\n")
                append(content)
                append("\n\nRelevant extract:")
            }

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

        /** R328: 병렬 압축 기본 상한 (동시 LLM 호출 수). */
        internal const val DEFAULT_MAX_CONCURRENT = 5

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

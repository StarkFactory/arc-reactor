package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * 분해(Decomposition) 쿼리 변환기.
 *
 * 복합적인 멀티홉 질문을 독립적으로 검색 가능한 단순 하위 쿼리로 분해하고,
 * 결과를 합쳐서 포괄적인 검색을 수행한다.
 *
 * ## 동작 원리
 * ```
 * 사용자 쿼리: "우리 반품 정책이 경쟁사와 어떻게 다르고
 *              고객 유지에 어떤 영향을 미치나요?"
 *
 * → LLM이 분해:
 *   1. "우리 반품 정책은 무엇인가?"
 *   2. "경쟁사 반품 정책은 무엇인가?"
 *   3. "반품 정책이 고객 유지에 어떤 영향을 미치는가?"
 *
 * → 각 하위 쿼리를 독립적으로 검색
 * → 결과를 통합하여 포괄적인 컨텍스트 제공
 * ```
 *
 * ## 왜 분해가 효과적인가
 * 복합 질문은 여러 주제에 걸쳐있다. 단일 Vector Search로는
 * 질문의 일부만 다루는 문서를 놓칠 수 있다. 초점이 맞춰진
 * 하위 쿼리로 분해하면 각 검색이 특정 측면을 타겟팅하여 재현율(recall)이 향상된다.
 *
 * @param chatClient 쿼리 분해에 사용하는 Spring AI ChatClient
 * @see <a href="https://arxiv.org/abs/2205.10625">Least-to-Most Prompting (Zhou et al., 2023)</a>
 */
class DecompositionQueryTransformer(
    private val chatClient: ChatClient
) : QueryTransformer {

    override suspend fun transform(query: String): List<String> {
        return try {
            val subQueries = decomposeQuery(query)
            if (subQueries.isEmpty()) {
                logger.warn { "Decomposition returned empty result, falling back to original query" }
                listOf(query)
            } else {
                // 원본 쿼리도 포함하여 검색 범위를 넓히고, 중복 제거
                (subQueries + query).distinct()
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Query decomposition failed, falling back to original query" }
            listOf(query)
        }
    }

    /** LLM을 호출하여 쿼리를 하위 쿼리들로 분해한다. */
    private suspend fun decomposeQuery(query: String): List<String> {
        return withContext(Dispatchers.IO) {
            val response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(query)
                .call()
                .chatResponse()
                ?.result?.output?.text

            parseSubQueries(response)
        }
    }

    companion object {
        /** LLM에 전달하는 쿼리 분해 시스템 프롬프트 */
        internal const val SYSTEM_PROMPT =
            "Break down this complex question into 2-4 simpler sub-questions that can be " +
                "independently searched. If the question is already simple, return it as-is.\n\n" +
                "Respond with one sub-question per line, no numbering or bullets."

        /**
         * LLM 응답을 줄 단위로 파싱하여 하위 쿼리 목록으로 변환한다.
         * 빈 줄은 필터링한다.
         */
        internal fun parseSubQueries(response: String?): List<String> {
            if (response.isNullOrBlank()) return emptyList()
            return response.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
    }
}

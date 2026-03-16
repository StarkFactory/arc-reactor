package com.arc.reactor.rag.impl

import com.arc.reactor.rag.QueryTransformer
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * HyDE (Hypothetical Document Embeddings) 쿼리 변환기.
 *
 * LLM을 사용하여 쿼리에 대한 **가상 답변 문서**를 생성한 뒤,
 * 원본 쿼리와 가상 문서 모두를 검색 쿼리로 사용한다.
 * 이를 통해 질문과 답변 간의 어휘 격차(vocabulary gap)를 해소하여
 * 검색 성능을 향상시킨다.
 *
 * ## 동작 원리
 * ```
 * 사용자 쿼리: "우리 반품 정책이 뭐야?"
 *
 * → LLM이 가상 답변을 생성:
 *   "우리 반품 정책은 구매 후 30일 이내에 전액 환불이 가능합니다.
 *    제품은 미사용 상태이며 원래 포장을 유지해야 합니다."
 *
 * → 두 가지로 검색:
 *   1. "우리 반품 정책이 뭐야?" (원본 쿼리)
 *   2. "우리 반품 정책은 구매 후 30일 이내에..." (가상 문서)
 * ```
 *
 * ## 왜 HyDE가 효과적인가
 * Vector Search는 유사한 임베딩을 매칭한다.
 * 질문("정책이 뭐야?")과 답변("정책은 30일...")은 어휘가 다르지만 의미는 유사하다.
 * 가상 답변을 생성함으로써 실제 문서와 임베딩 공간에서 더 가까운 쿼리를 만든다.
 *
 * @param chatClient 가상 문서 생성에 사용하는 Spring AI ChatClient
 * @param systemPrompt LLM에 전달할 커스텀 시스템 프롬프트 (선택적)
 * @see <a href="https://arxiv.org/abs/2212.10496">HyDE Paper (Gao et al., 2022)</a>
 */
class HyDEQueryTransformer(
    private val chatClient: ChatClient,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) : QueryTransformer {

    override suspend fun transform(query: String): List<String> {
        return try {
            val hypotheticalDocument = generateHypotheticalDocument(query)
            if (hypotheticalDocument.isNullOrBlank()) {
                logger.warn { "HyDE generation returned empty result, falling back to original query" }
                listOf(query)
            } else {
                // 원본 쿼리 + 가상 문서 모두 사용하여 검색 범위를 극대화
                listOf(query, hypotheticalDocument)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "HyDE generation failed, falling back to original query" }
            listOf(query)
        }
    }

    /** LLM을 호출하여 쿼리에 대한 가상 답변 문서를 생성한다. */
    private suspend fun generateHypotheticalDocument(query: String): String? {
        return withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemPrompt)
                .user(query)
                .call()
                .chatResponse()
                ?.result
                ?.output
                ?.text
        }
    }

    companion object {
        /** 가상 문서 생성을 위한 기본 시스템 프롬프트 */
        internal const val DEFAULT_SYSTEM_PROMPT =
            "Write a short passage (2-3 sentences) that would directly answer the following question. " +
                "Write as if you are quoting from an authoritative document. " +
                "Do not include any preamble like 'Here is...' — just write the passage itself."
    }
}

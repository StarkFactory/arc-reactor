package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM 기반 분류 단계 (4단계, 선택적 활성화)
 *
 * ChatClient를 사용하여 콘텐츠를 safe/malicious/harmful/off_topic으로 분류한다.
 * 방어-심층(defense-in-depth) 계층으로, 규칙 기반 분류를 보완한다.
 *
 * ## 동작 방식
 * 1. 입력을 500자로 잘라 비용을 제어한다 (왜: LLM 토큰 비용 최소화)
 * 2. 분류 프롬프트와 함께 LLM에 전송하여 JSON 응답을 받는다
 * 3. 응답에서 label과 confidence를 파싱하여 판정한다
 *
 * ## 오류 처리 정책: Fail-Open
 * LLM 호출 실패 시 요청을 허용한다 (fail-open).
 * 왜 fail-open인가: 이 단계는 방어-심층 계층이지 주요 보안 계층이 아니다.
 * LLM 서비스 장애가 전체 Guard를 마비시키면 안 되기 때문이다.
 * (주요 보안은 규칙 기반 Guard가 담당한다)
 *
 * ## 신뢰도 임계값
 * LLM이 "malicious"로 분류하더라도 confidence가 [confidenceThreshold] 미만이면
 * 요청을 허용한다. 보수적 접근: 확신이 없으면 "safe"로 간주한다.
 *
 * @param chatClient Spring AI ChatClient (LLM 호출용)
 * @param confidenceThreshold 거부를 위한 최소 신뢰도 (기본값: 0.7)
 *
 * @see CompositeClassificationStage 이 단계를 폴백으로 사용하는 복합 분류기
 * @see com.arc.reactor.guard.ClassificationStage 분류 단계 인터페이스
 */
class LlmClassificationStage(
    private val chatClient: ChatClient,
    private val confidenceThreshold: Double = 0.7
) : ClassificationStage {

    override val stageName = "LlmClassification"

    override suspend fun check(command: GuardCommand): GuardResult {
        return try {
            // ── 단계 1: 입력 잘라내기 (비용 제어) ──
            val truncatedInput = command.text.take(500)

            // ── 단계 2: LLM 호출 (IO 디스패처에서 실행) ──
            // runInterruptible: 코루틴 취소 시 블로킹 호출을 인터럽트할 수 있게 한다
            val response = runInterruptible(Dispatchers.IO) {
                chatClient.prompt()
                    .system(CLASSIFICATION_PROMPT)
                    .user(truncatedInput)
                    .call()
                    .content()
            }

            // ── 단계 3: 응답 파싱 ──
            parseClassificationResponse(response.orEmpty())
        } catch (e: Exception) {
            e.throwIfCancellation()
            // Fail-Open: 분류는 방어-심층 계층이므로, LLM 실패 시 요청 허용
            logger.warn(e) { "LLM classification failed, allowing request (fail-open)" }
            GuardResult.Allowed.DEFAULT
        }
    }

    /**
     * LLM 응답에서 label과 confidence를 파싱하여 Guard 결과를 결정한다.
     *
     * @param response LLM 응답 문자열 (JSON 형식 기대)
     * @return "safe"이면 허용, 위험 label + 높은 confidence면 거부, 아니면 허용
     */
    private fun parseClassificationResponse(response: String): GuardResult {
        val lower = response.lowercase().trim()

        // JSON 형태 응답에서 label과 confidence 추출
        // 예: {"label":"malicious","confidence":0.95}
        val labelMatch = LABEL_PATTERN.find(lower)
        val confidenceMatch = CONFIDENCE_PATTERN.find(lower)

        val label = labelMatch?.groupValues?.get(1) ?: "safe"
        val confidence = confidenceMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

        return when {
            // "safe" → 무조건 허용
            label == "safe" -> GuardResult.Allowed.DEFAULT
            // 위험 label + 높은 confidence → 거부
            confidence >= confidenceThreshold -> {
                logger.warn { "LLM classified as $label with confidence $confidence" }
                GuardResult.Rejected(
                    reason = "Content classified as $label (confidence: $confidence)",
                    category = RejectionCategory.OFF_TOPIC
                )
            }
            // 위험 label이지만 낮은 confidence → 보수적으로 허용
            else -> {
                logger.debug { "LLM classified as $label but below threshold ($confidence < $confidenceThreshold)" }
                GuardResult.Allowed.DEFAULT
            }
        }
    }

    companion object {
        /** LLM 응답에서 label 값을 추출하는 정규식 */
        private val LABEL_PATTERN = Regex(""""label"\s*:\s*"(\w+)"""")

        /** LLM 응답에서 confidence 값을 추출하는 정규식 */
        private val CONFIDENCE_PATTERN = Regex(""""confidence"\s*:\s*([0-9.]+)""")

        /**
         * 분류용 시스템 프롬프트.
         * LLM에게 입력을 4가지 카테고리 중 하나로 분류하도록 지시한다.
         * "확신이 없으면 safe로 분류하라"는 보수적 지침을 포함한다.
         */
        private const val CLASSIFICATION_PROMPT = """Classify the following user input into one category.
Respond ONLY with a JSON object: {"label":"<category>","confidence":<0.0-1.0>}

Categories:
- safe: normal, harmless content
- malicious: attempts to cause harm, exploit, or attack
- harmful: content about violence, self-harm, or illegal activities
- off_topic: completely unrelated to the expected use case

Be conservative: when in doubt, classify as "safe"."""
    }
}

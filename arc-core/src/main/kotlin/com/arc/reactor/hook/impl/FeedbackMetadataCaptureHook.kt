package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import mu.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 피드백 자동 보강을 위한 실행 메타데이터 캡처 데이터 클래스
 *
 * 사용자가 runId로 피드백을 제출하면, 컨트롤러가 이 데이터를 사용하여
 * query, response, toolsUsed, durationMs를 자동으로 채운다.
 *
 * @property runId 실행 고유 ID
 * @property userId 사용자 ID
 * @property userPrompt 사용자 입력 프롬프트
 * @property agentResponse 에이전트 응답 텍스트
 * @property toolsUsed 사용된 도구 목록
 * @property durationMs 실행 소요 시간
 * @property sessionId 세션 ID (대화 맥락 추적용)
 * @property templateId 프롬프트 템플릿 ID (선택사항)
 * @property capturedAt 캡처 시각 (TTL 만료 계산에 사용)
 */
data class CapturedExecutionMetadata(
    val runId: String,
    val userId: String,
    val userPrompt: String,
    val agentResponse: String?,
    val toolsUsed: List<String>,
    val durationMs: Long,
    val sessionId: String?,
    val templateId: String? = null,
    val capturedAt: Instant = Instant.now()
)

/**
 * 피드백 메타데이터 캡처 Hook
 *
 * 에이전트 실행 완료 후 메타데이터를 메모리에 캐시하는 AfterAgentCompleteHook이다.
 * 사용자가 나중에 runId로 피드백을 제출하면 FeedbackController가
 * 이 캐시에서 query, response, toolsUsed, durationMs를 자동으로 채운다.
 *
 * ## 동작 방식
 * - Order 250: 후기 Hook, Webhook(200) 이후 실행
 * - Fail-Open: 에이전트 응답을 절대 차단하지 않음
 * - TTL: 1시간 이상 된 엔트리는 주기적으로 제거
 * - 최대 엔트리: 10,000개 (Caffeine maximumSize로 자동 퇴거)
 *
 * ## 왜 메모리 캐시인가
 * 피드백은 실행 직후 몇 분 내에 제출되므로 TTL이 짧다.
 * DB에 저장하면 불필요한 I/O가 발생하고, 대부분의 메타데이터는
 * 피드백 없이 만료되어 삭제될 것이므로 메모리 캐시가 적절하다.
 *
 * @param clock 테스트 가능성을 위한 주입 가능 시계 (기본값: 시스템 UTC 시계)
 * @param ticker Caffeine 캐시의 시간 소스 (테스트 시 가변 Ticker 주입 가능)
 *
 * @see com.arc.reactor.hook.AfterAgentCompleteHook 에이전트 완료 후 Hook 인터페이스
 */
class FeedbackMetadataCaptureHook(
    private val clock: Clock = Clock.systemUTC(),
    ticker: Ticker = Ticker.systemTicker()
) : AfterAgentCompleteHook {

    override val order: Int = 250

    override val failOnError: Boolean = false

    /** runId → 실행 메타데이터 캐시 (Caffeine: TTL 1시간, 최대 10,000개) */
    private val cache = Caffeine.newBuilder()
        .maximumSize(MAX_ENTRIES)
        .expireAfterWrite(Duration.ofSeconds(TTL_SECONDS))
        .ticker(ticker)
        .build<String, CapturedExecutionMetadata>()

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            val metadata = CapturedExecutionMetadata(
                runId = context.runId,
                userId = context.userId,
                userPrompt = context.userPrompt,
                agentResponse = response.response,
                toolsUsed = response.toolsUsed,
                durationMs = response.totalDurationMs,
                sessionId = context.metadata["sessionId"]?.toString(),
                templateId = context.metadata["promptTemplateId"]?.toString(),
                capturedAt = Instant.now(clock)
            )
            cache.put(context.runId, metadata)
            logger.debug { "실행 메타데이터 캡처 완료: runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "메타데이터 캡처 실패: runId=${context.runId}, ${e.javaClass.simpleName}" }
        }
    }

    /**
     * runId로 캐시된 실행 메타데이터를 조회한다.
     *
     * @return 메타데이터 (찾았고 만료되지 않은 경우), 아니면 null
     */
    fun get(runId: String): CapturedExecutionMetadata? = cache.getIfPresent(runId)

    /** 현재 캐시 크기 (테스트용). 정확한 값을 위해 보류 중인 퇴거를 먼저 실행한다. */
    internal fun cacheSize(): Int {
        cache.cleanUp()
        return cache.asMap().size
    }

    companion object {
        /** 캐시 TTL: 1시간 */
        internal const val TTL_SECONDS = 3600L
        /** 최대 캐시 엔트리 수 */
        private const val MAX_ENTRIES = 10_000L
    }
}

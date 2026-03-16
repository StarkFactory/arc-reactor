package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
 * - 최대 엔트리: 10,000개 (초과 시 가장 오래된 항목 제거)
 * - 제거 스로틀: 최소 30초 간격으로만 실행
 *
 * ## 왜 메모리 캐시인가
 * 피드백은 실행 직후 몇 분 내에 제출되므로 TTL이 짧다.
 * DB에 저장하면 불필요한 I/O가 발생하고, 대부분의 메타데이터는
 * 피드백 없이 만료되어 삭제될 것이므로 메모리 캐시가 적절하다.
 *
 * @param clock 테스트 가능성을 위한 주입 가능 시계 (기본값: 시스템 UTC 시계)
 *
 * @see com.arc.reactor.hook.AfterAgentCompleteHook 에이전트 완료 후 Hook 인터페이스
 */
class FeedbackMetadataCaptureHook(
    private val clock: Clock = Clock.systemUTC()
) : AfterAgentCompleteHook {

    override val order: Int = 250

    override val failOnError: Boolean = false

    /** runId → 실행 메타데이터 캐시 */
    private val cache = ConcurrentHashMap<String, CapturedExecutionMetadata>()

    /** 마지막 제거 실행 시각 (스로틀링용) */
    private val lastEvictionTime = AtomicReference(Instant.EPOCH)

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
            cache[context.runId] = metadata
            evictIfNeeded()
            logger.debug { "Captured execution metadata for runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Failed to capture metadata for runId=${context.runId}: ${e.message}" }
        }
    }

    /**
     * runId로 캐시된 실행 메타데이터를 조회한다.
     *
     * @return 메타데이터 (찾았고 만료되지 않은 경우), 아니면 null
     */
    fun get(runId: String): CapturedExecutionMetadata? {
        val entry = cache[runId] ?: return null
        val cutoff = Instant.now(clock).minusSeconds(TTL_SECONDS)
        if (entry.capturedAt.isBefore(cutoff)) {
            cache.remove(runId)
            return null
        }
        return entry
    }

    /**
     * 스로틀링을 적용하여 만료된 엔트리를 제거한다.
     * 최소 [EVICTION_INTERVAL_SECONDS]초 간격으로만 실행된다.
     */
    private fun evictIfNeeded() {
        val now = Instant.now(clock)
        val lastRun = lastEvictionTime.get()
        if (now.epochSecond - lastRun.epochSecond < EVICTION_INTERVAL_SECONDS) return
        if (!lastEvictionTime.compareAndSet(lastRun, now)) return

        evictStale()
    }

    /**
     * TTL 만료된 엔트리를 제거하고, 최대 엔트리 수를 초과하면 가장 오래된 항목을 제거한다.
     */
    private fun evictStale() {
        // TTL 만료 엔트리 제거
        val cutoff = Instant.now(clock).minusSeconds(TTL_SECONDS)
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.capturedAt.isBefore(cutoff)) {
                iterator.remove()
            }
        }

        // 최대 엔트리 수 초과 시 가장 오래된 항목 제거
        if (cache.size > MAX_ENTRIES) {
            val toRemove = cache.entries.toList()
                .sortedBy { it.value.capturedAt }
                .take(cache.size - MAX_ENTRIES)
            for (entry in toRemove) {
                cache.remove(entry.key)
            }
        }
    }

    /** 현재 캐시 크기 (테스트용) */
    internal fun cacheSize(): Int = cache.size

    companion object {
        /** 캐시 TTL: 1시간 */
        internal const val TTL_SECONDS = 3600L
        /** 최대 캐시 엔트리 수 */
        private const val MAX_ENTRIES = 10_000
        /** 제거 실행 최소 간격: 30초 */
        private const val EVICTION_INTERVAL_SECONDS = 30L
    }
}

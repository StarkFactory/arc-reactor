package com.arc.reactor.approval

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * 대기 중인 도구 승인 요청 저장소 인터페이스
 *
 * [CompletableDeferred]를 사용하여 사람이 REST API를 통해
 * 승인 또는 거부할 때까지 에이전트 코루틴을 일시 중지한다.
 *
 * ## HITL(Human-In-The-Loop) 흐름
 * 1. 에이전트가 승인이 필요한 도구 호출에 도달
 * 2. [requestApproval]이 대기 엔트리를 생성하고 코루틴을 일시 중지
 * 3. 사람이 REST API를 호출 → [approve] 또는 [reject]
 * 4. CompletableDeferred가 완료되고 에이전트가 재개
 *
 * @see InMemoryPendingApprovalStore 메모리 기반 구현체 (단일 인스턴스용)
 * @see JdbcPendingApprovalStore JDBC 기반 구현체 (다중 인스턴스용)
 */
interface PendingApprovalStore {

    /**
     * 승인 요청을 제출하고 해결될 때까지 일시 중지한다.
     *
     * @param runId 에이전트 실행 ID
     * @param userId 요청을 시작한 사용자
     * @param toolName 승인이 필요한 도구
     * @param arguments 도구 호출 인수
     * @param timeoutMs 최대 대기 시간 (0이면 기본값 사용)
     * @param context 4단계 구조화 컨텍스트 (opt-in, null이면 컨텍스트 없음)
     * @return 사람의 승인 응답
     */
    suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = 0,
        context: ApprovalContext? = null
    ): ToolApprovalResponse

    /**
     * 모든 대기 중인 승인 요청을 조회한다.
     */
    fun listPending(): List<ApprovalSummary>

    /**
     * 특정 사용자의 대기 중인 승인 요청을 조회한다.
     */
    fun listPendingByUser(userId: String): List<ApprovalSummary>

    /**
     * 대기 중인 요청을 승인한다.
     *
     * @param approvalId 승인 요청 ID
     * @param modifiedArguments 수정된 인수 (선택사항)
     * @return 해당 승인이 존재하고 처리되었으면 true
     */
    fun approve(approvalId: String, modifiedArguments: Map<String, Any?>? = null): Boolean

    /**
     * 대기 중인 요청을 거부한다.
     *
     * @param approvalId 승인 요청 ID
     * @param reason 거부 사유 (선택사항)
     * @return 해당 승인이 존재하고 처리되었으면 true
     */
    fun reject(approvalId: String, reason: String? = null): Boolean
}

/**
 * 메모리 기반 대기 승인 저장소
 *
 * 단일 인스턴스 배포에 적합하다.
 * [CompletableDeferred]를 사용하여 승인 대기와 완료를 구현한다.
 *
 * R310 fix: ConcurrentHashMap → Caffeine bounded cache. 기존 구현은 `pending`이
 * 무제한으로 성장할 수 있어 악성 호출(타임아웃 전에 대량 제출)로 OOM 가능성이 있었다.
 * 이제 [maxPending] 상한(기본 10,000)을 넘으면 Caffeine W-TinyLFU 정책으로 evict.
 * evict된 엔트리는 `CompletableDeferred`가 완료되지 못하고 `withTimeoutOrNull`에서
 * 자연스럽게 타임아웃되므로 coroutine leak 없음.
 *
 * @param defaultTimeoutMs 기본 승인 타임아웃 (밀리초, 기본값: 5분)
 * @param maxPending 동시 대기 가능한 최대 승인 요청 수 (기본값: 10,000)
 *
 * @see JdbcPendingApprovalStore 다중 인스턴스 환경용 JDBC 구현체
 */
class InMemoryPendingApprovalStore(
    private val defaultTimeoutMs: Long = 300_000, // 5분
    maxPending: Long = DEFAULT_MAX_PENDING
) : PendingApprovalStore {

    /** 대기 중인 승인 엔트리 (요청 + CompletableDeferred) */
    private data class PendingEntry(
        val request: ToolApprovalRequest,
        val deferred: CompletableDeferred<ToolApprovalResponse>
    )

    /** approvalId → 대기 엔트리 매핑 (Caffeine bounded cache) */
    private val pending: Cache<String, PendingEntry> = Caffeine.newBuilder()
        .maximumSize(maxPending)
        .build()

    override suspend fun requestApproval(
        runId: String,
        userId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long,
        context: ApprovalContext?
    ): ToolApprovalResponse {
        val id = UUID.randomUUID().toString()
        val request = ToolApprovalRequest(
            id = id, runId = runId, userId = userId,
            toolName = toolName, arguments = arguments,
            timeoutMs = timeoutMs,
            context = context
        )
        val deferred = CompletableDeferred<ToolApprovalResponse>()
        pending.put(id, PendingEntry(request, deferred))

        logger.info { "승인 요청: id=$id, tool=$toolName, user=$userId" }

        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs

        return try {
            // 코루틴을 일시 중지하고 사람의 응답을 기다린다
            val response = withTimeoutOrNull(effectiveTimeout) { deferred.await() }
            if (response != null) {
                response
            } else {
                logger.warn { "승인 타임아웃: id=$id, tool=$toolName" }
                ToolApprovalResponse(approved = false, reason = "Approval timed out after ${effectiveTimeout}ms")
            }
        } finally {
            // 완료/타임아웃 후 대기 엔트리 정리
            pending.invalidate(id)
        }
    }

    override fun listPending(): List<ApprovalSummary> {
        return pending.asMap().values.map { it.toSummary() }
    }

    override fun listPendingByUser(userId: String): List<ApprovalSummary> {
        return pending.asMap().values
            .filter { it.request.userId == userId }
            .map { it.toSummary() }
    }

    override fun approve(approvalId: String, modifiedArguments: Map<String, Any?>?): Boolean {
        val entry = pending.getIfPresent(approvalId) ?: return false
        val response = ToolApprovalResponse(
            approved = true,
            modifiedArguments = modifiedArguments
        )
        logger.info { "승인 허가: id=$approvalId, tool=${entry.request.toolName}" }
        // CompletableDeferred를 완료하여 일시 중지된 에이전트 코루틴을 재개
        return entry.deferred.complete(response)
    }

    override fun reject(approvalId: String, reason: String?): Boolean {
        val entry = pending.getIfPresent(approvalId) ?: return false
        val response = ToolApprovalResponse(
            approved = false,
            reason = reason ?: "Rejected by human"
        )
        logger.info { "승인 거부: id=$approvalId, tool=${entry.request.toolName}, reason=$reason" }
        return entry.deferred.complete(response)
    }

    /** PendingEntry를 API 응답용 ApprovalSummary로 변환한다 */
    private fun PendingEntry.toSummary() = ApprovalSummary(
        id = request.id,
        runId = request.runId,
        userId = request.userId,
        toolName = request.toolName,
        arguments = request.arguments,
        requestedAt = request.requestedAt,
        status = ApprovalStatus.PENDING,
        context = request.context
    )

    companion object {
        /** InMemory 구현 기본 상한. 초과 시 Caffeine W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_PENDING: Long = 10_000L
    }
}

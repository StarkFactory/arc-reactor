package com.arc.reactor.approval

import java.time.Instant

/**
 * 도구 승인 요청 데이터 클래스
 *
 * 사람의 승인이 필요한 도구 호출에 대한 요청 정보를 담는다.
 *
 * @property id 승인 요청 고유 ID
 * @property runId 에이전트 실행 ID ([com.arc.reactor.hook.model.HookContext.runId]와 상관)
 * @property userId 에이전트 요청을 시작한 사용자
 * @property toolName 승인이 필요한 도구 이름
 * @property arguments LLM이 생성한 도구 인수
 * @property requestedAt 승인 요청 시각
 * @property timeoutMs 승인 타임아웃 (밀리초, 0이면 기본값 사용)
 *
 * @see PendingApprovalStore 승인 요청을 관리하는 저장소
 */
data class ToolApprovalRequest(
    /** 승인 요청 고유 ID */
    val id: String,

    /** 에이전트 실행 ID */
    val runId: String,

    /** 요청을 시작한 사용자 */
    val userId: String,

    /** 승인이 필요한 도구 이름 */
    val toolName: String,

    /** LLM이 생성한 도구 인수 */
    val arguments: Map<String, Any?>,

    /** 승인 요청 시각 */
    val requestedAt: Instant = Instant.now(),

    /** 승인 타임아웃 (밀리초, 0이면 기본값 사용) */
    val timeoutMs: Long = 0
)

/**
 * 승인 요청에 대한 사람의 응답 데이터 클래스
 *
 * @property approved 도구 호출 승인 여부
 * @property reason 거부 사유 (선택사항)
 * @property modifiedArguments 수정된 인수 (선택사항, 사람이 도구 파라미터를 조정할 수 있음)
 */
data class ToolApprovalResponse(
    /** 도구 호출 승인 여부 */
    val approved: Boolean,

    /** 거부 사유 (선택사항) */
    val reason: String? = null,

    /** 수정된 인수 (선택사항, 사람이 파라미터 조정 가능) */
    val modifiedArguments: Map<String, Any?>? = null
)

/**
 * 승인 상태 열거형
 */
enum class ApprovalStatus {
    /** 승인 대기 중 */
    PENDING,
    /** 승인됨 */
    APPROVED,
    /** 거부됨 */
    REJECTED,
    /** 타임아웃 (시간 초과) */
    TIMED_OUT
}

/**
 * REST API용 승인 요약 데이터 클래스
 *
 * @property id 승인 요청 ID
 * @property runId 에이전트 실행 ID
 * @property userId 요청 사용자
 * @property toolName 도구 이름
 * @property arguments 도구 인수
 * @property requestedAt 요청 시각
 * @property status 승인 상태
 */
data class ApprovalSummary(
    val id: String,
    val runId: String,
    val userId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val requestedAt: Instant,
    val status: ApprovalStatus
)

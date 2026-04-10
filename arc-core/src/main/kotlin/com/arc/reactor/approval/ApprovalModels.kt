package com.arc.reactor.approval

import java.time.Instant

/**
 * 도구 호출의 복구 가능성(reversibility) 분류.
 *
 * 승인 요청 시 사용자에게 "이 작업이 실행되면 되돌릴 수 있는가"를 알려주기 위해 사용한다.
 * `docs/agent-work-directive.md` §3.1 Tool Approval UX 강화 원칙에 따라 추가되었다.
 */
enum class Reversibility {
    /** 복구 가능 — 예: 상태 전이, 댓글 작성 (취소 가능). */
    REVERSIBLE,

    /** 부분 복구 — 예: 대량 업데이트 중 일부만 롤백 가능. */
    PARTIALLY_REVERSIBLE,

    /** 복구 불가 — 예: 삭제, 금전 거래, 외부 시스템 이벤트. */
    IRREVERSIBLE,

    /** 알 수 없음 (기본값) — 컨텍스트 해석기가 판단하지 못한 경우. */
    UNKNOWN;

    /**
     * 사람이 읽을 수 있는 한국어 라벨.
     *
     * R240: 승인 요청 포맷터(Slack/CLI/REST)에서 사용자에게 복구 가능성을 표시할 때 사용.
     */
    fun koreanLabel(): String = when (this) {
        REVERSIBLE -> "복구 가능"
        PARTIALLY_REVERSIBLE -> "부분 복구 가능"
        IRREVERSIBLE -> "복구 불가"
        UNKNOWN -> "알 수 없음"
    }

    /**
     * Slack/UI 메시지용 시각 아이콘 (ASCII 대체 가능한 유니코드 심볼).
     *
     * R240: 사용자가 한눈에 위험도를 파악할 수 있도록 4단계에 서로 다른 심볼을 할당한다.
     * 이모지는 실제 그래픽 이모지가 아닌 유니코드 심볼로, Slack mrkdwn에서 안전하게 렌더링된다.
     */
    fun symbol(): String = when (this) {
        REVERSIBLE -> "✓"
        PARTIALLY_REVERSIBLE -> "~"
        IRREVERSIBLE -> "✗"
        UNKNOWN -> "?"
    }
}

/**
 * 승인 요청에 부가되는 4단계 구조화 컨텍스트.
 *
 * `docs/agent-work-directive.md` §3.1 Tool Approval UX 강화 원칙에서 요구하는
 * "왜 / 무엇을 / 영향 범위 / 되돌릴 수 있는지"를 구조화하여 전달한다.
 *
 * ## 필드
 *
 * - [reason] — 왜 승인이 필요한가 (사람이 읽을 수 있는 사유)
 *   예: "파괴적 작업", "금액 한도 초과", "외부 시스템에 영향을 주는 호출"
 * - [action] — 무엇을 실행하려는가 (사람이 읽을 수 있는 행동 설명)
 *   예: "JAR-36 이슈를 'Done' 상태로 전이", "bitbucket PR #42 머지"
 * - [impactScope] — 영향 범위 (어디까지 영향을 주는가)
 *   예: "1 Jira 이슈", "전체 저장소 web-labs", "사용자 42명"
 * - [reversibility] — 되돌릴 수 있는가
 *
 * ## 특성
 *
 * 모든 필드는 nullable/default 이다. 기존 API와 완전히 호환되며, 컨텍스트를 제공하지
 * 않더라도 기존 승인 경로는 그대로 동작한다. 이 데이터 클래스의 존재 자체가 **opt-in**
 * 이며, [ApprovalContextResolver] 빈을 등록한 사용자만 실제로 채워진 컨텍스트를 받는다.
 *
 * ## MCP 호환성
 *
 * 이 모델은 atlassian-mcp-server 연동 경로를 전혀 건드리지 않는다. 도구 호출 인수
 * (`arguments`)는 그대로 전달되며, 컨텍스트는 승인 UX를 강화하기 위한 메타데이터일 뿐이다.
 *
 * @property reason 왜 승인이 필요한가
 * @property action 무엇을 실행하려는가
 * @property impactScope 영향 범위
 * @property reversibility 복구 가능성
 *
 * @see ApprovalContextResolver 도구별 컨텍스트 생성 전략
 * @see ToolApprovalRequest 컨텍스트가 부가되는 요청 객체
 */
data class ApprovalContext(
    /** 왜 승인이 필요한가 (사람이 읽을 수 있는 사유) */
    val reason: String? = null,

    /** 무엇을 실행하려는가 (사람이 읽을 수 있는 행동 설명) */
    val action: String? = null,

    /** 영향 범위 (예: "1 Jira 이슈", "전체 저장소") */
    val impactScope: String? = null,

    /** 복구 가능성 */
    val reversibility: Reversibility = Reversibility.UNKNOWN
) {
    /**
     * 컨텍스트에 의미 있는 정보가 하나라도 설정되어 있는지 확인한다.
     * 모든 필드가 기본값(null 또는 UNKNOWN)이면 `false`를 반환한다.
     */
    fun hasAnyInformation(): Boolean {
        return reason != null ||
            action != null ||
            impactScope != null ||
            reversibility != Reversibility.UNKNOWN
    }

    /**
     * R240: 컨텍스트를 한 줄 요약 텍스트로 렌더링한다.
     *
     * 비어있는 필드는 생략하며, 모든 필드가 비어있으면 빈 문자열을 반환한다.
     * 섹션 구분자는 ` · ` (가운데 점)을 사용하여 한국어 가독성을 높인다.
     *
     * 예시:
     * ```
     * 이슈 이동 · JAR-36을 'Done'으로 전이 · 1 이슈 · 복구 가능
     * ```
     */
    fun toOneLineSummary(): String {
        if (!hasAnyInformation()) return ""
        val parts = mutableListOf<String>()
        reason?.takeIf { it.isNotBlank() }?.let(parts::add)
        action?.takeIf { it.isNotBlank() }?.let(parts::add)
        impactScope?.takeIf { it.isNotBlank() }?.let(parts::add)
        if (reversibility != Reversibility.UNKNOWN) {
            parts += reversibility.koreanLabel()
        }
        return parts.joinToString(" · ")
    }

    companion object {
        /** 빈 컨텍스트 — 정보 없음을 명시적으로 표현할 때 사용한다. */
        val EMPTY: ApprovalContext = ApprovalContext()
    }
}

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
 * @property context 4단계 구조화 컨텍스트 (opt-in, null이면 컨텍스트 없음)
 *
 * @see PendingApprovalStore 승인 요청을 관리하는 저장소
 * @see ApprovalContext 4단계 구조화 컨텍스트
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
    val timeoutMs: Long = 0,

    /** 4단계 구조화 컨텍스트 (opt-in, null이면 컨텍스트 없음) */
    val context: ApprovalContext? = null
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
 * @property context 4단계 구조화 컨텍스트 (opt-in, null이면 컨텍스트 없음)
 */
data class ApprovalSummary(
    val id: String,
    val runId: String,
    val userId: String,
    val toolName: String,
    val arguments: Map<String, Any?>,
    val requestedAt: Instant,
    val status: ApprovalStatus,
    val context: ApprovalContext? = null
)

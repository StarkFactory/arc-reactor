package com.arc.reactor.scheduler

import java.time.Instant

/**
 * 스케줄 작업의 실행 모드.
 *
 * - MCP_TOOL: 단일 MCP 도구를 직접 호출한다 (원래 동작).
 * - AGENT: 전체 ReAct 에이전트 루프를 실행한다 — LLM이 여러 MCP 도구를 추론하여 자연어 결과를 생성.
 *
 * WHY: 단순한 도구 호출(MCP_TOOL)과 복잡한 추론이 필요한 작업(AGENT)을
 * 같은 스케줄러 인프라에서 지원하기 위함.
 * AGENT 모드는 매 실행마다 LLM 호출이 발생하므로 API 비용이 발생한다.
 *
 * @see DynamicSchedulerService 스케줄러 서비스
 */
enum class ScheduledJobType {
    MCP_TOOL, AGENT
}

/**
 * MCP 도구 실행과 전체 에이전트 실행을 모두 지원하는 스케줄 작업 정의.
 *
 * ## MCP_TOOL 모드 (원래 동작)
 * [mcpServerName]과 [toolName] 필요. 단일 도구를 직접 호출한다.
 *
 * ## AGENT 모드 (에이전트 실행)
 * [agentPrompt] 필요. 등록된 모든 MCP 도구를 활용하여 전체 ReAct 루프를 실행한다.
 * 시스템 프롬프트 결정 순서: [agentSystemPrompt] -> [personaId] -> 기본 페르소나 -> 폴백.
 *
 * WHY: 두 가지 실행 모드를 하나의 ScheduledJob 모델로 통합하여
 * REST API와 도구 인터페이스를 단순하게 유지한다.
 *
 * @param id 작업 고유 식별자
 * @param name 작업 이름 (고유)
 * @param description 작업 설명
 * @param cronExpression Spring 6필드 크론 표현식 (초 분 시 일 월 요일)
 * @param timezone 크론 표현식에 적용할 시간대 (기본: Asia/Seoul)
 * @param jobType 실행 모드 (MCP_TOOL 또는 AGENT)
 * @param mcpServerName MCP_TOOL 모드: MCP 서버 이름
 * @param toolName MCP_TOOL 모드: 호출할 도구 이름
 * @param toolArguments MCP_TOOL 모드: 도구 호출 인자 (템플릿 변수 지원)
 * @param agentPrompt AGENT 모드: 매 실행 시 LLM에 전달할 프롬프트
 * @param personaId AGENT 모드: 시스템 프롬프트 결정에 사용할 페르소나 ID
 * @param agentSystemPrompt AGENT 모드: 직접 지정 시스템 프롬프트 (최우선)
 * @param agentModel AGENT 모드: 사용할 LLM 모델
 * @param agentMaxToolCalls AGENT 모드: 최대 도구 호출 수
 * @param tags 분류/필터링을 위한 태그
 * @param slackChannelId 결과를 전송할 Slack 채널 ID (선택)
 * @param teamsWebhookUrl 결과를 전송할 Teams 웹훅 URL (선택)
 * @param retryOnFailure 실패 시 재시도 여부
 * @param maxRetryCount retryOnFailure=true일 때 최대 재시도 횟수
 * @param executionTimeoutMs 작업별 실행 타임아웃 (밀리초). null=전역 기본값 사용.
 * @param enabled 활성화 여부
 * @param lastRunAt 마지막 실행 시각
 * @param lastStatus 마지막 실행 상태
 * @param lastResult 마지막 실행 결과 (잘린 텍스트)
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see DynamicSchedulerService 스케줄러 서비스
 */
data class ScheduledJob(
    val id: String = "",
    val name: String,
    val description: String? = null,
    val cronExpression: String,
    val timezone: String = "Asia/Seoul",
    val jobType: ScheduledJobType = ScheduledJobType.MCP_TOOL,

    // MCP_TOOL 모드 필드
    val mcpServerName: String? = null,
    val toolName: String? = null,
    val toolArguments: Map<String, Any> = emptyMap(),

    // AGENT 모드 필드
    val agentPrompt: String? = null,
    val personaId: String? = null,
    val agentSystemPrompt: String? = null,
    val agentModel: String? = null,
    val agentMaxToolCalls: Int? = null,

    // 태그
    val tags: Set<String> = emptySet(),

    // 공통
    val slackChannelId: String? = null,
    val teamsWebhookUrl: String? = null,

    /** 실패 시 재시도. true이면 maxRetryCount만큼 재시도한다. */
    val retryOnFailure: Boolean = false,
    /** retryOnFailure=true일 때 최대 재시도 횟수. */
    val maxRetryCount: Int = 3,
    /** 작업별 실행 타임아웃 (밀리초). null=전역 기본값 사용. */
    val executionTimeoutMs: Long? = null,

    val enabled: Boolean = true,
    val lastRunAt: Instant? = null,
    val lastStatus: JobExecutionStatus? = null,
    val lastResult: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 작업 실행 상태.
 */
enum class JobExecutionStatus {
    SUCCESS, FAILED, RUNNING, SKIPPED
}

/**
 * 스케줄 작업의 단일 실행 기록.
 *
 * @param id 실행 기록 고유 식별자
 * @param jobId 작업 ID
 * @param jobName 작업 이름
 * @param status 실행 결과 상태
 * @param result 실행 결과 텍스트
 * @param durationMs 실행 소요 시간 (밀리초)
 * @param dryRun 드라이런 여부 (실제 실행 아님)
 * @param startedAt 실행 시작 시각
 * @param completedAt 실행 완료 시각
 */
data class ScheduledJobExecution(
    val id: String = "",
    val jobId: String,
    val jobName: String,
    val status: JobExecutionStatus,
    val result: String? = null,
    val durationMs: Long = 0,
    val dryRun: Boolean = false,
    val startedAt: Instant = Instant.now(),
    val completedAt: Instant? = null
)

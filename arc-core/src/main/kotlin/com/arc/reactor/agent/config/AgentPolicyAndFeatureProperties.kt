package com.arc.reactor.agent.config

import com.arc.reactor.guard.output.impl.OutputBlockPattern

/**
 * Human-in-the-Loop 승인 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       enabled: true
 *       timeout-ms: 300000
 *       tool-names:
 *         - delete_order
 *         - process_refund
 * ```
 *
 * @see com.arc.reactor.approval.ToolApprovalPolicy 승인 정책 인터페이스
 * @see com.arc.reactor.approval.PendingApprovalStore 승인 대기 저장소
 */
data class ApprovalProperties(
    /** Human-in-the-Loop 승인 활성화 */
    val enabled: Boolean = false,

    /** 기본 승인 타임아웃 (밀리초, 0이면 5분) */
    val timeoutMs: Long = 300_000,

    /** 완료된 승인 항목의 정리 전 보존 기간 (밀리초) */
    val resolvedRetentionMs: Long = 7 * 24 * 60 * 60 * 1000L,

    /** 승인이 필요한 도구 이름 목록 (빈 집합 = 커스텀 ToolApprovalPolicy 사용) */
    val toolNames: Set<String> = emptySet()
)

/**
 * 도구 정책 설정.
 *
 * 주로 기업 환경에서 "쓰기"(부작용이 있는) 도구를 안전하게 처리하기 위해 사용한다.
 * 일반적인 전략:
 * - 웹: 쓰기 도구에 HITL 승인 필요
 * - Slack: 쓰기 도구 거부 (채팅 우선 UX + 위험)
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-policy:
 *       enabled: true
 *       write-tool-names:
 *         - jira_create_issue
 *         - confluence_update_page
 *       deny-write-channels:
 *         - slack
 * ```
 *
 * @see com.arc.reactor.agent.impl.ToolCallOrchestrator 도구 실행 시 정책 적용
 */
data class ToolPolicyProperties(
    /** 도구 정책 적용 활성화 (opt-in). */
    val enabled: Boolean = false,

    /**
     * 동적 도구 정책 설정 (관리자 관리).
     *
     * 활성화하면 Arc Reactor가 런타임에 DB/API를 통해 도구 정책 값을 로드하고 갱신할 수 있어,
     * 기업이 재배포 없이 쓰기 도구 규칙을 변경할 수 있다.
     */
    val dynamic: ToolPolicyDynamicProperties = ToolPolicyDynamicProperties(),

    /** "쓰기"(부작용이 있는) 도구 이름 목록. */
    val writeToolNames: Set<String> = emptySet(),

    /** 쓰기 도구가 거부되는 채널 (fail-closed). */
    val denyWriteChannels: Set<String> = setOf("slack"),

    /**
     * [denyWriteChannels]에서도 허용되는 쓰기 도구 이름의 예외 목록.
     *
     * 엄격한 기본값(채팅 채널에서 거부)을 유지하면서도
     * 안전한 쓰기 작업의 소수 부분집합(예: Jira 이슈 생성)을 Slack에서 허용할 때 유용하다.
     */
    val allowWriteToolNamesInDenyChannels: Set<String> = emptySet(),

    /**
     * 거부 채널별 쓰기 도구 허용 목록.
     *
     * [denyWriteChannels]에 있는 채널에서는 기본적으로 쓰기 도구가 차단된다.
     * 이 맵으로 특정 채널에서 특정 쓰기 도구를 허용할 수 있다.
     *
     * 설정 예시:
     * ```yaml
     * arc:
     *   reactor:
     *     tool-policy:
     *       enabled: true
     *       deny-write-channels: [slack]
     *       allow-write-tool-names-by-channel:
     *         slack: [jira_create_issue]
     * ```
     */
    val allowWriteToolNamesByChannel: Map<String, Set<String>> = emptyMap(),

    /** 정책에 의해 도구 호출이 거부될 때 반환되는 에러 메시지. */
    val denyWriteMessage: String = "Error: This tool is not allowed in this channel"
)

/**
 * 동적 도구 정책 설정.
 */
data class ToolPolicyDynamicProperties(
    /** DB 기반 도구 정책 활성화 (관리자 API 업데이트 + 주기적 갱신). */
    val enabled: Boolean = false,

    /** 동적 정책 활성화 시 캐시 갱신 간격 (밀리초). */
    val refreshMs: Long = 10_000
)

/**
 * 멀티모달 (파일 업로드 / 미디어 URL) 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     multimodal:
 *       enabled: false              # 파일 업로드 및 미디어 URL 처리 비활성화
 *       max-file-size-bytes: 10485760  # 파일당 10MB
 *       max-files-per-request: 5
 * ```
 */
data class MultimodalProperties(
    /** 멀티모달 지원 활성화 (/api/chat/multipart 파일 업로드 및 JSON 요청의 mediaUrls) */
    val enabled: Boolean = true,

    /** 업로드 파일당 최대 허용 크기 (바이트). 기본 10MB. */
    val maxFileSizeBytes: Long = 10 * 1024 * 1024,

    /** 멀티파트 요청당 최대 파일 수. 기본 5. */
    val maxFilesPerRequest: Int = 5
)

/**
 * 응답 후처리 설정.
 */
data class ResponseProperties(
    /** 최대 응답 길이 (문자 수). 0 = 무제한 (기본). */
    val maxLength: Int = 0,

    /** 응답 필터 체인 처리 활성화. */
    val filtersEnabled: Boolean = true
)

/**
 * 실행 후 응답 검증을 위한 출력 가드 설정.
 *
 * 활성화하면 LLM 응답이 호출자에게 반환되기 전에
 * PII, 정책 위반, 커스텀 정규식 패턴을 검사한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     output-guard:
 *       enabled: true
 *       pii-masking-enabled: true
 *       custom-patterns:
 *         - pattern: "(?i)internal\\s+use\\s+only"
 *           action: REJECT
 *           name: "Internal Document"
 * ```
 *
 * @see com.arc.reactor.guard.output.OutputGuardPipeline 출력 가드 파이프라인
 */
data class OutputGuardProperties(
    /** 출력 가드 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 내장 PII 마스킹 단계 활성화. */
    val piiMaskingEnabled: Boolean = true,

    /** 동적 런타임 관리 정규식 규칙 활성화 (관리자 관리). */
    val dynamicRulesEnabled: Boolean = true,

    /** 동적 규칙 캐시 갱신 간격 (밀리초). */
    val dynamicRulesRefreshMs: Long = 3000,

    /** 차단 또는 마스킹을 위한 커스텀 정규식 패턴 목록. */
    val customPatterns: List<OutputBlockPattern> = emptyList()
)

/**
 * 인텐트 분류 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     intent:
 *       enabled: true
 *       confidence-threshold: 0.6
 *       llm-model: gemini
 *       rule-confidence-threshold: 0.8
 * ```
 *
 * @see com.arc.reactor.intent.IntentResolver 인텐트 해석기
 */
data class IntentProperties(
    /** 인텐트 분류 활성화 (opt-in) */
    val enabled: Boolean = false,

    /** 인텐트 프로필을 적용하기 위한 최소 신뢰도 */
    val confidenceThreshold: Double = 0.6,

    /** 분류에 사용할 LLM 프로바이더 (null = 기본 프로바이더 사용) */
    val llmModel: String? = null,

    /** LLM 폴백을 건너뛰기 위한 최소 규칙 기반 신뢰도 */
    val ruleConfidenceThreshold: Double = 0.8,

    /** LLM 프롬프트에서 인텐트당 최대 few-shot 예시 수 */
    val maxExamplesPerIntent: Int = 3,

    /** 컨텍스트 인식 분류에 포함할 최대 대화 턴 수 */
    val maxConversationTurns: Int = 2,

    /** 차단할 인텐트 이름 — 이 인텐트로 분류된 요청은 거부됨 */
    val blockedIntents: Set<String> = emptySet()
)

/**
 * 동적 스케줄러 설정.
 *
 * 활성화하면 REST API를 통해 관리되는 cron 스케줄 MCP 도구 실행을 허용한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     scheduler:
 *       enabled: true
 *       thread-pool-size: 5
 *       default-execution-timeout-ms: 300000
 * ```
 */
data class SchedulerProperties(
    /** 동적 스케줄러 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 스케줄된 작업 실행을 위한 스레드 풀 크기. */
    val threadPoolSize: Int = 5,

    /** 사용자가 지정하지 않은 경우의 기본 타임존. */
    val defaultTimezone: String = java.time.ZoneId.systemDefault().id,

    /** 명시적 타임아웃이 없는 작업의 기본 실행 타임아웃 (밀리초). */
    val defaultExecutionTimeoutMs: Long = 300_000,

    /** 작업당 유지할 최대 실행 히스토리 항목 수. 0 = 무제한. */
    val maxExecutionsPerJob: Int = 100
)

/**
 * 입출력 경계값 정책 설정.
 *
 * 모든 길이 기반 경계값 검사를 하나의 설정 그룹으로 제공한다:
 * 입력 최소/최대, 시스템 프롬프트 최대, 출력 최소/최대.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     boundaries:
 *       input-min-chars: 1
 *       input-max-chars: 5000
 *       system-prompt-max-chars: 50000
 *       output-min-chars: 0
 *       output-max-chars: 0
 *       output-min-violation-mode: warn
 * ```
 *
 * @see com.arc.reactor.agent.impl.OutputBoundaryEnforcer 출력 경계값 적용
 */
data class BoundaryProperties(
    /** 최소 입력 길이 (문자 수). */
    val inputMinChars: Int = 1,

    /** 최대 입력 길이 (문자 수). */
    val inputMaxChars: Int = 10000,

    /** 최대 시스템 프롬프트 길이 (문자 수). 0 = 비활성. */
    val systemPromptMaxChars: Int = 50000,

    /** 최소 출력 길이 (문자 수). 0 = 비활성. */
    val outputMinChars: Int = 0,

    /** 최대 출력 길이 (문자 수). 0 = 비활성. */
    val outputMaxChars: Int = 0,

    /** 출력이 outputMinChars 미만일 때의 정책. */
    val outputMinViolationMode: OutputMinViolationMode = OutputMinViolationMode.WARN
)

/**
 * 도구 파라미터 보강 설정.
 *
 * [requesterAwareToolNames]에 나열된 도구는 LLM이 생략한 경우
 * 요청 메타데이터에서 호출자의 ID(계정 ID 또는 이메일)를 자동으로 받는다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-enrichment:
 *       requester-aware-tool-names:
 *         - jira_my_open_issues
 *         - bitbucket_review_queue
 * ```
 *
 * @see com.arc.reactor.agent.impl.ToolCallOrchestrator 도구 실행 시 파라미터 보강
 */
data class ToolEnrichmentProperties(
    /** 요청자 ID로 보강되어야 하는 도구 이름 목록. */
    val requesterAwareToolNames: Set<String> = emptySet()
)

/**
 * Citation(출처 인용) 자동 포맷 설정.
 *
 * 활성화되고 응답에 검증된 출처가 존재하면,
 * 응답 내용 끝에 Citation 섹션이 자동으로 추가된다. 중복 출처는 제거된다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     citation:
 *       enabled: true
 *       format: markdown
 * ```
 */
data class CitationProperties(
    /** Citation 자동 포맷 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** Citation 형식. 현재 "markdown"만 지원. */
    val format: String = "markdown"
)

/**
 * 동적 모델 라우팅 설정.
 *
 * 비용/품질 기반으로 요청을 적절한 LLM 모델로 자동 라우팅한다.
 * 단순 질문은 저렴한 모델(flash/haiku)로, 복잡한 추론은 비싼 모델(pro/opus)로 전달한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     model-routing:
 *       enabled: true
 *       default-model: gemini-2.0-flash
 *       high-complexity-model: gemini-2.5-pro
 *       routing-strategy: balanced
 *       complexity-threshold-chars: 500
 * ```
 *
 * @see com.arc.reactor.agent.routing.ModelRouter 라우팅 인터페이스
 * @see com.arc.reactor.agent.routing.CostAwareModelRouter 기본 구현
 */
data class ModelRoutingProperties(
    /** 동적 모델 라우팅 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 단순 요청에 사용할 기본(저비용) 모델 ID. */
    val defaultModel: String = "gemini-2.0-flash",

    /** 복잡한 요청에 사용할 고급(고비용) 모델 ID. */
    val highComplexityModel: String = "gemini-2.5-pro",

    /** 라우팅 전략: "cost-optimized", "quality-first", "balanced". */
    val routingStrategy: String = "balanced",

    /** 복잡도 판단을 위한 입력 길이 임계값 (문자 수). */
    val complexityThresholdChars: Int = 500
)

/**
 * 단계별 토큰 예산 추적 설정.
 *
 * ReAct 루프의 각 단계(LLM 호출, 도구 실행)별 토큰 소비를 추적하고
 * 예산 초과 시 경고 또는 중단을 유도한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     budget:
 *       enabled: true
 *       max-tokens-per-request: 50000
 *       soft-limit-percent: 80
 * ```
 *
 * @see com.arc.reactor.agent.budget.StepBudgetTracker 예산 추적기
 */
data class BudgetProperties(
    /** 토큰 예산 추적 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 요청당 최대 토큰 예산. */
    val maxTokensPerRequest: Int = 50000,

    /** 소프트 리밋 비율 (1-99). 이 비율 도달 시 경고 로그를 기록한다. */
    val softLimitPercent: Int = 80
)

/**
 * 출력 최소 길이 위반 처리 정책.
 *
 * @see com.arc.reactor.agent.impl.OutputBoundaryEnforcer 위반 모드에 따른 정책 적용
 */
enum class OutputMinViolationMode {
    /** 경고 로그를 기록하고 짧은 응답을 그대로 전달한다. */
    WARN,

    /** 더 긴 응답을 요청하는 추가 LLM 호출을 한 번 시도한다. 여전히 짧으면 WARN으로 폴백한다. */
    RETRY_ONCE,

    /** OUTPUT_TOO_SHORT 에러 코드로 실패한다. */
    FAIL
}

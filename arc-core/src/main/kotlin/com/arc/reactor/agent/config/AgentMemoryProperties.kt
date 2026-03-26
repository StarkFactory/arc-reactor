package com.arc.reactor.agent.config

/**
 * 대화 메모리 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     memory:
 *       summary:
 *         enabled: true
 *         trigger-message-count: 20
 *         recent-message-count: 10
 *         llm-model: gemini-2.5-flash
 *         max-narrative-tokens: 500
 * ```
 *
 * @see com.arc.reactor.memory.ConversationManager 대화 히스토리 관리
 */
data class MemoryProperties(
    /** 계층적 요약 설정 */
    val summary: SummaryProperties = SummaryProperties(),

    /** 사용자별 장기 메모리 설정 */
    val user: UserMemoryProperties = UserMemoryProperties()
)

/**
 * 사용자별 장기 메모리 설정.
 *
 * 활성화하면 에이전트가 대화 세션 간에 사용자별 사실, 선호도, 최근 주제를 기억한다.
 * 메모리는 자동으로 시스템 프롬프트에 주입된다.
 *
 * @see com.arc.reactor.hook.impl.UserMemoryInjectionHook 메모리 주입 훅
 */
data class UserMemoryProperties(
    /** 사용자별 장기 메모리 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /**
     * 사용자 메모리(사실/선호도)를 시스템 프롬프트에 주입.
     * [enabled]=true 필요. 기본 비활성 (opt-in).
     */
    val injectIntoPrompt: Boolean = false,

    /** 주입되는 사용자 메모리 컨텍스트 블록의 최대 문자 길이. */
    val maxPromptInjectionChars: Int = 1000,

    /** 사용자당 유지할 최대 최근 주제 수. */
    val maxRecentTopics: Int = 10,

    /** JDBC 관련 설정 */
    val jdbc: UserMemoryJdbcProperties = UserMemoryJdbcProperties()
)

/**
 * 사용자 메모리 영속성을 위한 JDBC 관련 설정.
 */
data class UserMemoryJdbcProperties(
    /** 사용자 메모리 레코드를 위한 데이터베이스 테이블 이름. */
    val tableName: String = "user_memories"
)

/**
 * 계층적 대화 요약 설정.
 *
 * 활성화하면 오래된 메시지가 구조화된 사실 + 내러티브로 요약되고
 * 최근 메시지는 원문 그대로 보존된다. 긴 대화에서 컨텍스트 손실을 방지한다.
 *
 * @see com.arc.reactor.memory.ConversationManager 요약 트리거 및 저장
 */
data class SummaryProperties(
    /** 계층적 메모리 요약 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 요약이 트리거되기 전 최소 메시지 수. */
    val triggerMessageCount: Int = 20,

    /** 원문 그대로 유지할 최근 메시지 수 (요약하지 않음). */
    val recentMessageCount: Int = 10,

    /** 요약에 사용할 LLM 프로바이더 (null = 기본 프로바이더 사용). */
    val llmModel: String? = null,

    /** 내러티브 요약의 최대 토큰 예산. */
    val maxNarrativeTokens: Int = 500
)

/**
 * Arc Reactor 추적(트레이싱) 설정.
 *
 * 활성화하면 에이전트 요청 스팬, Guard 스팬, LLM 호출 스팬, 도구 호출 스팬이 발행된다.
 * OpenTelemetry가 클래스패스에 없으면 no-op 트레이서가 사용되며 모든 연산의 비용이 제로이다.
 *
 * @see com.arc.reactor.tracing.ArcReactorTracer 트레이서 인터페이스
 */
data class TracingProperties(
    /** 스팬 발행 활성화. 기본 ON — OTel이 없으면 no-op 트레이서가 오버헤드 없이 동작한다. */
    val enabled: Boolean = true,

    /** 스팬에 `service.name`으로 부착되는 서비스 이름. */
    val serviceName: String = "arc-reactor",

    /**
     * 스팬 속성에 사용자 ID 포함 여부.
     * 기본 비활성. 트레이스에서 의도치 않은 PII 유출을 방지하기 위함.
     */
    val includeUserId: Boolean = false
)

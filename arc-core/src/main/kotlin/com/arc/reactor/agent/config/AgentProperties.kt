package com.arc.reactor.agent.config

import com.arc.reactor.a2a.A2aProperties
import com.arc.reactor.promptlab.PromptLabProperties
import com.arc.reactor.tool.filter.ToolFilterProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Arc Reactor 에이전트 설정.
 *
 * `arc.reactor.*` 접두사로 시작하는 모든 에이전트 관련 설정 속성을 관리한다.
 * Spring Boot의 [ConfigurationProperties]를 통해 YAML/환경변수에서 바인딩된다.
 *
 * @see com.arc.reactor.autoconfigure.ArcReactorAutoConfiguration 자동 설정에서 이 속성 사용
 * @see AgentPolicyAndFeatureProperties 정책 및 기능 토글 기본값
 */
@ConfigurationProperties(prefix = "arc.reactor")
data class AgentProperties(
    /** LLM 설정 (프로바이더, temperature, 토큰 제한 등) */
    val llm: LlmProperties = LlmProperties(),

    /** Guard 설정 (속도 제한, 인젝션 탐지, 분류 등) */
    val guard: GuardProperties = GuardProperties(),

    /** RAG(검색 증강 생성) 설정 */
    val rag: RagProperties = RagProperties(),

    /** 동시성 설정 (동시 요청 수, 타임아웃 등) */
    val concurrency: ConcurrencyProperties = ConcurrencyProperties(),

    /** 재시도 설정 (최대 횟수, 지수 백오프 등) */
    val retry: RetryProperties = RetryProperties(),

    /** 요청당 최대 도구 수 */
    val maxToolsPerRequest: Int = 30,

    /** 최대 도구 호출 횟수 (무한 루프 방지) */
    val maxToolCalls: Int = 10,

    /** CORS 설정 */
    val cors: CorsProperties = CorsProperties(),

    /** 보안 헤더 설정 */
    val securityHeaders: SecurityHeadersProperties = SecurityHeadersProperties(),

    /** MCP(Model Context Protocol) 설정 */
    val mcp: McpConfigProperties = McpConfigProperties(),

    /** 웹훅 알림 설정 */
    val webhook: WebhookConfigProperties = WebhookConfigProperties(),

    /** 도구 선택 전략 설정 */
    val toolSelection: ToolSelectionProperties = ToolSelectionProperties(),

    /** Human-in-the-Loop 승인 설정 */
    val approval: ApprovalProperties = ApprovalProperties(),

    /** 도구 정책 설정 (읽기 전용 vs 쓰기 도구 등) */
    val toolPolicy: ToolPolicyProperties = ToolPolicyProperties(),

    /** 멀티모달 (파일 업로드 / 미디어 URL) 설정 */
    val multimodal: MultimodalProperties = MultimodalProperties(),

    /** 응답 후처리 설정 */
    val response: ResponseProperties = ResponseProperties(),

    /** 서킷 브레이커 설정 */
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties(),

    /** 응답 캐시 설정 */
    val cache: CacheProperties = CacheProperties(),

    /** 장애 완화 / 폴백 설정 */
    val fallback: FallbackProperties = FallbackProperties(),

    /** 인텐트 분류 설정 */
    val intent: IntentProperties = IntentProperties(),

    /** 출력 가드 설정 */
    val outputGuard: OutputGuardProperties = OutputGuardProperties(),

    /** 동적 스케줄러 설정 */
    val scheduler: SchedulerProperties = SchedulerProperties(),

    /** 입출력 경계값 정책 설정 */
    val boundaries: BoundaryProperties = BoundaryProperties(),

    /** 대화 메모리 설정 */
    val memory: MemoryProperties = MemoryProperties(),

    /** Prompt Lab 설정 */
    val promptLab: PromptLabProperties = PromptLabProperties(),

    /** 추적(트레이싱) 설정 */
    val tracing: TracingProperties = TracingProperties(),

    /** 도구 파라미터 보강 설정 */
    val toolEnrichment: ToolEnrichmentProperties = ToolEnrichmentProperties(),

    /** 도구 결과 캐시 설정 */
    val toolResultCache: ToolResultCacheProperties = ToolResultCacheProperties(),

    /** Citation(출처 인용) 자동 포맷 설정 */
    val citation: CitationProperties = CitationProperties(),

    /** 도구 멱등성 보호 설정 */
    val toolIdempotency: ToolIdempotencyProperties = ToolIdempotencyProperties(),

    /** 실행 체크포인트 설정 */
    val checkpoint: CheckpointProperties = CheckpointProperties(),

    /** 동적 모델 라우팅 설정 */
    val modelRouting: ModelRoutingProperties = ModelRoutingProperties(),

    /** 단계별 토큰 예산 추적 설정 */
    val budget: BudgetProperties = BudgetProperties(),

    /** 동적 도구 필터 설정 */
    val toolFilter: ToolFilterProperties = ToolFilterProperties(),

    /** A2A 에이전트 카드 설정 */
    val a2a: A2aProperties = A2aProperties(),

    /** 자동 모드 선택(Mode Resolver) 설정 */
    val modeResolver: ModeResolverProperties = ModeResolverProperties(),

    /** 멀티 에이전트(Supervisor 패턴) 설정 */
    val multiAgent: MultiAgentProperties = MultiAgentProperties(),

    /** 도구 의존성 DAG 설정 */
    val toolDependency: ToolDependencyProperties = ToolDependencyProperties(),

    /** SLO 알림 설정 */
    val slo: SloAlertProperties = SloAlertProperties(),

    /** 비용 이상 탐지 설정 */
    val costAnomaly: CostAnomalyProperties = CostAnomalyProperties(),

    /** 프롬프트 드리프트 감지 설정 */
    val promptDrift: PromptDriftProperties = PromptDriftProperties(),

    /** Guard 차단률 베이스라인 모니터링 설정 */
    val guardBlockRate: GuardBlockRateProperties = GuardBlockRateProperties()
)

/**
 * LLM 설정 속성.
 *
 * @see com.arc.reactor.agent.impl.ChatOptionsFactory 이 속성으로 ChatOptions 생성
 */
data class LlmProperties(
    /** 기본 LLM 프로바이더 (예: "gemini", "openai", "anthropic") */
    val defaultProvider: String = "gemini",

    /** 기본 LLM 모델 ID (비용 추적에 사용). 미지정 시 defaultProvider를 폴백으로 사용. */
    val defaultModel: String? = null,

    /** 기본 temperature (0에 가까울수록 결정적) */
    val temperature: Double = 0.1,

    /** 최대 출력 토큰 수 */
    val maxOutputTokens: Int = 4096,

    /** Top-P (핵 샘플링). null이면 프로바이더 기본값 사용. */
    val topP: Double? = null,

    /** 빈도 패널티. null이면 프로바이더 기본값 사용. */
    val frequencyPenalty: Double? = null,

    /** 존재 패널티. null이면 프로바이더 기본값 사용. */
    val presencePenalty: Double? = null,

    /**
     * Gemini Google Search Retrieval 그라운딩 활성화.
     * 기업 환경에서 의도치 않은 외부 검색을 방지하기 위해 기본 OFF.
     */
    val googleSearchRetrievalEnabled: Boolean = false,

    /** 최대 대화 히스토리 턴 수 */
    val maxConversationTurns: Int = 10,

    /** 최대 컨텍스트 윈도우 토큰 수 (토큰 기반 메시지 트리밍용) */
    val maxContextWindowTokens: Int = 128000,

    /** Anthropic 프롬프트 캐싱 설정 */
    val promptCaching: PromptCachingProperties = PromptCachingProperties()
)

/**
 * Anthropic 프롬프트 캐싱 설정.
 *
 * 활성화하면 반복되는 콘텐츠(시스템 프롬프트, 도구 정의)에
 * `cache_control: {"type": "ephemeral"}`을 표시하여 Anthropic이
 * 요청 간 캐시된 토큰을 재사용할 수 있게 한다.
 * 공통 접두사를 공유하는 요청에 대해 프롬프트 토큰 비용을 80-90% 절감할 수 있다.
 *
 * `anthropic` 프로바이더에서만 지원. 다른 프로바이더 요청은 영향 없음.
 *
 * Anthropic API가 요구하는 최소 캐시 가능 토큰 수:
 * - claude-3-5-sonnet-20241022 이상: 1024 토큰
 * - claude-3-haiku: 2048 토큰
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     llm:
 *       prompt-caching:
 *         enabled: true
 *         provider: anthropic
 *         cache-system-prompt: true
 *         cache-tools: true
 *         min-cacheable-tokens: 1024
 * ```
 */
data class PromptCachingProperties(
    /** Anthropic 프롬프트 캐싱 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 캐싱을 적용할 LLM 프로바이더. "anthropic"만 지원. */
    val provider: String = "anthropic",

    /** 시스템 프롬프트에 캐싱 표시 여부. */
    val cacheSystemPrompt: Boolean = true,

    /** 도구 정의에 캐싱 표시 여부. */
    val cacheTools: Boolean = true,

    /**
     * 캐싱 표시를 위한 최소 추정 토큰 수.
     * Anthropic이 거부할 짧은 프롬프트에 cache_control이 설정되는 것을 방지한다.
     */
    val minCacheableTokens: Int = 1024
)

/**
 * 재시도 설정 속성.
 *
 * @see com.arc.reactor.agent.impl.RetryExecutor 이 속성을 사용하여 재시도 실행
 */
data class RetryProperties(
    /** 최대 재시도 횟수 */
    val maxAttempts: Int = 3,

    /** 재시도 간 초기 지연 (밀리초) */
    val initialDelayMs: Long = 200,

    /** 백오프 승수 */
    val multiplier: Double = 2.0,

    /** 재시도 간 최대 지연 (밀리초) */
    val maxDelayMs: Long = 10000
)

/**
 * Guard 설정 속성.
 *
 * 요청 보안 가드레일의 5단계 파이프라인을 구성한다.
 * Guard는 fail-close 정책: 거부 시 즉시 요청 차단.
 *
 * @see com.arc.reactor.guard.RequestGuard Guard 파이프라인
 */
data class GuardProperties(
    /** Guard 활성화 여부 */
    val enabled: Boolean = true,

    /** 분당 요청 제한 */
    val rateLimitPerMinute: Int = 20,

    /** 시간당 요청 제한 */
    val rateLimitPerHour: Int = 200,

    /** 인젝션 탐지 활성화 여부 */
    val injectionDetectionEnabled: Boolean = true,

    /** 유니코드 정규화 활성화 (NFKC + 제로폭 문자 제거 + 호모글리프) */
    val unicodeNormalizationEnabled: Boolean = true,

    /** 최대 제로폭 문자 비율 (0.0-1.0). 초과 시 거부 */
    val maxZeroWidthRatio: Double = 0.1,

    /** 분류 활성화 (규칙 기반 + 선택적 LLM) */
    val classificationEnabled: Boolean = false,

    /** LLM 기반 분류 활성화 (classificationEnabled 필요) */
    val classificationLlmEnabled: Boolean = false,

    /** 시스템 프롬프트 유출 탐지를 위한 카나리 토큰 활성화 */
    val canaryTokenEnabled: Boolean = false,

    /** 도구 출력 새니타이징 활성화 */
    val toolOutputSanitizationEnabled: Boolean = false,

    /** Guard 감사 추적 활성화 */
    val auditEnabled: Boolean = true,

    /** 주제 이탈 탐지 활성화 (Crescendo 공격 방어) */
    val topicDriftEnabled: Boolean = false,

    /** 카나리 토큰 시드 (배포별 고유 토큰을 위해 재정의) */
    val canarySeed: String = "arc-reactor-canary",

    /** 테넌트별 속도 제한 */
    val tenantRateLimits: Map<String, TenantRateLimit> = emptyMap()
)

/**
 * 테넌트별 속도 제한 설정.
 *
 * @param perMinute 이 테넌트의 분당 요청 제한
 * @param perHour 이 테넌트의 시간당 요청 제한
 */
data class TenantRateLimit(
    /** 이 테넌트의 분당 요청 제한 */
    val perMinute: Int,

    /** 이 테넌트의 시간당 요청 제한 */
    val perHour: Int
)

/**
 * 동시성 설정 속성.
 *
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor 세마포어와 타임아웃에 사용
 */
data class ConcurrencyProperties(
    /** 최대 동시 요청 수 */
    val maxConcurrentRequests: Int = 20,

    /**
     * 요청 타임아웃 (밀리초).
     * R209: 30000 → 45000. R208 minimal prompt retry가 empty 발생 시 최대 2회 재시도를 하며,
     * 각 retry가 10~15s 소요되어 합계가 30s를 초과해 B3/B4 같은 케이스에서 timeout이 발생했다.
     * 45s로 확장하여 retry 2회까지 안정적으로 수용.
     */
    val requestTimeoutMs: Long = 45000,

    /** 개별 도구 호출 타임아웃 (밀리초) */
    val toolCallTimeoutMs: Long = 15000
)

/**
 * CORS 설정 속성.
 */
data class CorsProperties(
    /** CORS 활성화 (opt-in) */
    val enabled: Boolean = false,

    /** 허용된 오리진 목록 */
    val allowedOrigins: List<String> = listOf("http://localhost:3000"),

    /** 허용된 HTTP 메서드 목록 */
    val allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS"),

    /** 허용된 헤더 목록 */
    val allowedHeaders: List<String> = listOf("*"),

    /** 자격 증명(쿠키, Authorization 헤더) 허용 여부 */
    val allowCredentials: Boolean = false,

    /** 프리플라이트 캐시 지속 시간 (초) */
    val maxAge: Long = 3600
)

/**
 * 보안 헤더 설정 속성.
 */
data class SecurityHeadersProperties(
    /** 보안 헤더 활성화 (기본: true) */
    val enabled: Boolean = true
)

/**
 * MCP(Model Context Protocol) 설정 — 런타임 보안 및 연결 설정.
 *
 * MCP 서버는 REST API(`/api/mcp/servers`)를 통해 등록 및 관리된다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     mcp:
 *       security:
 *         allowed-server-names: []
 *         max-tool-output-length: 50000
 * ```
 */
data class McpConfigProperties(
    /** 보안 설정 */
    val security: McpSecurityProperties = McpSecurityProperties(),

    /** MCP 연결 타임아웃 (밀리초) */
    val connectionTimeoutMs: Long = 30_000,

    /** 자동 재연결 설정 */
    val reconnection: McpReconnectionProperties = McpReconnectionProperties(),

    /**
     * 사설/예약 IP 주소(루프백, 사이트-로컬, 링크-로컬) 연결 허용.
     * MCP 서버가 localhost에서 실행되는 로컬 개발 환경에서만 활성화할 것.
     */
    val allowPrivateAddresses: Boolean = false,

    /** 주기적 헬스체크 설정 */
    val health: McpHealthProperties = McpHealthProperties()
)

/**
 * MCP 보안 설정 속성.
 */
data class McpSecurityProperties(
    /** 허용된 MCP 서버 이름 (빈 집합 = 모두 허용) */
    val allowedServerNames: Set<String> = emptySet(),

    /** 최대 도구 출력 길이 (문자 수) */
    val maxToolOutputLength: Int = 50_000,

    /** 허용된 STDIO 명령 실행 파일. 이 기본 명령만 허용된다. */
    val allowedStdioCommands: Set<String> = DEFAULT_ALLOWED_STDIO_COMMANDS
) {
    companion object {
        /** MCP 서버용 기본 안전 STDIO 실행 파일 집합. */
        val DEFAULT_ALLOWED_STDIO_COMMANDS: Set<String> = setOf(
            "npx", "node", "python", "python3", "uvx", "uv", "docker", "deno", "bun"
        )
    }
}

/**
 * MCP 자동 재연결 설정.
 *
 * 활성화하면 실패한 MCP 서버 연결이 지수 백오프로 자동 재시도된다.
 * 도구 호출 시 연결이 끊어진 서버에 대해 온디맨드 재연결도 시도한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     mcp:
 *       reconnection:
 *         enabled: true
 *         max-attempts: 5
 *         initial-delay-ms: 5000
 *         multiplier: 2.0
 *         max-delay-ms: 60000
 * ```
 */
data class McpReconnectionProperties(
    /** 실패한 MCP 서버에 대한 자동 재연결 활성화. */
    val enabled: Boolean = true,

    /**
     * 최대 재연결 시도 횟수.
     * R173: 5 → 10으로 확대 — 시작 시 MCP 서버가 늦게 올라오는 환경에서
     * 더 오래 재시도. McpHealthPinger의 주기적 재시도가 이후를 책임진다.
     */
    val maxAttempts: Int = 10,

    /**
     * 재연결 시도 간 초기 지연 (밀리초).
     * R173: 5000 → 2000 — 시작 시 첫 재시도까지 너무 오래 걸리는 문제 완화.
     */
    val initialDelayMs: Long = 2000,

    /** 후속 시도에 대한 백오프 승수. */
    val multiplier: Double = 2.0,

    /** 재연결 시도 간 최대 지연 (밀리초). */
    val maxDelayMs: Long = 60_000
)

/**
 * MCP 서버 주기적 헬스체크 설정.
 *
 * 활성화하면 CONNECTED 상태의 MCP 서버를 주기적으로 점검하여
 * 조용히 끊어진 연결을 사전에 감지하고 재연결을 트리거한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     mcp:
 *       health:
 *         enabled: true
 *         ping-interval-seconds: 60
 * ```
 */
data class McpHealthProperties(
    /** MCP 헬스체크 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 헬스체크 간격 (초). */
    val pingIntervalSeconds: Long = 60
)

/**
 * 웹훅 알림 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     webhook:
 *       enabled: true
 *       url: https://example.com/webhook
 *       timeout-ms: 5000
 *       include-conversation: false
 * ```
 */
data class WebhookConfigProperties(
    /** 웹훅 알림 활성화 */
    val enabled: Boolean = false,

    /** POST 대상 URL */
    val url: String = "",

    /** HTTP 타임아웃 (밀리초) */
    val timeoutMs: Long = 5000,

    /** 페이로드에 전체 대화 포함 여부 */
    val includeConversation: Boolean = false
)

/**
 * 도구 선택 전략 설정.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-selection:
 *       strategy: semantic    # all | keyword | semantic
 *       similarity-threshold: 0.3
 *       max-results: 10
 * ```
 */
data class ToolSelectionProperties(
    /** 선택 전략: "all", "keyword", 또는 "semantic" */
    val strategy: String = "all",

    /** 시맨틱 선택의 최소 코사인 유사도 임계값 */
    val similarityThreshold: Double = 0.3,

    /** 시맨틱 선택에서 반환할 최대 도구 수 */
    val maxResults: Int = 10
)

/**
 * 도구 결과 캐시 설정.
 *
 * 활성화하면 동일한 도구 호출(같은 도구 이름 + 같은 인자)이 동일 ReAct 루프 내에서
 * 도구를 다시 실행하는 대신 캐시된 결과를 반환한다. LLM이 같은 도구를
 * 같은 인자로 반복 호출할 때 중복 호출을 방지한다.
 *
 * 캐시는 [com.arc.reactor.agent.impl.ToolCallOrchestrator] 인스턴스별로 스코핑되며
 * TTL 만료에 Caffeine을 사용한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-result-cache:
 *       enabled: true
 *       ttl-seconds: 60
 *       max-size: 200
 * ```
 */
data class ToolResultCacheProperties(
    /** 도구 결과 캐시 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 캐시 항목의 유효 시간(TTL, 초). */
    val ttlSeconds: Long = 60,

    /** 최대 캐시 항목 수. */
    val maxSize: Long = 200
)

/**
 * 도구 멱등성 보호 설정.
 *
 * 동일한 도구 호출(도구명 + 인수 해시)이 중복 실행되는 것을 방지한다.
 * TTL 기반 캐시로 중복 호출을 감지하고 이전 결과를 반환한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-idempotency:
 *       enabled: true
 *       ttl-seconds: 60
 *       max-size: 1000
 * ```
 */
data class ToolIdempotencyProperties(
    /** 도구 멱등성 보호 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 멱등성 캐시 항목의 유효 시간(TTL, 초). */
    val ttlSeconds: Long = 60,

    /** 최대 캐시 항목 수. */
    val maxSize: Long = 1000
)

/**
 * 실행 체크포인트 설정.
 *
 * ReAct 루프 중간 상태를 저장하여 장애 복구 또는 디버깅에 활용한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     checkpoint:
 *       enabled: true
 *       max-checkpoints-per-run: 50
 * ```
 */
data class CheckpointProperties(
    /** 실행 체크포인트 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 실행당 최대 체크포인트 수. */
    val maxCheckpointsPerRun: Int = 50
)

/**
 * 도구 의존성 DAG 설정.
 *
 * 도구 간 의존 관계를 선언하고 DAG 기반 위상 정렬로
 * 병렬/순차 실행 순서를 자동 결정한다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     tool-dependency:
 *       enabled: true
 * ```
 *
 * @see com.arc.reactor.tool.dependency.ToolDependencyGraph 의존성 그래프 인터페이스
 * @see com.arc.reactor.tool.dependency.ToolExecutionPlan 실행 계획
 */
data class ToolDependencyProperties(
    /** 도구 의존성 DAG 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false
)

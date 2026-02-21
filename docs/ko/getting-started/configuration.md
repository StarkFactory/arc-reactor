# 설정 레퍼런스 & 자동 구성

> **핵심 파일:** `AgentProperties.kt`, `ArcReactorAutoConfiguration.kt`
> 이 문서는 Arc Reactor의 모든 설정 옵션과 Spring Boot 자동 구성 메커니즘을 설명합니다.
> 처음 fork해서 시작한다면 [configuration-quickstart.md](configuration-quickstart.md)부터 보세요.

## 전체 설정 구조

```yaml
arc:
  reactor:
    max-tools-per-request: 20    # 요청당 최대 도구 수
    max-tool-calls: 10           # ReAct 루프 최대 도구 호출 횟수

    llm:                         # LLM 호출 설정
      temperature: 0.3
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000

    retry:                       # LLM 재시도 설정
      max-attempts: 3
      initial-delay-ms: 1000
      multiplier: 2.0
      max-delay-ms: 10000

    guard:                       # Guard 파이프라인 설정
      enabled: true
      rate-limit-per-minute: 10
      rate-limit-per-hour: 100
      injection-detection-enabled: true

    boundaries:                  # 입력/출력 경계 검사
      input-min-chars: 1
      input-max-chars: 5000

    rag:                         # RAG 파이프라인 설정
      enabled: false
      similarity-threshold: 0.7
      top-k: 10
      rerank-enabled: true
      max-context-tokens: 4000

    concurrency:                 # 동시성 제어
      max-concurrent-requests: 20
      request-timeout-ms: 30000

    cache:                       # 응답 캐싱 (opt-in)
      enabled: false
      max-size: 1000
      ttl-minutes: 60
      cacheable-temperature: 0.0

    circuit-breaker:             # 서킷 브레이커 (opt-in)
      enabled: false
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1

    fallback:                    # 우아한 성능 저하 (opt-in)
      enabled: false
      models: []
```

## 설정 그룹별 상세

### AgentProperties (루트)

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-tools-per-request` | Int | 20 | 한 번의 요청에서 사용 가능한 최대 도구 수. Local + MCP 도구 합산 후 `take(n)` 적용 |
| `max-tool-calls` | Int | 10 | ReAct 루프에서 허용하는 최대 도구 호출 횟수. 도달 시 도구 목록을 빈 리스트로 교체하여 강제 종료 |

### LlmProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `temperature` | Double | 0.3 | LLM 생성 온도. 0.0(결정적) ~ 2.0(창의적) |
| `max-output-tokens` | Int | 4096 | LLM 응답 최대 토큰 수 |
| `max-conversation-turns` | Int | 10 | Memory에서 로드할 최대 대화 턴 수 |
| `max-context-window-tokens` | Int | 128000 | 컨텍스트 윈도우 토큰 예산. `budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens` |

**주의사항:**
- `temperature`는 `AgentCommand.temperature`로 요청별 오버라이드 가능
- `max-context-window-tokens`는 사용하는 LLM 모델의 실제 컨텍스트 윈도우에 맞춰야 함 (GPT-4: 128K, Claude: 200K, Gemini: 1M)

### RetryProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-attempts` | Int | 3 | 최대 재시도 횟수 (초기 시도 포함) |
| `initial-delay-ms` | Long | 1000 | 첫 재시도 전 대기 시간 (ms) |
| `multiplier` | Double | 2.0 | 지수 백오프 배수. `delay = min(initialDelay * multiplier^attempt, maxDelay)` |
| `max-delay-ms` | Long | 10000 | 최대 대기 시간 (ms). 지수 증가의 상한선 |

**재시도 대상 (일시적 에러):**
- Rate limit (429)
- Timeout
- 5xx 서버 에러
- Connection 에러

**재시도 불가:**
- 인증 에러, Context too long, Invalid request
- `CancellationException` — 절대 재시도 안 함 (구조적 동시성 보장)

### GuardProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | Guard 파이프라인 활성화. `false`면 모든 Guard 단계 비활성화 |
| `rate-limit-per-minute` | Int | 10 | 사용자별 분당 요청 제한 |
| `rate-limit-per-hour` | Int | 100 | 사용자별 시간당 요청 제한 |
| `injection-detection-enabled` | Boolean | true | 프롬프트 인젝션 탐지 활성화 |

**동작 방식:**
- `enabled=false`: Guard 빈 자체가 생성되지 않음 (`@ConditionalOnProperty`)
- `injection-detection-enabled=false`: 인젝션 탐지 단계만 비활성화, 나머지 Guard는 동작

### BoundaryProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `input-min-chars` | Int | 1 | 사용자 입력 최소 길이 (문자 수) |
| `input-max-chars` | Int | 5000 | 사용자 입력 최대 길이 (문자 수) |
| `system-prompt-max-chars` | Int | 0 | 시스템 프롬프트 최대 길이. `0`이면 무제한 |

### ConcurrencyProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-concurrent-requests` | Int | 20 | 동시 에이전트 실행 수 제한. `Semaphore(permits)` 사용 |
| `request-timeout-ms` | Long | 30000 | 요청 전체 타임아웃 (ms). `withTimeout()` 적용 |

**주의:** 세마포어 대기 시간은 타임아웃에 포함됩니다. 즉, 세마포어 대기 중에도 타임아웃이 발생할 수 있습니다.

### CacheProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 응답 캐싱 활성화. 기본 비활성 (opt-in) |
| `max-size` | Long | 1000 | 캐시 항목 최대 수 |
| `ttl-minutes` | Long | 60 | 캐시 항목 TTL (분) |
| `cacheable-temperature` | Double | 0.0 | 요청 온도가 이 값 이하일 때만 캐시. `0.0`이면 결정적 요청만 캐시, `1.0`이면 모든 요청 캐시 |

**동작 방식:**
- 캐시 키: `userPrompt + systemPrompt + model + tools + responseFormat`의 SHA-256 해시
- 응답 필터 적용 전에 캐시됨
- 스트리밍 요청은 캐시되지 않음

### CircuitBreakerProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 서킷 브레이커 활성화. 기본 비활성 (opt-in) |
| `failure-threshold` | Int | 5 | 서킷을 열기 전 연속 실패 횟수 |
| `reset-timeout-ms` | Long | 30000 | OPEN에서 HALF_OPEN으로 전이 전 대기 시간 (ms) |
| `half-open-max-calls` | Int | 1 | HALF_OPEN 상태에서 허용되는 시험 호출 수 |

**상태 전이:**
- `CLOSED` (정상) -> N회 연속 실패 -> `OPEN` (모든 호출 거부)
- `OPEN` -> `resetTimeoutMs` 경과 -> `HALF_OPEN` (시험 호출 허용)
- `HALF_OPEN` -> 시험 성공 -> `CLOSED`
- `HALF_OPEN` -> 시험 실패 -> `OPEN`

### FallbackProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 우아한 성능 저하 활성화. 기본 비활성 (opt-in) |
| `models` | List&lt;String&gt; | [] | 폴백 모델 이름 우선순위 순서 (예: `[openai, anthropic]`) |

**동작 방식:**
- 기본 모델이 재시도 후에도 실패하면 발동
- 모델은 나열된 순서대로 순차 시도
- 해당 프로바이더 빈이 등록되어 있어야 함 (예: `SPRING_AI_OPENAI_API_KEY` 환경 변수)

### RagProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | RAG 파이프라인 활성화. `true`로 설정해야 RAG 관련 빈이 생성됨 |
| `similarity-threshold` | Double | 0.7 | 벡터 검색 유사도 임계값 (0.0~1.0) |
| `top-k` | Int | 10 | 벡터 검색 결과 수 |
| `rerank-enabled` | Boolean | true | 검색 결과 재순위 활성화 |
| `query-transformer` | String | `passthrough` | 질의 재작성 모드 (`passthrough` 또는 `hyde`) |
| `max-context-tokens` | Int | 4000 | RAG 컨텍스트에 할당할 최대 토큰 수 |

---

## 자동 구성 (Auto-Configuration)

### 빈 생성 순서와 조건

Arc Reactor는 Spring Boot Auto-Configuration으로 모든 핵심 빈을 자동 생성합니다. 모든 빈에 `@ConditionalOnMissingBean`이 적용되어 **사용자 정의 빈이 있으면 자동 생성을 건너뜁니다**.

```
ArcReactorAutoConfiguration
├── 항상 생성
│   ├── toolSelector          → AllToolSelector (모든 도구 선택)
│   ├── errorMessageResolver  → DefaultErrorMessageResolver (영어)
│   ├── agentMetrics          → NoOpAgentMetrics (메트릭 비활성화)
│   ├── tokenEstimator        → DefaultTokenEstimator (CJK 인식)
│   ├── conversationManager   → DefaultConversationManager
│   ├── mcpManager            → DefaultMcpManager
│   ├── hookExecutor          → HookExecutor (빈 Hook 리스트)
│   └── responseFilterChain   → ResponseFilterChain (빈 필터 리스트)
│
├── 조건부 생성
│   ├── jdbcMemoryStore       → @ConditionalOnClass(JdbcTemplate) + @ConditionalOnBean(DataSource)
│   ├── memoryStore           → InMemoryMemoryStore (jdbcMemoryStore 없을 때 폴백)
│   └── agentExecutor         → @ConditionalOnBean(ChatClient) (필수!)
│
├── guard.enabled=true (기본값) → GuardConfiguration
│   ├── rateLimitStage        → DefaultRateLimitStage
│   ├── inputValidationStage  → DefaultInputValidationStage
│   ├── injectionDetectionStage → DefaultInjectionDetectionStage (injection-detection-enabled=true)
│   └── requestGuard          → GuardPipeline(stages)
│
├── cache.enabled=true → CacheConfiguration
│   └── responseCache       → CaffeineResponseCache
│
├── circuit-breaker.enabled=true → CircuitBreakerConfiguration
│   └── circuitBreaker      → DefaultCircuitBreaker
│
├── fallback.enabled=true → FallbackConfiguration
│   └── fallbackStrategy    → ModelFallbackStrategy
│
└── rag.enabled=true → RagConfiguration
    ├── documentRetriever     → SpringAiVectorStoreRetriever (@ConditionalOnBean(VectorStore))
    ├── inMemoryRetriever     → InMemoryDocumentRetriever (VectorStore 없을 때 폴백)
    ├── documentReranker      → SimpleScoreReranker
    └── ragPipeline           → DefaultRagPipeline
```

### 핵심 패턴: @ConditionalOnMissingBean

모든 빈이 이 어노테이션을 사용하므로, 사용자 정의 구현으로 교체가 간단합니다:

```kotlin
// 사용자 정의 빈이 있으면 자동 생성 건너뜀
@Bean
fun toolSelector(): ToolSelector = MyCustomToolSelector()

@Bean
fun errorMessageResolver(): ErrorMessageResolver = KoreanErrorMessageResolver()

@Bean
fun agentMetrics(): AgentMetrics = MicrometerAgentMetrics(registry)
```

### MemoryStore 자동 선택 로직

```
DataSource 빈 있음?
├── YES → JdbcTemplate 클래스 있음?
│         ├── YES → JdbcMemoryStore (PostgreSQL)
│         └── NO  → InMemoryMemoryStore (폴백)
└── NO  → InMemoryMemoryStore (폴백)
```

코드 변경 없이 `build.gradle.kts`에서 JDBC 의존성 추가 + `application.yml`에 DataSource 설정만 하면 자동 전환됩니다.

### AgentExecutor 필수 의존성

`agentExecutor` 빈은 `@ConditionalOnBean(ChatClient::class)`가 적용되어 있습니다. `ChatClient` 빈이 없으면 에이전트가 생성되지 않습니다. Spring AI의 LLM provider 의존성이 필요합니다:

```kotlin
// build.gradle.kts — 하나 이상 활성화 필요
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
// implementation("org.springframework.ai:spring-ai-starter-model-openai")
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### 커스텀 빈 교체 예시

```kotlin
@Configuration
class MyConfig {

    // 한국어 에러 메시지
    @Bean
    fun errorMessageResolver() = ErrorMessageResolver { code, _ ->
        when (code) {
            AgentErrorCode.RATE_LIMITED -> "요청 한도 초과. 잠시 후 다시 시도하세요."
            AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
            else -> code.defaultMessage
        }
    }

    // Micrometer 메트릭
    @Bean
    fun agentMetrics(registry: MeterRegistry) = MicrometerAgentMetrics(registry)

    // 커스텀 도구 선택기
    @Bean
    fun toolSelector() = CategoryBasedToolSelector(
        categories = listOf(ToolCategory.SEARCH, ToolCategory.CALCULATION)
    )
}
```

---

## 프로덕션 설정 예시

### 고트래픽 환경

```yaml
arc:
  reactor:
    max-tool-calls: 5            # 루프 제한 강화
    concurrency:
      max-concurrent-requests: 50   # 동시 요청 증가
      request-timeout-ms: 60000     # 타임아웃 여유
    retry:
      max-attempts: 5              # 재시도 여유
      max-delay-ms: 30000
    guard:
      rate-limit-per-minute: 30    # Rate limit 완화
      rate-limit-per-hour: 500
```

### 비용 최적화

```yaml
arc:
  reactor:
    max-tool-calls: 3            # 도구 호출 최소화
    llm:
      temperature: 0.1           # 결정적 응답
      max-output-tokens: 2048    # 출력 토큰 절약
      max-context-window-tokens: 32000  # 컨텍스트 축소
    rag:
      max-context-tokens: 2000   # RAG 토큰 절약
      top-k: 5                   # 검색 결과 축소
```

### 보안 강화

```yaml
arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 5
      rate-limit-per-hour: 50
      injection-detection-enabled: true
    boundaries:
      input-max-chars: 3000
    concurrency:
      request-timeout-ms: 15000  # 짧은 타임아웃
```

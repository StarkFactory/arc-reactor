# 관측성 & 메트릭

> **핵심 파일:** `agent/metrics/AgentMetrics.kt`
> 이 문서는 에이전트 메트릭 인터페이스, 사용 가능한 메트릭 포인트, 모니터링 백엔드 통합 방법을 다룹니다.

## 개요

Arc Reactor는 프레임워크에 독립적인 `AgentMetrics` 인터페이스를 제공합니다. 자동 구성은 클래스패스 기반으로 구현을 선택합니다:

- **`MicrometerAgentMetrics`** 는 클래스패스에 `MeterRegistry` 빈이 있을 때 (즉, Micrometer가 의존성에 포함된 경우) `@Primary`로 자동 등록됩니다. 프로덕션 환경에서 권장하는 경로입니다.
- **`NoOpAgentMetrics`** 는 `MeterRegistry`가 없을 때 `@ConditionalOnMissingBean`을 통해 폴백으로 자동 등록됩니다. 모든 메서드가 no-op이므로 메트릭이 실질적으로 비활성화됩니다.

사용자는 항상 커스텀 `AgentMetrics` 빈을 정의하여 둘 다 오버라이드할 수 있습니다.

## AgentMetrics 인터페이스

다음 카테고리에 걸쳐 메트릭 기록 메서드를 제공합니다:

### 실행 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordExecution(result)` | 비스트리밍 에이전트 실행 완료 후 | `AgentResult` (success, durationMs, content, errorCode) |
| `recordStreamingExecution(result)` | 스트리밍 에이전트 실행 완료 후 | `AgentResult` (위와 동일) |

### 도구 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordToolCall(toolName, durationMs, success)` | 각 도구 호출(로컬 또는 MCP) 후 | 도구 이름, 소요 시간(ms), 성공 여부 |

### Guard 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordGuardRejection(stage, reason)` | Guard 파이프라인이 요청을 거부할 때 | 단계 이름 (예: "InjectionDetection"), 거부 사유 |
| `recordGuardRejection(stage, reason, metadata)` | 위와 동일, 요청 메타데이터 포함 | 단계, 사유, 메타데이터 맵 |

### 복원력 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordCacheHit(cacheKey)` | 캐시된 응답이 반환될 때 (기본 메서드) | SHA-256 캐시 키 |
| `recordExactCacheHit(cacheKey)` | 바이트 동일 요청 키가 캐시 응답과 일치할 때 | SHA-256 캐시 키 |
| `recordSemanticCacheHit(cacheKey)` | 의미적으로 유사한 프롬프트가 캐시 응답과 일치할 때 | SHA-256 캐시 키 |
| `recordCacheMiss(cacheKey)` | 캐시된 응답이 없을 때 | SHA-256 캐시 키 |
| `recordCircuitBreakerStateChange(name, from, to)` | 서킷 브레이커 상태가 전이될 때 | CB 이름, 이전 상태, 새 상태 |
| `recordFallbackAttempt(model, success)` | 폴백 모델이 시도될 때 | 모델 이름, 성공 여부 |

`recordExactCacheHit`와 `recordSemanticCacheHit`는 기본적으로 `recordCacheHit`에 위임하므로, `recordCacheHit`만 오버라이드한 기존 구현은 변경 없이 계속 동작합니다.

### 토큰 사용량 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordTokenUsage(usage)` | 각 LLM 호출 후 | `TokenUsage(promptTokens, completionTokens, totalTokens)` |
| `recordTokenUsage(usage, metadata)` | 위와 동일, 요청 메타데이터 포함 | TokenUsage, 메타데이터 맵 |

### 출력 Guard 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordOutputGuardAction(stage, action, reason)` | 출력 Guard가 응답을 처리할 때 | 단계, 동작 ("allowed"/"modified"/"rejected"), 사유 |
| `recordOutputGuardAction(stage, action, reason, metadata)` | 위와 동일, 요청 메타데이터 포함 | 단계, 동작, 사유, 메타데이터 맵 |

### 경계 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordBoundaryViolation(violation, policy, limit, actual)` | 출력 경계 정책이 트리거될 때 | 위반 유형 (예: "output_too_long"), 정책 동작 (예: "truncate"), 한계값, 실제값 |
| `recordBoundaryViolation(violation, policy, limit, actual, metadata)` | 위와 동일, 요청 메타데이터 포함 | 위반, 정책, 한계값, 실제값, 메타데이터 맵 |

### 응답 관측 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordUnverifiedResponse(metadata)` | 응답이 승인된 소스에서 검증되지 못했을 때 | 메타데이터 맵 (채널, 쿼리 정보) |
| `recordResponseObservation(metadata)` | 제품 가치 인사이트를 위한 최종 응답 후 | 메타데이터 맵 (answerMode, grounded, deliveryMode, channel, toolFamily) |

### 지연 시간 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordStageLatency(stage, durationMs, metadata)` | 각 파이프라인 단계 완료 후 | 단계 이름, 소요 시간(ms), 메타데이터 맵 |
| `recordLlmLatency(model, durationMs)` | SLA 추적(P50/P95/P99)을 위한 각 LLM 호출 후 | 모델 이름, 소요 시간(ms) |

### 도구 결과 캐시 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordToolResultCacheHit(toolName, cacheKey)` | 캐시된 도구 결과가 재사용될 때 (동일 도구 + 동일 인자) | 도구 이름, 캐시 키 |
| `recordToolResultCacheMiss(toolName, cacheKey)` | 도구가 실행되고 결과가 캐시에 저장될 때 | 도구 이름, 캐시 키 |

### 도구 출력 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordToolOutputSize(toolName, sizeBytes, truncated)` | 출력 크기 모니터링을 위한 도구 실행 후 | 도구 이름, 출력 크기(바이트), 잘림 여부 |

### 활성 요청 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordActiveRequests(count)` | 진행 중인 에이전트 요청 수가 변경될 때 | 현재 활성 수 |

### RAG 메트릭

| 메서드 | 호출 시점 | 파라미터 |
|--------|---------|---------|
| `recordRagRetrieval(status, durationMs)` | RAG 검색 시도 후 | 상태 ("success", "empty", "timeout", "error"), 소요 시간(ms) |

## 구현

### Micrometer 커스텀 메트릭

```kotlin
@Bean
fun agentMetrics(registry: MeterRegistry): AgentMetrics = MicrometerAgentMetrics(registry)

class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {

    private val executionCounter = registry.counter("arc.agent.executions")
    private val executionTimer = registry.timer("arc.agent.execution.duration")
    private val errorCounter = registry.counter("arc.agent.errors")
    private val toolCounter = registry.counter("arc.agent.tool.calls")
    private val guardRejectionCounter = registry.counter("arc.agent.guard.rejections")
    private val cacheHitCounter = registry.counter("arc.agent.cache.hits")
    private val cacheMissCounter = registry.counter("arc.agent.cache.misses")
    private val fallbackCounter = registry.counter("arc.agent.fallback.attempts")
    private val tokenTotal = AtomicLong(0)

    init {
        registry.gauge("arc.agent.tokens.total", tokenTotal)
    }

    override fun recordExecution(result: AgentResult) {
        executionCounter.increment()
        executionTimer.record(result.durationMs, TimeUnit.MILLISECONDS)
        if (!result.success) errorCounter.increment()
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        registry.counter("arc.agent.tool.calls", "tool", toolName).increment()
        registry.timer("arc.agent.tool.duration", "tool", toolName)
            .record(durationMs, TimeUnit.MILLISECONDS)
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        registry.counter("arc.agent.guard.rejections", "stage", stage).increment()
    }

    override fun recordCacheHit(cacheKey: String) { cacheHitCounter.increment() }
    override fun recordCacheMiss(cacheKey: String) { cacheMissCounter.increment() }

    override fun recordCircuitBreakerStateChange(
        name: String, from: CircuitBreakerState, to: CircuitBreakerState
    ) {
        registry.counter("arc.agent.cb.transitions", "from", from.name, "to", to.name).increment()
    }

    override fun recordFallbackAttempt(model: String, success: Boolean) {
        registry.counter("arc.agent.fallback", "model", model, "success", success.toString()).increment()
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        tokenTotal.addAndGet(usage.totalTokens.toLong())
        registry.counter("arc.agent.tokens.prompt").increment(usage.promptTokens.toDouble())
        registry.counter("arc.agent.tokens.completion").increment(usage.completionTokens.toDouble())
    }

    override fun recordStreamingExecution(result: AgentResult) {
        registry.counter("arc.agent.streaming.executions").increment()
        if (!result.success) errorCounter.increment()
    }
}
```

### 간단한 로깅 메트릭

```kotlin
@Component
class LoggingAgentMetrics : AgentMetrics {
    private val logger = KotlinLogging.logger {}

    override fun recordExecution(result: AgentResult) {
        logger.info { "Execution: success=${result.success}, duration=${result.durationMs}ms" }
    }

    override fun recordToolCall(toolName: String, durationMs: Long, success: Boolean) {
        logger.info { "ToolCall: tool=$toolName, duration=${durationMs}ms, success=$success" }
    }

    override fun recordGuardRejection(stage: String, reason: String) {
        logger.warn { "GuardRejection: stage=$stage, reason=$reason" }
    }

    override fun recordCacheHit(cacheKey: String) {
        logger.info { "CacheHit: key=${cacheKey.take(16)}..." }
    }

    override fun recordCacheMiss(cacheKey: String) {
        logger.info { "CacheMiss: key=${cacheKey.take(16)}..." }
    }

    override fun recordCircuitBreakerStateChange(
        name: String, from: CircuitBreakerState, to: CircuitBreakerState
    ) {
        logger.warn { "CircuitBreaker: name=$name, $from -> $to" }
    }

    override fun recordFallbackAttempt(model: String, success: Boolean) {
        logger.info { "FallbackAttempt: model=$model, success=$success" }
    }

    override fun recordTokenUsage(usage: TokenUsage) {
        logger.info { "TokenUsage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=${usage.totalTokens}" }
    }

    override fun recordStreamingExecution(result: AgentResult) {
        logger.info { "StreamingExecution: success=${result.success}, duration=${result.durationMs}ms" }
    }
}
```

## 빈 등록

자동 구성은 다음 순서로 메트릭 빈을 등록합니다:

1. **`MicrometerAgentMetrics`** -- `@Primary`와 `@ConditionalOnBean(MeterRegistry::class)`로 등록됩니다. Micrometer가 클래스패스에 있고 `MeterRegistry` 빈이 존재하면 이 구현이 자동으로 사용됩니다.
2. **`NoOpAgentMetrics`** -- `@ConditionalOnMissingBean`으로 등록됩니다. `MeterRegistry`가 없을 때 폴백으로 사용됩니다.

완전한 커스텀 구현을 제공하려면 자신만의 `AgentMetrics` 빈을 정의하세요:

```kotlin
@Configuration
class MetricsConfig {
    @Bean
    fun agentMetrics(registry: MeterRegistry): AgentMetrics =
        MyCustomAgentMetrics(registry)
}
```

## 하위 호환성

2.6.0+ 에서 추가된 모든 메서드는 인터페이스에 **기본 빈 구현**을 갖습니다. 기존 커스텀 `AgentMetrics` 구현은 변경 없이 계속 동작합니다. 원래 3개 메서드(`recordExecution`, `recordToolCall`, `recordGuardRejection`)만 추상 메서드입니다.

metadata 오버로드(2.7.0 추가)는 기본적으로 metadata 없는 메서드로 위임하므로, 기존 구현에서 별도 오버라이드가 필요 없습니다.

## 내장 Micrometer 메트릭 이름

`MicrometerAgentMetrics`가 활성화되면 다음 메트릭 이름이 등록됩니다:

| 메트릭 이름 | 타입 | 태그 | 설명 |
|------------|------|------|------|
| `arc.agent.executions` | Counter | `success`, `error_code` | 에이전트 실행 횟수 |
| `arc.agent.execution.duration` | Timer | `success` | 에이전트 실행 시간 (P50/P95/P99) |
| `arc.agent.errors` | Counter | `error_code` | 에이전트 에러 횟수 |
| `arc.agent.tool.calls` | Counter | `tool`, `success` | 도구 호출 횟수 |
| `arc.agent.tool.duration` | Timer | `tool`, `success` | 도구 호출 시간 |
| `arc.agent.guard.rejections` | Counter | `stage` | Guard 거부 횟수 |
| `arc.agent.cache.hits` | Counter | `mode` (exact/semantic) | 응답 캐시 히트 |
| `arc.agent.cache.misses` | Counter | -- | 응답 캐시 미스 |
| `arc.agent.circuitbreaker.transitions` | Counter | `name`, `from`, `to` | 서킷 브레이커 상태 전이 |
| `arc.agent.fallback.attempts` | Counter | `model`, `success` | 모델 폴백 시도 |
| `arc.agent.tokens.total` | DistributionSummary | -- | LLM 호출당 토큰 사용량 |
| `arc.agent.streaming.executions` | Counter | `success` | 스트리밍 실행 횟수 |
| `arc.agent.output.guard.actions` | Counter | `stage`, `action` | 출력 Guard 동작 |
| `arc.agent.boundary.violations` | Counter | `violation`, `policy` | 경계 정책 위반 |
| `arc.agent.responses.unverified` | Counter | `channel` | 미검증 응답 횟수 |
| `arc.agent.stage.duration` | Timer | `stage`, `channel` | 단계별 지연 시간 |
| `arc.agent.llm.latency` | Timer | `model` | LLM 호출 지연 (P50/P95/P99) |
| `arc.agent.tool.output.size` | DistributionSummary | `tool` | 도구 출력 크기 (바이트) |
| `arc.agent.tool.output.truncated` | Counter | `tool` | 잘린 도구 출력 횟수 |
| `arc.agent.tool.result.cache.hits` | Counter | `tool` | 도구 결과 캐시 히트 |
| `arc.agent.tool.result.cache.misses` | Counter | `tool` | 도구 결과 캐시 미스 |
| `arc.agent.rag.retrievals` | Counter | `status` | RAG 검색 횟수 |
| `arc.agent.rag.retrieval.duration` | Timer | `status` | RAG 검색 시간 |
| `arc.agent.active_requests` | Gauge | -- | 현재 진행 중인 에이전트 요청 |

## arc-admin 메트릭 파이프라인

`arc-admin` 모듈은 `NoOpAgentMetrics`를 대체하는 프로덕션 수준의 메트릭 수집 파이프라인을 제공하며, 테넌트 범위 관측성을 지원합니다.

### 구성 요소

| 구성 요소 | 역할 |
|-----------|------|
| `MetricCollectorAgentMetrics` | `@Primary` `AgentMetrics` 구현. Guard/토큰 이벤트를 링 버퍼에 게시. `CostCalculator`로 `estimatedCostUsd` 계산. |
| `MetricCollectionHook` | `AfterAgentCompleteHook` + `AfterToolCallHook` (order=200). 지연 분석, sessionId, userId 등 풍부한 실행/도구 이벤트 캡처. |
| `HitlEventHook` | `AfterToolCallHook` (order=201). `ToolCallOrchestrator` 메타데이터에서 HITL 승인/거부 이벤트 캡처. |
| `QuotaEnforcerHook` | `BeforeAgentStartHook` (order=5). 테넌트 쿼터 적용, 거부/경고 시 `QuotaEvent` 게시. |
| `MetricRingBuffer` | Lock-free 링 버퍼 (Disruptor 기반, 기본 8192 슬롯). 프로듀서는 블로킹 없음. 버퍼 가득 시 드롭 + 카운터. |
| `MetricWriter` | 링 버퍼를 주기적으로 드레인 → `MetricEventStore`를 통한 배치 JDBC 플러시. 안전망: cost가 ZERO인 `TokenUsageEvent` 재계산. |

### 데이터 흐름

```
에이전트 스레드                         백그라운드 라이터
    |                                       |
    +-- QuotaEnforcerHook (order=5) ---|    |
    +-- MetricCollectorAgentMetrics ---|    |
    +-- MetricCollectionHook (200) ----|    |
    +-- HitlEventHook (201) ----------|    |
                                      v    |
                              MetricRingBuffer
                                      |    |
                                      +----+
                                      v
                              MetricWriter (스케줄)
                                      |
                                      v
                              MetricEventStore (JDBC)
```

### 테넌트 전파

테넌트 ID는 metadata 오버로드를 통해 전달되는 `metadata["tenantId"]`에서 해석되며, ThreadLocal을 사용하지 **않습니다**. 이를 통해 비동기/코루틴 컨텍스트에서 정확한 테넌트 어트리뷰션을 보장합니다.

#### 멀티에이전트 메타데이터 전파

멀티에이전트 오케스트레이션에서 테넌트 메타데이터는 부모 command에서 서브 에이전트로 전파됩니다:

| 오케스트레이터 | 전파 방식 | 상태 |
|-------------|----------|------|
| `SupervisorOrchestrator` | `command.copy()` → supervisor agent | 전파됨 |
| `WorkerAgentTool` | `parentCommand` 파라미터 → worker `AgentCommand` | 전파됨 |
| `SequentialOrchestrator` | `command.copy()` → 각 노드 | 전파됨 |
| `ParallelOrchestrator` | `command.copy()` → 각 병렬 노드 | 전파됨 |

`WorkerAgentTool`은 생성 시 부모 `AgentCommand`를 받아(`SupervisorOrchestrator` 경유) 각 worker의 command에 `metadata`와 `userId`를 복사합니다. 이를 통해 `tenantId`, `sessionId`, `channel`이 서브 에이전트 실행 파이프라인의 모든 Hook에 전달됩니다.

### 이벤트 타입

모든 이벤트는 `sealed class MetricEvent`를 상속하며 TimescaleDB hypertable에 저장됩니다.

| 이벤트 | 테이블 | 소스 | 설명 |
|--------|--------|------|------|
| `AgentExecutionEvent` | `metric_agent_executions` | `MetricCollectionHook` | 실행 수준 메트릭: 성공, 지연 분석, guard/tool/LLM 소요 시간 |
| `ToolCallEvent` | `metric_tool_calls` | `MetricCollectionHook` | 도구별 호출: 이름, 소스(local/MCP), 소요 시간, 오류 |
| `TokenUsageEvent` | `metric_token_usage` | `MetricCollectorAgentMetrics` | LLM 호출별: 모델, 프로바이더, 토큰, `estimatedCostUsd` |
| `SessionEvent` | `metric_sessions` | `MetricCollectionHook` | 세션 집계: 턴 수, 총 소요 시간/토큰/비용 |
| `GuardEvent` | `metric_guard_events` | `MetricCollectorAgentMetrics` | Guard 거부: 단계, 카테고리, 사유, 출력 Guard 플래그 |
| `McpHealthEvent` | `metric_mcp_health` | `MetricCollectionHook` | MCP 서버 상태: 응답 시간, 상태, 오류 추적 |
| `EvalResultEvent` | `metric_eval_results` | 외부 수집 | 평가 실행: 통과/실패, 점수, 지연, 어설션 타입 |
| `QuotaEvent` | `metric_quota_events` | `QuotaEnforcerHook` | 쿼터 거부/경고: 액션, 사용량, 한도, 퍼센트 |
| `HitlEvent` | `metric_hitl_events` | `HitlEventHook` | HITL 결정: 도구, 승인 여부, 대기 시간, 거부 사유 |

### 토큰 비용 계산

`MetricCollectorAgentMetrics`는 `CostCalculator`를 사용하여 핫 패스에서 `estimatedCostUsd`를 계산합니다:

```kotlin
val cost = costCalculator.calculate(
    provider = provider, model = model, time = Instant.now(),
    promptTokens = usage.promptTokens, completionTokens = usage.completionTokens
)
```

- 모델 가격 정보 존재 → 이벤트에 0이 아닌 비용 설정
- 모델 불명 또는 예외 발생 → `BigDecimal.ZERO` (안전 폴백)
- `MetricWriter.enrichCosts()`는 cost가 ZERO일 때만 재시도 (핫 패스 외 안전망)

### 쿼터 이벤트

`QuotaEnforcerHook`는 3개 거부 지점과 1개 경고 임계값에서 `QuotaEvent`를 게시합니다:

| 액션 | 트리거 | 계속 진행? |
|------|--------|-----------|
| `rejected_requests` | `usage.requests >= quota.maxRequestsPerMonth` | 아니오 (요청 거부) |
| `rejected_tokens` | `usage.tokens >= quota.maxTokensPerMonth` | 아니오 (요청 거부) |
| `rejected_suspended` | `tenant.status != ACTIVE` | 아니오 (요청 거부) |
| `warning` | `usage.requests >= 90%` 쿼터 | 예 (요청 계속) |

90% 경고는 알림 노이즈 방지를 위해 `ConcurrentHashMap.newKeySet()`로 **테넌트당 월 1회 중복 제거**됩니다.

### HITL 이벤트

`HitlEventHook`는 HITL(Human-in-the-Loop) 승인 과정에서 `ToolCallOrchestrator`가 설정한 메타데이터를 읽습니다:

| 메타데이터 키 | 타입 | 설명 |
|--------------|------|------|
| `hitlWaitMs_{toolName}_{callIndex}` | Long | 사람의 승인을 기다린 시간 (ms) |
| `hitlApproved_{toolName}_{callIndex}` | Boolean | 사람이 도구 호출을 승인했는지 여부 |
| `hitlRejectionReason_{toolName}_{callIndex}` | String? | 거부 사유 (거부된 경우) |

`hitlWaitMs_{toolName}_{callIndex}`가 없으면 레거시 키(`hitlWaitMs_{toolName}` 등)로
폴백합니다. 둘 다 없으면 훅은 조용히 건너뜁니다 (HITL이 관여하지 않은 경우).

### 데이터베이스 스키마

테이블은 Flyway 마이그레이션 `V8__create_quota_and_hitl_tables.sql`에 정의됩니다:

- `metric_quota_events` — TimescaleDB hypertable, 7일 청크, 7일 압축, 90일 보존
- `metric_hitl_events` — TimescaleDB hypertable, 7일 청크, 7일 압축, 90일 보존

## 파이프라인 내 메트릭 포인트

```
요청
  |
  +--> recordActiveRequests(+1)
  v
QuotaEnforcerHook ----[거부]----> QuotaEvent
  |                 --[90% 사용]---> QuotaEvent (경고, 중복 제거)
  v
Guard 파이프라인 ----[거부]----> recordGuardRejection()
  |
  +--> recordStageLatency("guard", ...)
  v
캐시 확인 ----[히트]----> recordExactCacheHit() 또는 recordSemanticCacheHit() --> 캐시된 응답 반환
  |  [미스] --> recordCacheMiss()
  v
RAG 검색 -----> recordRagRetrieval(status, durationMs)
  |
  v
LLM 호출 -----> recordTokenUsage()
  |         --> recordLlmLatency(model, durationMs)
  |
  +--> [도구 호출] --> recordToolCall() (도구당)
  |                --> recordToolOutputSize() (도구당)
  |                --> recordToolResultCacheHit() 또는 recordToolResultCacheMiss()
  |                --> HitlEventHook --> HitlEvent (HITL 메타데이터가 있는 경우)
  |
  +--> [서킷 브레이커 상태 전이] --> recordCircuitBreakerStateChange()
  |
  +--> [폴백 발생] --> recordFallbackAttempt() (모델당)
  |
  v
출력 Guard ----[동작]----> recordOutputGuardAction()
  |
  +--> [경계 위반] --> recordBoundaryViolation()
  |
  v
응답
  |
  +--> recordExecution() (비스트리밍)
  +--> recordStreamingExecution() (스트리밍)
  +--> recordResponseObservation() (제품 가치 인사이트)
  +--> recordUnverifiedResponse() (미검증 시)
  +--> recordActiveRequests(-1)
```

# 관측성 & 메트릭

> **핵심 파일:** `agent/metrics/AgentMetrics.kt`
> 이 문서는 에이전트 메트릭 인터페이스, 사용 가능한 메트릭 포인트, 모니터링 백엔드 통합 방법을 다룹니다.

## 개요

Arc Reactor는 프레임워크에 독립적인 `AgentMetrics` 인터페이스를 제공합니다. 기본적으로 `NoOpAgentMetrics` 구현이 사용됩니다(메트릭 비활성화). Micrometer, Prometheus, Datadog 등 원하는 메트릭 백엔드로 커스텀 구현을 제공할 수 있습니다.

## AgentMetrics 인터페이스

7개 카테고리에 걸쳐 14개 메트릭 기록 메서드를 제공합니다:

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
| `recordCacheHit(cacheKey)` | 캐시된 응답이 반환될 때 | SHA-256 캐시 키 |
| `recordCacheMiss(cacheKey)` | 캐시된 응답이 없을 때 | SHA-256 캐시 키 |
| `recordCircuitBreakerStateChange(name, from, to)` | 서킷 브레이커 상태가 전이될 때 | CB 이름, 이전 상태, 새 상태 |
| `recordFallbackAttempt(model, success)` | 폴백 모델이 시도될 때 | 모델 이름, 성공 여부 |

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

## 구현

### Micrometer 커스텀 메트릭

```kotlin
@Bean
fun agentMetrics(registry: MeterRegistry): AgentMetrics = MicrometerAgentMetrics(registry)

class MicrometerAgentMetrics(private val registry: MeterRegistry) : AgentMetrics {

    private val executionCounter = registry.counter("arc.agent.executions")
    private val executionTimer = registry.timer("arc.agent.execution.duration")
    private val errorCounter = registry.counter("arc.agent.errors")
    private val cacheHitCounter = registry.counter("arc.agent.cache.hits")
    private val cacheMissCounter = registry.counter("arc.agent.cache.misses")
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

`NoOpAgentMetrics`는 `@ConditionalOnMissingBean`으로 등록됩니다. 사용자 정의 `AgentMetrics` 빈을 정의하면 자동으로 오버라이드됩니다:

```kotlin
@Configuration
class MetricsConfig {
    @Bean
    fun agentMetrics(registry: MeterRegistry): AgentMetrics =
        MicrometerAgentMetrics(registry)
}
```

## 하위 호환성

2.6.0+ 에서 추가된 모든 메서드는 인터페이스에 **기본 빈 구현**을 갖습니다. 기존 커스텀 `AgentMetrics` 구현은 변경 없이 계속 동작합니다. 원래 3개 메서드(`recordExecution`, `recordToolCall`, `recordGuardRejection`)만 추상 메서드입니다.

metadata 오버로드(2.7.0 추가)는 기본적으로 metadata 없는 메서드로 위임하므로, 기존 구현에서 별도 오버라이드가 필요 없습니다.

## arc-admin 메트릭 파이프라인

`arc-admin` 모듈은 `NoOpAgentMetrics`를 대체하는 프로덕션 수준의 메트릭 수집 파이프라인을 제공하며, 테넌트 범위 관측성을 지원합니다.

### 구성 요소

| 구성 요소 | 역할 |
|-----------|------|
| `MetricCollectorAgentMetrics` | `@Primary` `AgentMetrics` 구현. Guard/토큰 이벤트를 링 버퍼에 게시. |
| `MetricCollectionHook` | `AfterAgentCompleteHook` + `AfterToolCallHook`. 지연 분석, sessionId, userId 등 풍부한 실행/도구 이벤트 캡처. |
| `MetricRingBuffer` | Lock-free 링 버퍼 (Disruptor 기반, 기본 8192 슬롯). 프로듀서는 블로킹 없음. 버퍼 가득 시 드롭 + 카운터. |
| `MetricWriter` | 링 버퍼를 주기적으로 드레인 → `MetricEventStore`를 통한 배치 JDBC 플러시. |

### 데이터 흐름

```
에이전트 스레드                         백그라운드 라이터
    |                                       |
    +-- MetricCollectorAgentMetrics --|      |
    +-- MetricCollectionHook --------|      |
                                     v      |
                              MetricRingBuffer
                                     |      |
                                     +------+
                                     v
                              MetricWriter (스케줄)
                                     |
                                     v
                              MetricEventStore (JDBC)
```

### 테넌트 전파

테넌트 ID는 metadata 오버로드를 통해 전달되는 `metadata["tenantId"]`에서 해석되며, ThreadLocal을 사용하지 **않습니다**. 이를 통해 비동기/코루틴 컨텍스트에서 정확한 테넌트 어트리뷰션을 보장합니다.

## 파이프라인 내 메트릭 포인트

```
요청
  |
  v
Guard 파이프라인 ----[거부]----> recordGuardRejection()
  |
  v
캐시 확인 ----[히트]----> recordCacheHit() --> 캐시된 응답 반환
  |  [미스] --> recordCacheMiss()
  v
LLM 호출 -----> recordTokenUsage()
  |
  +--> [도구 호출] --> recordToolCall() (도구당)
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
```

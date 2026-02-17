# 관측성 & 메트릭

> **핵심 파일:** `agent/metrics/AgentMetrics.kt`
> 이 문서는 에이전트 메트릭 인터페이스, 사용 가능한 메트릭 포인트, 모니터링 백엔드 통합 방법을 다룹니다.

## 개요

Arc Reactor는 프레임워크에 독립적인 `AgentMetrics` 인터페이스를 제공합니다. 기본적으로 `NoOpAgentMetrics` 구현이 사용됩니다(메트릭 비활성화). Micrometer, Prometheus, Datadog 등 원하는 메트릭 백엔드로 커스텀 구현을 제공할 수 있습니다.

## AgentMetrics 인터페이스

5개 카테고리에 걸쳐 9개 메트릭 기록 메서드를 제공합니다:

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
응답
  |
  +--> recordExecution() (비스트리밍)
  +--> recordStreamingExecution() (스트리밍)
```

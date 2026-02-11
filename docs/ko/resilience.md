# 서킷 브레이커

## 개요

서킷 브레이커는 연속적인 에러를 추적하여 실패율이 임계값을 초과하면 호출을 즉시 차단합니다. LLM 제공자와 애플리케이션 모두를 반복적인 실패 요청으로부터 보호합니다.

Arc Reactor는 **Kotlin 네이티브** 서킷 브레이커를 사용합니다 (Resilience4j 등 외부 의존성 없음).

**기본값은 비활성화** — 설정으로 활성화합니다.

---

## 상태 머신

```
CLOSED ──(실패 >= 임계값)──> OPEN
OPEN ──(resetTimeout 경과)──> HALF_OPEN
HALF_OPEN ──(성공)──> CLOSED
HALF_OPEN ──(실패)──> OPEN
```

| 상태 | 동작 |
|------|------|
| **CLOSED** | 정상 운영. 연속 실패 횟수를 카운트. 성공 시 리셋. |
| **OPEN** | 모든 호출 즉시 거부 (`CircuitBreakerOpenException`). |
| **HALF_OPEN** | 제한된 수의 시도 호출 허용. 성공 → CLOSED, 실패 → OPEN. |

---

## 설정

```yaml
arc:
  reactor:
    circuit-breaker:
      enabled: true              # 서킷 브레이커 활성화 (기본값: false)
      failure-threshold: 5       # 회로 개방까지 연속 실패 횟수 (기본값: 5)
      reset-timeout-ms: 30000    # OPEN → HALF_OPEN 전환 대기 시간 (기본값: 30000)
      half-open-max-calls: 1     # HALF_OPEN에서 허용되는 시도 호출 수 (기본값: 1)
```

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | `false` | 마스터 스위치 (opt-in) |
| `failure-threshold` | `5` | 회로를 개방하는 연속 실패 횟수 |
| `reset-timeout-ms` | `30000` | 시도 호출 허용까지 대기 시간 |
| `half-open-max-calls` | `1` | HALF_OPEN에서 허용되는 시도 호출 수 |

---

## 동작 방식

### LLM 호출 보호

활성화 시 서킷 브레이커가 `SpringAiAgentExecutor`의 `callWithRetry`를 래핑합니다:

1. **CB 확인**이 먼저 실행 — OPEN이면 즉시 거부
2. **재시도 로직**이 CB 내부에서 실행 — 모든 재시도가 하나의 CB 실행으로 카운트
3. 재시도가 모두 소진되어 호출이 실패하면 CB에 실패 1회 기록

```
요청 → 서킷 브레이커 → [재시도 + 백오프 → LLM 호출] → 응답
            │
            └─ OPEN? → CircuitBreakerOpenException → CIRCUIT_BREAKER_OPEN 에러
```

### 에러 코드

회로가 열려 있으면 에이전트가 반환하는 값:
- `errorCode`: `CIRCUIT_BREAKER_OPEN`
- `errorMessage`: "Service temporarily unavailable due to repeated failures. Please try again later."

`ErrorMessageResolver`로 커스텀 가능합니다.

---

## 아키텍처

### CircuitBreaker 인터페이스

```kotlin
interface CircuitBreaker {
    suspend fun <T> execute(block: suspend () -> T): T
    fun state(): CircuitBreakerState
    fun reset()
    fun metrics(): CircuitBreakerMetrics
}
```

### CircuitBreakerRegistry

이름별 서킷 브레이커를 `CircuitBreakerRegistry`로 관리합니다:

```kotlin
val registry = CircuitBreakerRegistry(failureThreshold = 5)
val llmBreaker = registry.get("llm")          // 지연 생성
val mcpBreaker = registry.get("mcp:weather")   // 이름별 격리
```

각 이름은 독립적인 서킷 브레이커를 갖습니다. Executor는 기본적으로 `"llm"`을 사용합니다.

### 핵심 설계

- **스레드 안전**: `AtomicReference`, `AtomicInteger`, `AtomicLong` 사용 (잠금 없음)
- **CancellationException 안전**: 실패로 카운트하지 않음 (구조적 동시성 보존)
- **테스트 가능한 시계**: `clock` 함수를 주입받아 결정적 시간 기반 테스트 가능
- **외부 의존성 없음**: 순수 Kotlin 구현

---

## 커스텀 서킷 브레이커

자체 Bean으로 기본값을 교체할 수 있습니다:

```kotlin
@Bean
fun circuitBreakerRegistry(): CircuitBreakerRegistry {
    return CircuitBreakerRegistry(
        failureThreshold = 10,
        resetTimeoutMs = 60_000,
        halfOpenMaxCalls = 3
    )
}
```

또는 `CircuitBreaker` 인터페이스를 직접 구현하여 `SpringAiAgentExecutor`에 전달할 수 있습니다.

---

## 메트릭

모니터링을 위한 서킷 브레이커 메트릭 접근:

```kotlin
val metrics = circuitBreakerRegistry.get("llm").metrics()
// CircuitBreakerMetrics(failureCount=3, successCount=42, state=CLOSED, lastFailureTime=1707...)
```

| 메트릭 | 설명 |
|--------|------|
| `failureCount` | 현재 연속 실패 횟수 |
| `successCount` | 총 성공 횟수 |
| `state` | 현재 상태 (CLOSED/OPEN/HALF_OPEN) |
| `lastFailureTime` | 마지막 실패 시간 (에포크 ms, 없으면 null) |

---

## 재시도와의 상호작용

서킷 브레이커와 재시도는 함께 동작합니다:

```
서킷 브레이커
  └─ 재시도 (지수 백오프)
       └─ LLM 호출
```

- **재시도**: 단일 요청 내 일시적 에러 처리 (예: 일시적 503)
- **서킷 브레이커**: 여러 요청에 걸친 지속적 장애 처리 (예: 제공자 장애)
- 모든 재시도가 실패하면 서킷 브레이커에 실패 **1회** 기록
- 재시도 중 성공하면 서킷 브레이커에 성공 **1회** 기록

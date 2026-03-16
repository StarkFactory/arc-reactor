# 추적 (Tracing)

## 개요

Arc Reactor는 OpenTelemetry에 대한 하드 의존성 없이 에이전트 실행을 계측하는 경량 추적 추상화(`ArcReactorTracer`)를 제공합니다. OTel이 존재하면 설정된 exporter(Jaeger, Zipkin, OTLP 등)로 span이 전송됩니다. OTel이 없으면 오버헤드 없는 no-op tracer가 사용됩니다.

```
arc.agent.request                          ← 최상위 span (전체 실행)
  ├── arc.agent.guard                      ← Guard 파이프라인 검사
  ├── arc.agent.llm.call [0]              ← 첫 번째 LLM 호출
  ├── arc.agent.tool.call [calc, 0]       ← 병렬 도구 호출
  ├── arc.agent.tool.call [search, 1]
  ├── arc.agent.llm.call [1]              ← 두 번째 LLM 호출 (도구 호출 이후)
  └── ...
```

**기본값은 활성화** — OpenTelemetry가 클래스패스에 없으면 no-op tracer의 비용이 0이므로 안전합니다.

---

## 아키텍처

### ArcReactorTracer 인터페이스

핵심 추상화는 하나의 메서드를 가진 단일 인터페이스입니다:

```kotlin
interface ArcReactorTracer {

    fun startSpan(name: String, attributes: Map<String, String> = emptyMap()): SpanHandle

    interface SpanHandle : AutoCloseable {
        fun setError(e: Throwable)
        fun setAttribute(key: String, value: String)
        override fun close()   // span 종료. 멱등.
    }
}
```

핵심 설계:

- **API에 OTel 타입 없음** — 인터페이스는 `String`과 `Map`만 사용하므로 OTel 없이도 컴파일 가능
- **`SpanHandle`은 `AutoCloseable`** — Kotlin `use {}` 블록으로 span 종료를 보장
- **스레드 안전** — 구현체는 여러 코루틴에서 호출해도 안전해야 함
- **멱등 close** — `close()`를 여러 번 호출해도 안전 (첫 번째 이후 no-op)

### NoOpArcReactorTracer

OpenTelemetry가 없거나 추적이 비활성화되었을 때 사용됩니다:

```kotlin
class NoOpArcReactorTracer : ArcReactorTracer {
    override fun startSpan(name: String, attributes: Map<String, String>): SpanHandle =
        NoOpSpanHandle   // 싱글턴 객체 — 할당 없음

    private object NoOpSpanHandle : ArcReactorTracer.SpanHandle {
        override fun setError(e: Throwable) = Unit
        override fun setAttribute(key: String, value: String) = Unit
        override fun close() = Unit
    }
}
```

- 모든 호출에 동일한 싱글턴 `NoOpSpanHandle`을 반환 — span당 할당 0
- 모든 메서드가 빈 구현 — 오버헤드 0

### OtelArcReactorTracer

OpenTelemetry 기반 구현체:

```kotlin
class OtelArcReactorTracer(private val tracer: Tracer) : ArcReactorTracer {

    override fun startSpan(name: String, attributes: Map<String, String>): SpanHandle {
        val builder = tracer.spanBuilder(name).setParent(Context.current())
        for ((key, value) in attributes) {
            builder.setAttribute(key, value)
        }
        val span = builder.startSpan()
        return OtelSpanHandle(span)
    }
}
```

주요 동작:

- 각 span은 **현재 OTel context의 자식**입니다 (`Context.current()`)
- 속성은 span 생성 시 첨부됩니다
- `setError()`는 `StatusCode.ERROR`를 설정하고 `recordException()`으로 예외를 기록합니다
- `close()`는 `span.end()`를 호출하여 span을 완료합니다

---

## 설정

```yaml
arc:
  reactor:
    tracing:
      enabled: true                # span 방출 활성화 (기본값: true)
      service-name: arc-reactor    # span에 첨부되는 service.name (기본값: "arc-reactor")
      include-user-id: false       # span에 user.id 속성 포함 (기본값: false — PII 보호)
```

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | `true` | 마스터 스위치. `false`이면 OTel 존재 여부와 관계없이 `NoOpArcReactorTracer`가 사용됩니다. |
| `service-name` | `"arc-reactor"` | OTel `Tracer` 인스턴스를 얻을 때 사용하는 `service.name`. |
| `include-user-id` | `false` | span 속성에 `user.id`를 포함할지 여부. 트레이스에서의 PII 유출 방지를 위해 기본 비활성화. |

---

## 자동 설정

Bean 해석 순서 (`@ConditionalOnMissingBean`에 의해 첫 번째 매칭이 우선):

1. **`arcReactorOtelTracer`** — 다음 조건이 모두 충족될 때 생성:
   - `arc.reactor.tracing.enabled=true` (기본값)
   - `io.opentelemetry.api.OpenTelemetry` 클래스가 클래스패스에 존재
   - Spring context에 `OpenTelemetry` Bean이 존재
   - 사용자 제공 `ArcReactorTracer` Bean이 없음

2. **`noOpTracer`** — OTel tracer가 등록되지 않았을 때의 폴백

사용자는 자체 `ArcReactorTracer` `@Bean`을 제공하여 어느 Bean이든 교체할 수 있습니다. `@ConditionalOnMissingBean` 어노테이션이 커스텀 Bean의 우선권을 보장합니다.

---

## Span 참조

프레임워크는 에이전트 실행 중 4가지 span 유형을 방출합니다:

### `arc.agent.request`

전체 에이전트 실행을 포괄하는 최상위 span.

| 속성 | 설명 |
|------|------|
| `session.id` | command metadata의 세션 ID (없으면 빈 문자열) |
| `agent.mode` | 에이전트 실행 모드 (예: `chat`, `streaming`) |
| `user.id` | 사용자 ID (`include-user-id=true`일 때만; null이면 `"anonymous"` 사용) |
| `error.code` | 실패 시 에러 코드 (예: `RATE_LIMITED`, `TIMEOUT`) |
| `error.message` | 실패 시 에러 메시지 (500자로 잘림) |

### `arc.agent.guard`

Guard 파이프라인 검사(입력 검증, 속도 제한, 주입 감지)를 포괄합니다.

| 속성 | 설명 |
|------|------|
| `guard.result` | `"passed"` 또는 `"rejected"` |

### `arc.agent.llm.call`

ReAct 루프 내 LLM 호출당 하나의 span.

| 속성 | 설명 |
|------|------|
| `llm.call.index` | 이번 실행에서의 LLM 호출 0 기반 인덱스 |

### `arc.agent.tool.call`

도구 호출당 하나의 span (병렬 도구 호출은 병렬 span을 생성).

| 속성 | 설명 |
|------|------|
| `tool.name` | 호출된 도구의 이름 |
| `tool.call.index` | 전체 실행에서 이 도구 호출의 글로벌 인덱스 |

---

## Spring Boot에서 OpenTelemetry 활성화

### 1단계: 의존성 추가

빌드에 OpenTelemetry Spring Boot 스타터를 추가합니다:

```kotlin
// build.gradle.kts
implementation("io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.12.0")
```

이것은 OTel API, SDK 및 Spring Boot 자동 설정을 가져옵니다.

### 2단계: Exporter 설정

```yaml
# application.yml
otel:
  exporter:
    otlp:
      endpoint: http://localhost:4317   # OTLP gRPC 엔드포인트 (Jaeger, Tempo 등)
  resource:
    attributes:
      service.name: my-agent-service

arc:
  reactor:
    tracing:
      enabled: true
      service-name: my-agent-service
```

### 3단계: 확인

애플리케이션을 시작하고 에이전트 요청을 보냅니다. 추적 백엔드에서 span을 확인할 수 있습니다:

```
my-agent-service: arc.agent.request (2340ms)
  ├── arc.agent.guard (12ms)
  ├── arc.agent.llm.call (1800ms)
  ├── arc.agent.tool.call [calculator] (200ms)
  └── arc.agent.llm.call (320ms)
```

---

## Jaeger 통합

```yaml
otel:
  exporter:
    otlp:
      endpoint: http://jaeger:4317
```

`http://localhost:16686`에서 Jaeger UI에 접근합니다. 서비스 이름 `arc-reactor` (또는 커스텀 `service-name`)으로 필터링합니다.

## Zipkin 통합

```yaml
otel:
  exporter:
    zipkin:
      endpoint: http://zipkin:9411/api/v2/spans
```

`http://localhost:9411`에서 Zipkin UI에 접근합니다.

## Spring Boot Actuator 통합

`spring-boot-starter-actuator`와 OTel이 모두 존재하면 트레이스가 자동으로 내보내집니다. `arc.reactor.tracing.enabled=true` (기본값)를 확인하는 것 외에 추가 Arc Reactor 설정은 필요 없습니다.

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 모든 요청 샘플링 (프로덕션에서는 줄이세요)
```

---

## 커스텀 Tracer 구현

자체 Bean으로 기본값을 교체할 수 있습니다:

```kotlin
@Bean
fun arcReactorTracer(): ArcReactorTracer {
    return object : ArcReactorTracer {
        override fun startSpan(
            name: String,
            attributes: Map<String, String>
        ): ArcReactorTracer.SpanHandle {
            logger.info { "Span started: $name, attrs=$attributes" }
            return object : ArcReactorTracer.SpanHandle {
                override fun setError(e: Throwable) {
                    logger.error(e) { "Span error: $name" }
                }
                override fun setAttribute(key: String, value: String) {
                    logger.debug { "Span attr: $key=$value" }
                }
                override fun close() {
                    logger.info { "Span ended: $name" }
                }
            }
        }
    }
}
```

`@ConditionalOnMissingBean`이 사용되므로 커스텀 Bean이 우선합니다.

---

## 주의 사항

| 주의 사항 | 설명 |
|-----------|------|
| **OTel API는 있지만 Bean 없음** | OTel API jar가 있지만 `OpenTelemetry` Bean이 등록되지 않으면 (예: 자동 설정 누락) `NoOpArcReactorTracer`가 조용히 사용됩니다. 로그에서 `"ArcReactorTracer: using NoOp"` 메시지를 확인하세요. |
| **트레이스의 PII** | `include-user-id`는 기본값이 `false`입니다. 활성화하면 사용자 ID가 추적 백엔드로 전송됩니다. 트레이스 저장소가 개인정보 보호 요구사항을 준수하는지 확인하세요. |
| **닫히지 않는 span** | 항상 `use {}` 블록이나 `try/finally`로 span을 닫으세요. 닫히지 않은 span은 메모리 누수와 불완전한 트레이스를 유발합니다. 프레임워크 내부에서는 이미 이렇게 처리하고 있습니다. |
| **span에서의 CancellationException** | Executor는 `CancellationException`을 올바르게 처리합니다 — span에 에러를 설정하고 다시 던집니다. 커스텀 tracer 구현체는 `CancellationException`을 삼키지 않아야 합니다. |
| **고카디널리티 속성** | 전체 프롬프트나 응답 본문 같은 고카디널리티 값을 span 속성으로 추가하지 마세요. `error.message` 잘림(500자)을 가이드로 참고하세요. |
| **프로덕션에서의 샘플링** | 전체 샘플링(`1.0`)이면 모든 요청이 트레이스를 생성합니다. 트래픽이 많은 배포에서는 과도한 저장 비용을 피하기 위해 샘플링 확률을 줄이세요. |

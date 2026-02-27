# arc-admin

## 개요

arc-admin은 Arc Reactor의 운영 제어 플레인(control plane)입니다. 멀티 테넌트 프로덕션 환경에서 AI 에이전트를 운영할 때 비용, 신뢰성, 할당량 소비에 대한 가시성을 확보하는 문제를 해결합니다.

이 모듈이 제공하는 기능:

- **메트릭 수집** — 에이전트 실행, 도구 호출, 토큰 사용량, Guard 결정, 세션 정보를 lock-free 링 버퍼에 캡처하여 백그라운드 배치로 TimescaleDB에 플러시
- **비용 추적** — provider/model/시간 기준으로 모델 가격을 조회하여 모든 토큰 이벤트에 USD 비용 추정치를 부여
- **테넌트 관리** — 테넌트는 플랜(FREE/STARTER/BUSINESS/ENTERPRISE), 할당량, SLO 목표, 라이프사이클 상태(ACTIVE/SUSPENDED/DEACTIVATED)를 가짐
- **할당량 강제** — 월 한도 초과 시 요청을 거부하는 `BeforeAgentStartHook` (로컬 카운터 → Caffeine 캐시 → 서킷 브레이커로 보호되는 DB 3단계 방어)
- **알림(Alerting)** — 정적 임계값, 이상 탐지(σ 기반), 에러 버짓 소진율(burn rate) 기반 규칙 알림; 스케줄러가 규칙을 평가하고 알림 전송
- **SLO 추적** — 테넌트별 가용성 및 p99 레이턴시 목표와 에러 버짓 소진율 계산
- **분산 추적** — 모든 에이전트 실행과 도구 호출에 대한 OTel span, OTLP 또는 TimescaleDB로 내보내기 가능
- **관리자 REST API** — 플랫폼 전체 및 테넌트 범위의 대시보드, 할당량 조회, CSV 내보내기, 가격 관리, 알림 CRUD

## 활성화

**프로퍼티:**
```yaml
arc:
  reactor:
    admin:
      enabled: true
```

**Gradle 의존성:**
```kotlin
implementation("com.arc.reactor:arc-admin")
```

JDBC 계층을 위해 `DataSource`(TimescaleDB 확장이 포함된 PostgreSQL)가 필요합니다. `DataSource` 빈이 존재할 때 메트릭 저장소, 테넌트 저장소, 가격 저장소, 알림 저장소, 쿼리 서비스, 대시보드 서비스가 자동으로 활성화됩니다. `DataSource` 없이는 인메모리 저장소와 링 버퍼 수집 파이프라인만 활성화됩니다.

## 주요 컴포넌트

| 클래스 | 역할 |
|---|---|
| `AdminAutoConfiguration` | 기본 빈: 테넌트 저장소, 가격 저장소, 비용 계산기, 메트릭 링 버퍼, Hook |
| `AdminJdbcConfiguration` | JDBC 계층: 전체 저장소, MetricWriter, 쿼리/SLO/알림/대시보드 서비스, 할당량 Hook |
| `TracingAutoConfiguration` | OTel SDK 설정, Micrometer 추적 브릿지, OTLP/TimescaleDB 내보내기 연결 |
| `MetricRingBuffer` | 메트릭 이벤트를 위한 lock-free Disruptor 방식 링 버퍼 (단일 소비자 drain) |
| `MetricCollectionHook` | `AfterAgentCompleteHook` + `AfterToolCallHook` (order=200), 모든 메트릭 이벤트 발행 |
| `MetricWriter` | 링 버퍼를 드레인하고 배치를 `MetricEventStore`에 기록하는 백그라운드 스레드 |
| `MetricCollectorAgentMetrics` | `@Primary AgentMetrics` 구현체; LLM 토큰/비용 데이터 캡처 |
| `QuotaEnforcerHook` | `BeforeAgentStartHook` (order=5); fail-open 방식의 3단계 할당량 검사 |
| `AlertEvaluator` | 알림 규칙 평가: STATIC_THRESHOLD, BASELINE_ANOMALY, ERROR_BUDGET_BURN_RATE |
| `AlertScheduler` | 고정 주기로 `AlertEvaluator.evaluateAll()`을 실행하고 알림 전송 |
| `AgentTracingHooks` | `gen_ai.agent.execute` 및 `gen_ai.tool.execute` OTel span을 생성하는 4-type Hook (order=199) |
| `CostCalculator` | 모델 가격 조회 및 토큰 수로 USD 비용 계산 (5분 캐시) |
| `TenantService` | 테넌트 라이프사이클: 생성, 정지, 활성화 |
| `DashboardService` | 집계 대시보드 쿼리: overview, usage, quality, tools, cost |
| `SloService` | 가용성 및 레이턴시 SLO 상태와 에러 버짓 소진율 |
| `PlatformAdminController` | `GET/POST /api/admin/platform/*` — 테넌트 CRUD, 가격, 알림, 플랫폼 헬스 |
| `TenantAdminController` | `GET /api/admin/tenant/*` — 테넌트 대시보드, SLO, 할당량, CSV 내보내기 |
| `McpMetricReporter` | MCP 서버가 자체 도구 호출 메트릭을 전송하는 경량 HTTP 리포터 |

## 설정

모든 프로퍼티는 `arc.reactor.admin` 접두사를 사용합니다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모듈 활성화 |
| `timescale-enabled` | `true` | TimescaleDB 사용 여부 표시 (예약됨) |
| `tracing.enabled` | `true` | 에이전트/도구 span 생성 활성화 |
| `tracing.timescale-export` | `true` | `TimescaleSpanExporter`를 통해 TimescaleDB로 span 내보내기 |
| `tracing.otlp.enabled` | `false` | OTLP span 내보내기 활성화 |
| `tracing.otlp.endpoint` | `""` | OTLP 엔드포인트 URL |
| `tracing.otlp.protocol` | `"http/protobuf"` | OTLP 프로토콜 |
| `tracing.otlp.headers` | `{}` | 추가 OTLP 헤더 (예: 인증) |
| `collection.ring-buffer-size` | `8192` | 링 버퍼 용량 (2의 제곱수로 올림) |
| `collection.flush-interval-ms` | `1000` | `MetricWriter`가 버퍼를 드레인하는 주기 (ms) |
| `collection.batch-size` | `1000` | DB 쓰기 배치당 최대 이벤트 수 |
| `collection.writer-threads` | `1` | 기록 스레드 수 (drain이 단일 소비자이므로 1 유지 필수) |
| `retention.raw-days` | `90` | TimescaleDB 원시 메트릭 보존 기간 |
| `retention.hourly-days` | `365` | 시간별 집계 보존 기간 |
| `retention.daily-days` | `1825` | 일별 집계 보존 기간 (5년) |
| `retention.audit-years` | `7` | 감사 로그 보존 기간 |
| `retention.compression-after-days` | `7` | N일 이후 TimescaleDB 청크 압축 |
| `slo.default-availability` | `0.995` | 기본 SLO 가용성 목표 (99.5%) |
| `slo.default-latency-p99-ms` | `10000` | 기본 SLO p99 레이턴시 목표 |
| `scaling.instance-id` | 호스트명 | OTel span에 삽입되는 인스턴스 식별자 |
| `scaling.mode` | `DIRECT_WRITE` | `DIRECT_WRITE` 또는 `KAFKA` (Kafka 모드는 예약됨) |

## 연동

arc-admin은 표준 Hook 및 메트릭 인터페이스를 통해 코어 프레임워크와 연동됩니다.

**자동으로 등록되는 Hook:**

- `MetricCollectionHook` (order=200) — `AfterAgentCompleteHook`과 `AfterToolCallHook`을 구현하여 `AgentExecutionEvent`, `ToolCallEvent`, `GuardEvent`, `SessionEvent`, `McpHealthEvent`를 링 버퍼에 발행
- `HitlEventHook` — 도구 호출이 Human-in-the-Loop 승인 대기 시 `HitlEvent`를 발행
- `QuotaEnforcerHook` (order=5) — `BeforeAgentStartHook`을 구현하며, `DataSource`와 `CircuitBreakerRegistry` 빈이 모두 존재할 때만 활성화
- `AgentTracingHooks` (order=199) — `Tracer` 빈이 있을 때만 활성화

**메트릭 인터페이스:**

`MetricCollectorAgentMetrics`는 `@Primary AgentMetrics`로 등록됩니다. 코어 실행기는 LLM 단계마다 `AgentMetrics`를 호출하여 토큰 수와 비용을 기록합니다. 토큰 데이터는 실행기에서 링 버퍼의 `TokenUsageEvent`로 흐릅니다.

**테넌트 해석:**

HTTP 요청은 `TenantWebFilter`를 통과하며, `TenantResolver`가 요청 헤더(예: `X-Tenant-ID`)에서 `tenantId`를 추출합니다. 해석된 ID는 WebFlux exchange 속성에 저장되고 `MetricCollectionHook`이 `context.metadata["tenantId"]`로 읽어갑니다.

## 코드 예시

**최소 프로덕션 설정:**

```yaml
arc:
  reactor:
    admin:
      enabled: true
      collection:
        ring-buffer-size: 16384
        flush-interval-ms: 500
      retention:
        raw-days: 30
      tracing:
        enabled: true
        otlp:
          enabled: true
          endpoint: "http://otel-collector:4318"

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arc_reactor
```

**REST API로 테넌트 생성:**

```bash
curl -X POST http://localhost:8080/api/admin/platform/tenants \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp", "slug": "acme", "plan": "BUSINESS"}'
```

**에러율에 대한 정적 임계값 알림 규칙 생성:**

```bash
curl -X POST http://localhost:8080/api/admin/platform/alerts/rules \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High error rate",
    "type": "STATIC_THRESHOLD",
    "metric": "error_rate",
    "threshold": 0.05,
    "windowMinutes": 15,
    "severity": "HIGH",
    "tenantId": "acme-id"
  }'
```

**MCP 서버에서 arc-admin으로 메트릭 보고:**

```kotlin
val reporter = McpMetricReporter(
    endpoint = "http://arc-reactor:8080/api/admin/metrics/ingest",
    tenantId = "tenant-abc",
    serverName = "my-mcp-server"
)
reporter.start()

// 도구 핸들러 내에서:
reporter.reportToolCall("my_tool", durationMs = 120, success = true, runId = runId)

// 애플리케이션 종료 시:
reporter.stop()
```

## 주의사항

**링 버퍼는 단일 소비자 전용입니다.** `MetricRingBuffer.drain()`은 동시 호출에 안전하지 않습니다. `collection.writer-threads=1`(기본값)을 유지하세요. 값을 늘리면 중복 읽기와 데이터 손실이 발생합니다.

**QuotaEnforcerHook은 fail-open입니다.** 서킷 브레이커가 열려 있거나 DB에 접근할 수 없는 경우, 경고 로그를 남기고 요청을 통과시킵니다. DB 장애가 전체 에이전트 트래픽을 차단하지 않도록 의도된 동작입니다.

**전체 기능을 위해 TimescaleDB가 필요합니다.** `DataSource` 없이는 메트릭 저장, 대시보드, SLO, 알림 평가를 사용할 수 없습니다. 링 버퍼와 Hook은 여전히 등록되지만 이벤트는 기록 단계에서 버려집니다.

**관리자 컨트롤러는 DataSource가 필요합니다.** `PlatformAdminController`와 `TenantAdminController`는 모두 `@ConditionalOnBean(DataSource::class)`으로 선언되어 있어 인메모리 전용 모드에서는 활성화되지 않습니다.

**테넌트 ID "default"는 항상 허용됩니다.** `QuotaEnforcerHook`은 `tenantId == "default"`일 때 강제를 건너뜁니다. 프로덕션 트래픽에서는 `TenantResolver`가 실제 테넌트 ID를 반환하도록 해야 합니다.

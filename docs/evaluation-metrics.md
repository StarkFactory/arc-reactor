# Arc Reactor Evaluation Metrics — 관측 가이드

R222~R255 `EvaluationMetricsCollector`가 기록하는 9개 핵심 메트릭과 `execution.error`의 9개 stage 자동 기록 배선, 활성화, Prometheus 쿼리, Grafana 대시보드, 운영 플레이북 가이드.

## 목차

1. [활성화](#활성화)
2. [메트릭 카탈로그](#메트릭-카탈로그)
3. [Prometheus 쿼리 예시](#prometheus-쿼리-예시)
4. [Grafana 대시보드 템플릿](#grafana-대시보드-템플릿)
5. [execution.error Stage 운영 플레이북 (R245~R255)](#executionerror-stage-운영-플레이북-r245r255)
6. [알림 규칙 예시](#알림-규칙-예시)
7. [참조](#참조)

## 활성화

`application.yml`에 다음 속성을 추가하면 모든 9개 메트릭이 자동으로 수집된다:

```yaml
arc:
  reactor:
    evaluation:
      metrics:
        enabled: true
```

### 동작 방식

- `MeterRegistry` 빈이 존재하면 `MicrometerEvaluationMetricsCollector`가 자동 주입됨
- `EvaluationMetricsHook`(AfterAgentCompleteHook)이 등록되어 각 작업 완료 시 메트릭 기록
- R223 `ToolResponseSummarizer`가 활성화되어 있으면 R224 `tool.response.kind` 메트릭과 R242 `tool.response.compression` 메트릭도 함께 수집됨

### 의존성 요구사항

- Micrometer (`io.micrometer:micrometer-core`) — Spring Boot Actuator에 포함
- Prometheus 백엔드 — `io.micrometer:micrometer-registry-prometheus`
- Grafana (선택) — 대시보드 시각화

## 메트릭 카탈로그

R222 6개 + R224 1개 + R242 1개 + R245 1개 = **총 9개 메트릭**. 타입화된 카탈로그는 `com.arc.reactor.agent.metrics.EvaluationMetricsCatalog`에서 프로그래밍 방식으로 조회 가능하다.

| # | 메트릭 이름 | 유형 | 태그 | 단위 | Round |
|---|------------|------|------|------|-------|
| 1 | `arc.reactor.eval.task.completed` | Counter | `result`, `error_code` | — | R222 |
| 2 | `arc.reactor.eval.task.duration` | Timer | `result` | ms | R222 |
| 3 | `arc.reactor.eval.tool.calls` | DistributionSummary | — | calls | R222 |
| 4 | `arc.reactor.eval.token.cost.usd` | Counter | `model` | usd | R222 |
| 5 | `arc.reactor.eval.human.override` | Counter | `outcome`, `tool` | — | R222 |
| 6 | `arc.reactor.eval.safety.rejection` | Counter | `stage`, `reason` | — | R222 |
| 7 | `arc.reactor.eval.tool.response.kind` | Counter | `kind`, `tool` | — | R224 |
| 8 | `arc.reactor.eval.tool.response.compression` | DistributionSummary | `tool` | percent | R242 |
| 9 | `arc.reactor.eval.execution.error` | Counter | `stage`, `exception` | — | R245 |

### 태그 값 사전

| 태그 | 값 |
|------|-----|
| `result` | `success`, `failure` |
| `error_code` | `none`, `RATE_LIMITED`, `TIMEOUT`, `GUARD_REJECTED`, `OUTPUT_GUARD_REJECTED`, `HOOK_REJECTED`, `UNKNOWN` 등 |
| `model` | `gemini-2.5-flash`, `claude-opus-4`, `unknown` 등 |
| `outcome` | `approved`, `rejected`, `timeout`, `auto` |
| `tool` | 호출 도구 이름 (예: `jira_get_issue`, `bitbucket_list_prs`) |
| `stage` | `guard`, `output_guard`, `hook`, `tool_policy`, `other` |
| `reason` | 거부 사유 분류 (`injection`, `pii`, `unauthorized`, `rate limited` 등) |
| `kind` | `empty`, `error_cause_first`, `list_top_n`, `structured`, `text_head_tail`, `text_full` |

## Prometheus 쿼리 예시

Micrometer가 메트릭 이름의 `.`을 `_`로 변환한다는 점을 주의. 예: `arc.reactor.eval.task.completed` → Prometheus에서는 `arc_reactor_eval_task_completed_total`.

### Task success rate (성공률)

```promql
sum(rate(arc_reactor_eval_task_completed_total{result="success"}[5m]))
/
sum(rate(arc_reactor_eval_task_completed_total[5m]))
```

### Task 실패 사유 분포 (top 5)

```promql
topk(5,
  sum by (error_code) (
    rate(arc_reactor_eval_task_completed_total{result="failure"}[5m])
  )
)
```

### p95 지연 (latency)

```promql
histogram_quantile(0.95,
  sum by (le) (
    rate(arc_reactor_eval_task_duration_seconds_bucket{result="success"}[5m])
  )
)
```

### 평균 도구 호출 수

```promql
rate(arc_reactor_eval_tool_calls_sum[5m])
/
rate(arc_reactor_eval_tool_calls_count[5m])
```

### 모델별 시간당 비용

```promql
sum by (model) (
  rate(arc_reactor_eval_token_cost_usd_total[1h]) * 3600
)
```

### HITL 거부율

```promql
sum(rate(arc_reactor_eval_human_override_total{outcome="rejected"}[5m]))
/
sum(rate(arc_reactor_eval_human_override_total[5m]))
```

### HITL 타임아웃 비율 (시스템 개선 신호)

```promql
sum(rate(arc_reactor_eval_human_override_total{outcome="timeout"}[5m]))
/
sum(rate(arc_reactor_eval_human_override_total[5m]))
```

### 도구별 에러 응답 비율 (R224 시너지)

```promql
sum by (tool) (
  rate(arc_reactor_eval_tool_response_kind_total{kind="error_cause_first"}[5m])
)
/
sum by (tool) (
  rate(arc_reactor_eval_tool_response_kind_total[5m])
)
```

### 빈 응답이 가장 많은 도구 top 5

```promql
topk(5,
  sum by (tool) (
    rate(arc_reactor_eval_tool_response_kind_total{kind="empty"}[5m])
  )
)
```

### Guard injection 거부 추이

```promql
rate(arc_reactor_eval_safety_rejection_total{stage="guard",reason="injection"}[5m])
```

### 도구별 평균 압축률 (R242 시너지)

```promql
sum by (tool) (rate(arc_reactor_eval_tool_response_compression_sum[5m]))
/
sum by (tool) (rate(arc_reactor_eval_tool_response_compression_count[5m]))
```

### 압축률이 낮은 도구 top 5 (요약 효율 점검)

```promql
bottomk(5,
  sum by (tool) (
    rate(arc_reactor_eval_tool_response_compression_sum[5m])
  )
  /
  sum by (tool) (
    rate(arc_reactor_eval_tool_response_compression_count[5m])
  )
)
```

낮은 값이 나오는 도구는 요약 휴리스틱 재검토가 필요하다. 예를 들어 구조화 필드가 많은데 `STRUCTURED` 전략이 주요 필드만 잘 추출하지 못하면 압축률이 낮게 측정된다.

### 압축률 p95 분포

```promql
histogram_quantile(0.95,
  sum by (le) (rate(arc_reactor_eval_tool_response_compression_bucket[5m]))
)
```

p95가 30% 미만이면 ACI 요약 계층의 효과가 미미하다고 볼 수 있다.

### stage별 실행 에러 발생률 (R245)

```promql
sum by (stage) (rate(arc_reactor_eval_execution_error_total[5m]))
```

각 실행 단계별 예외 발생 빈도. `tool_call` 급증은 MCP 서버 장애, `llm_call` 급증은 LLM API 장애 신호.

### 예외 클래스별 top 5 (운영 우선순위 판단)

```promql
topk(5,
  sum by (exception) (rate(arc_reactor_eval_execution_error_total[5m]))
)
```

가장 많이 발생하는 예외 타입을 상위 5개로 추려 우선 수정 대상 선정.

### 특정 stage + exception 조합 상승 감지

```promql
rate(arc_reactor_eval_execution_error_total{stage="tool_call",exception="SocketTimeoutException"}[5m])
```

Alertmanager 규칙 예시:
```yaml
- alert: ToolCallTimeoutSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="tool_call",exception="SocketTimeoutException"}[5m]) > 0.1
  for: 5m
  annotations:
    summary: "도구 호출 타임아웃 급증 — MCP 서버 네트워크 확인"
```

## Grafana 대시보드 템플릿

아래 JSON은 Grafana dashboard import 형식이다. 파일로 저장하여 import하거나, Grafana UI에서 JSON 탭에 붙여넣으면 된다.

**R259 추가 패널 (9개 → 15개)**:
- `execution.error — 9 Stage Stack` — 9개 stage 스택 차트 (R245~R255 통합 관측)
- `execution.error — Fault-Tolerance Style Groups` — fail-open vs fail-close vs catch-all 그룹화
- `execution.error — Top 5 Exceptions per Stage` — stage × exception drill-down 테이블
- `Redis Infrastructure Outage Detector` — memory ∧ cache 동시 급증 확정 패널
- `Parsing Regression Monitor` — LLM 모델 regression 추적
- `OTHER Stage Top Exceptions` — 새 stage 후보 발굴 테이블

```json
{
  "title": "Arc Reactor Evaluation Metrics",
  "uid": "arc-reactor-eval",
  "timezone": "browser",
  "panels": [
    {
      "title": "Task Success Rate",
      "type": "stat",
      "targets": [
        {
          "expr": "sum(rate(arc_reactor_eval_task_completed_total{result=\"success\"}[5m])) / sum(rate(arc_reactor_eval_task_completed_total[5m]))",
          "legendFormat": "success rate"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "percentunit",
          "thresholds": {
            "steps": [
              { "color": "red", "value": null },
              { "color": "yellow", "value": 0.95 },
              { "color": "green", "value": 0.99 }
            ]
          }
        }
      }
    },
    {
      "title": "p50/p95/p99 Latency",
      "type": "timeseries",
      "targets": [
        {
          "expr": "histogram_quantile(0.50, sum by (le) (rate(arc_reactor_eval_task_duration_seconds_bucket[5m])))",
          "legendFormat": "p50"
        },
        {
          "expr": "histogram_quantile(0.95, sum by (le) (rate(arc_reactor_eval_task_duration_seconds_bucket[5m])))",
          "legendFormat": "p95"
        },
        {
          "expr": "histogram_quantile(0.99, sum by (le) (rate(arc_reactor_eval_task_duration_seconds_bucket[5m])))",
          "legendFormat": "p99"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "s" } }
    },
    {
      "title": "Average Tool Calls per Task",
      "type": "timeseries",
      "targets": [
        {
          "expr": "rate(arc_reactor_eval_tool_calls_sum[5m]) / rate(arc_reactor_eval_tool_calls_count[5m])",
          "legendFormat": "avg tool calls"
        }
      ]
    },
    {
      "title": "Token Cost by Model (hourly $)",
      "type": "timeseries",
      "targets": [
        {
          "expr": "sum by (model) (rate(arc_reactor_eval_token_cost_usd_total[1h]) * 3600)",
          "legendFormat": "{{model}}"
        }
      ],
      "fieldConfig": { "defaults": { "unit": "currencyUSD" } }
    },
    {
      "title": "Error Code Distribution",
      "type": "piechart",
      "targets": [
        {
          "expr": "sum by (error_code) (rate(arc_reactor_eval_task_completed_total{result=\"failure\"}[5m]))",
          "legendFormat": "{{error_code}}"
        }
      ]
    },
    {
      "title": "Human Override Outcomes",
      "type": "timeseries",
      "targets": [
        {
          "expr": "sum by (outcome) (rate(arc_reactor_eval_human_override_total[5m]))",
          "legendFormat": "{{outcome}}"
        }
      ]
    },
    {
      "title": "Safety Rejection by Stage",
      "type": "timeseries",
      "targets": [
        {
          "expr": "sum by (stage) (rate(arc_reactor_eval_safety_rejection_total[5m]))",
          "legendFormat": "{{stage}}"
        }
      ]
    },
    {
      "title": "Tool Response Kind Distribution (R224)",
      "type": "timeseries",
      "targets": [
        {
          "expr": "sum by (kind) (rate(arc_reactor_eval_tool_response_kind_total[5m]))",
          "legendFormat": "{{kind}}"
        }
      ]
    },
    {
      "title": "Top 5 Tools with Error Responses",
      "type": "table",
      "targets": [
        {
          "expr": "topk(5, sum by (tool) (rate(arc_reactor_eval_tool_response_kind_total{kind=\"error_cause_first\"}[5m])))",
          "legendFormat": "{{tool}}"
        }
      ]
    },
    {
      "title": "execution.error — 9 Stage Stack (R245~R255)",
      "type": "timeseries",
      "description": "R245~R255로 자동 기록되는 9개 stage의 예외 발생률. tool_call/llm_call/hook/output_guard/guard/memory/cache/parsing/other 스택 차트.",
      "targets": [
        {
          "expr": "sum by (stage) (rate(arc_reactor_eval_execution_error_total[5m]))",
          "legendFormat": "{{stage}}"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "drawStyle": "bars",
            "stacking": { "mode": "normal" }
          }
        }
      }
    },
    {
      "title": "execution.error — Fault-Tolerance Style Groups",
      "type": "timeseries",
      "description": "Fail-open(hook/memory/cache/parsing) vs Fail-close(guard/output_guard) vs Catch-all(other) 그룹화. R256 플레이북 참조.",
      "targets": [
        {
          "expr": "sum(rate(arc_reactor_eval_execution_error_total{stage=~\"hook|memory|cache|parsing\"}[5m]))",
          "legendFormat": "fail-open (swallow)"
        },
        {
          "expr": "sum(rate(arc_reactor_eval_execution_error_total{stage=~\"guard|output_guard\"}[5m]))",
          "legendFormat": "fail-close (reject)"
        },
        {
          "expr": "sum(rate(arc_reactor_eval_execution_error_total{stage=\"tool_call\"}[5m]))",
          "legendFormat": "tool_call (adapter)"
        },
        {
          "expr": "sum(rate(arc_reactor_eval_execution_error_total{stage=\"llm_call\"}[5m]))",
          "legendFormat": "llm_call (retry exhausted)"
        },
        {
          "expr": "sum(rate(arc_reactor_eval_execution_error_total{stage=\"other\"}[5m]))",
          "legendFormat": "other (catch-all)"
        }
      ]
    },
    {
      "title": "execution.error — Top 5 Exceptions per Stage (heatmap)",
      "type": "table",
      "description": "각 stage별 가장 빈발하는 예외 클래스 5개. 운영자 drill-down 기본 테이블.",
      "targets": [
        {
          "expr": "topk(5, sum by (stage, exception) (rate(arc_reactor_eval_execution_error_total[1h])))",
          "legendFormat": "{{stage}} / {{exception}}",
          "format": "table",
          "instant": true
        }
      ],
      "transformations": [
        {
          "id": "organize",
          "options": {
            "excludeByName": { "Time": true }
          }
        }
      ]
    },
    {
      "title": "Redis Infrastructure Outage Detector (memory ∧ cache)",
      "type": "stat",
      "description": "Memory와 Cache stage에서 동시에 Jedis/Connection 예외가 급증하면 Redis 인프라 전체 장애 확정. R256 운영 시나리오 #1.",
      "targets": [
        {
          "expr": "(rate(arc_reactor_eval_execution_error_total{stage=\"memory\",exception=~\".*Jedis.*|.*Connection.*|.*Redis.*\"}[5m]) > 0.05) * (rate(arc_reactor_eval_execution_error_total{stage=\"cache\",exception=~\".*Jedis.*|.*Connection.*|.*Redis.*\"}[5m]) > 0.05)",
          "legendFormat": "outage signal"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "mappings": [
            { "type": "value", "options": { "0": { "text": "정상", "color": "green" } } },
            { "type": "value", "options": { "1": { "text": "Redis 장애 의심", "color": "red" } } }
          ],
          "thresholds": {
            "steps": [
              { "color": "green", "value": null },
              { "color": "red", "value": 1 }
            ]
          }
        }
      }
    },
    {
      "title": "Parsing Regression Monitor (R254)",
      "type": "timeseries",
      "description": "LLM 모델 업그레이드 또는 프롬프트 변경 후 JSON 파싱 실패율 급증 감지. Jackson 계열 예외 추적.",
      "targets": [
        {
          "expr": "sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage=\"parsing\"}[5m]))",
          "legendFormat": "{{exception}}"
        }
      ],
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "thresholds": {
            "steps": [
              { "color": "green", "value": null },
              { "color": "yellow", "value": 0.1 },
              { "color": "red", "value": 0.5 }
            ]
          }
        }
      }
    },
    {
      "title": "OTHER Stage Top Exceptions — 새 Stage 후보 발굴 (R255)",
      "type": "table",
      "description": "stage=other에 반복 등장하는 예외 클래스. topk로 상위 5개를 추적하면 새 ExecutionStage enum 값 추가 후보를 발굴할 수 있다.",
      "targets": [
        {
          "expr": "topk(5, sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage=\"other\"}[24h])))",
          "legendFormat": "{{exception}}",
          "format": "table",
          "instant": true
        }
      ]
    }
  ]
}
```

### 커스터마이징 팁

- 특정 모델만 추적: `filter by (model=~"gemini.*")` 조건 추가
- 도구별 드릴다운: `variables` 섹션에 `$tool` 템플릿 변수 추가
- 환경 분리: `job` 또는 `instance` 태그로 production vs staging 구분

## execution.error Stage 운영 플레이북 (R245~R255)

R245에서 도입된 `arc.reactor.eval.execution.error` 메트릭은 9개 `ExecutionStage`로 분류된 런타임 예외를 추적한다. R246~R255의 11 라운드 투자로 **9/9 stage 자동 기록 100%**가 완성되어, 사용자가 `arc.reactor.evaluation.metrics.enabled=true` 한 줄만 설정하면 모든 실행 경로의 예외가 즉시 Prometheus에 노출된다.

이 섹션은 각 stage의 의미, 자주 등장하는 예외, 대응 팀, Prometheus 쿼리, Alertmanager 규칙을 통합 제공한다.

### 9개 Stage 개요

| Stage | 자동 기록 Round | Fault-tolerance 스타일 | 대응 팀 | 주요 관심사 |
|-------|-----------------|------------------------|---------|-------------|
| `tool_call` | R246/R247 | catch-and-fallback | SRE + MCP 팀 | MCP 서버 장애, 네트워크 타임아웃 |
| `llm_call` | R248 | retry + fail | ML 팀 + SRE | LLM API 장애, rate limit |
| `hook` | R249 | fail-open (swallow) | 사용자 확장 개발자 | 사용자 정의 Hook 버그 |
| `output_guard` | R250 | fail-close (Reject) | 보안 팀 | PII regex 버그, 동적 규칙 로딩 실패 |
| `guard` | R251 | fail-close (Reject) | 보안 팀 | Rate limit Redis 장애, injection 탐지 버그 |
| `memory` | R252 | hybrid (fail-open + fallback) | SRE | JDBC/Redis 장애, 세션 소유권 조회 실패 |
| `cache` | R253 | fail-open (swallow) | SRE | Redis/Caffeine 장애 |
| `parsing` | R254 | fail-open (empty map/list) | ML 팀 | LLM 모델 품질 regression |
| `other` | R255 | catch-all | 전체 온콜 | 분류 불가 잔여 예외 |

### Stage별 상세 플레이북

#### 1. `stage="tool_call"` — 도구 호출 실패

**기록 경로**: `ArcToolCallbackAdapter.call()` catch 블록 (R246). `TimeoutCancellationException`과 일반 `Exception` 모두 기록. `CancellationException`은 제외(재throw).

**자주 등장하는 예외**:
- `SocketTimeoutException` — MCP 서버 네트워크 지연
- `ConnectException` — MCP 서버 미응답
- `IllegalStateException` — tool 구현 버그
- `TimeoutCancellationException` — `fallbackToolTimeoutMs` 초과

**Prometheus 쿼리 — 도구별 타임아웃 트래킹**:
```promql
rate(arc_reactor_eval_execution_error_total{stage="tool_call",exception="TimeoutCancellationException"}[5m])
```

**Alertmanager 규칙**:
```yaml
- alert: ToolCallTimeoutSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="tool_call",exception=~"Timeout.*|SocketTimeout.*"}[5m]) > 0.1
  for: 5m
  labels:
    severity: warning
    team: sre
  annotations:
    summary: "도구 호출 타임아웃 급증"
    description: "MCP 서버 네트워크 지연 또는 장애 의심 — mcp 팀 확인"
```

**대응 매뉴얼**: MCP 서버 health check → 네트워크 latency 확인 → `fallbackToolTimeoutMs` 튜닝 검토.

---

#### 2. `stage="llm_call"` — LLM API 호출 실패

**기록 경로**: `RetryExecutor.execute()` catch 블록 (R248). **중간 재시도는 기록하지 않고** 최종 실패(비일시적 에러 즉시 throw 또는 재시도 소진)만 기록.

**자주 등장하는 예외**:
- `HttpClientErrorException$TooManyRequests` — Gemini/Anthropic rate limit
- `ResourceExhaustedException` — API quota 초과
- `HttpServerErrorException` — LLM API 5xx
- `TimeoutCancellationException` — 요청 타임아웃

**Prometheus 쿼리 — Rate limit 집계**:
```promql
sum(rate(arc_reactor_eval_execution_error_total{stage="llm_call",exception=~".*TooManyRequests.*|.*RateLimit.*|.*ResourceExhausted.*"}[5m]))
```

**Alertmanager 규칙**:
```yaml
- alert: LlmApiOutage
  expr: rate(arc_reactor_eval_execution_error_total{stage="llm_call"}[5m]) > 0.5
  for: 3m
  labels:
    severity: critical
    team: ml
  annotations:
    summary: "LLM API 장애 또는 quota 소진"
    description: "Gemini/Anthropic 콘솔 확인 + 백업 모델 전환 검토"
```

**대응 매뉴얼**: API 상태 페이지 확인 → quota 확인 → `ModelRouter`로 백업 모델 전환.

---

#### 3. `stage="hook"` — Hook 실행 실패 (fail-open)

**기록 경로**: `HookExecutor`의 3개 catch 블록 (R249) — Before/AfterToolCall/AfterAgentComplete.

**중요**: Hook은 `failOnError=false`(기본)일 때 예외를 swallowing하므로 task는 성공으로 끝난다. 이 메트릭 없이는 Hook 실패를 탐지할 방법이 없다.

**자주 등장하는 예외**:
- `NullPointerException` — 커스텀 Hook의 null 가정 실패
- `IllegalStateException` — Hook이 필요한 외부 서비스 미준비
- Webhook 관련 `ConnectException` — `WebhookNotificationHook` 대상 서버 미응답

**Prometheus 쿼리 — 가장 불안정한 Hook**:
```promql
topk(5, sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage="hook"}[1h])))
```

**Alertmanager 규칙**:
```yaml
- alert: HookFailureSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="hook"}[5m]) > 1.0
  for: 5m
  labels:
    severity: warning
    team: platform
  annotations:
    summary: "Hook 실패율 급증 — fail-open 공백 탐지"
    description: "커스텀 Hook 또는 EvaluationMetricsHook/WebhookNotificationHook 점검 필요"
```

**대응 매뉴얼**: 예외 클래스로 어느 Hook인지 특정 → Hook 구현 검토 또는 대상 외부 서비스 점검.

---

#### 4. `stage="output_guard"` — Output Guard Stage 실행 실패 (fail-close)

**기록 경로**: `OutputGuardPipeline` catch 블록 (R250). `Rejected(SYSTEM_ERROR)` 반환 전 기록.

**중요**: 정상 `Rejected`(PII 탐지 등 정책 동작)는 `safety.rejection` 메트릭에 기록된다. `execution.error`는 **stage 구현 자체의 버그**만 기록.

**자주 등장하는 예외**:
- `PatternSyntaxException` — regex 패턴 컴파일 실패 (커스텀 규칙)
- `NullPointerException` — PII 마스킹 로직 버그
- 동적 규칙 로딩 관련 `IOException`

**Prometheus 쿼리 — 정책 차단 vs 시스템 이상 분리**:
```promql
# 정책 차단 (정상 동작)
rate(arc_reactor_eval_safety_rejection_total{stage="output_guard"}[5m])

# 시스템 이상 (버그)
rate(arc_reactor_eval_execution_error_total{stage="output_guard"}[5m])
```

**Alertmanager 규칙**:
```yaml
- alert: OutputGuardImplementationError
  expr: rate(arc_reactor_eval_execution_error_total{stage="output_guard"}[5m]) > 0.1
  for: 5m
  labels:
    severity: warning
    team: security
  annotations:
    summary: "Output Guard 구현 버그 탐지"
    description: "PII 마스킹 regex 또는 동적 규칙 stage 점검 필요"
```

---

#### 5. `stage="guard"` — Input Guard Stage 실행 실패 (fail-close)

**기록 경로**: `GuardPipeline.handleStageError()` catch 블록 (R251). OutputGuard와 동일한 대칭 패턴.

**자주 등장하는 예외**:
- `JedisConnectionException` — Rate limit Redis 연결 장애
- `PatternSyntaxException` — Injection 탐지 regex 버그
- `IllegalStateException` — Unicode 정규화 stage 내부 오류

**Prometheus 쿼리 — Redis 장애 (GUARD ∪ MEMORY ∪ CACHE)**:
```promql
rate(arc_reactor_eval_execution_error_total{stage=~"guard|memory|cache",exception=~".*Jedis.*|.*Redis.*"}[5m])
```

**Alertmanager 규칙**:
```yaml
- alert: GuardRedisOutage
  expr: rate(arc_reactor_eval_execution_error_total{stage="guard",exception=~".*Jedis.*|.*Connection.*"}[5m]) > 0.3
  for: 3m
  labels:
    severity: critical
    team: sre
  annotations:
    summary: "Rate limit Redis 장애 — Guard 경로 차단 중"
    description: "Redis 복구까지 요청이 계속 SYSTEM_ERROR로 거부됨"
```

---

#### 6. `stage="memory"` — ConversationMemory 저장/조회 실패 (hybrid)

**기록 경로**: `DefaultConversationManager`의 5개 catch 지점 (R252) — `loadHistory`, `loadFromHistory`, `verifySessionOwnership`, `triggerAsyncSummarization`, `saveMessages`.

**중요**: `saveMessages` fail-open 특성 — 사용자는 정상 응답을 받지만 **대화 이력이 날아가는** 치명적 조용한 실패. 이 메트릭이 유일한 탐지 수단.

**자주 등장하는 예외**:
- `SQLException` — PostgreSQL 장애
- `JedisConnectionException` — Redis 메모리 장애
- `SerializationException` — 메시지 직렬화 실패
- `TimeoutException` — DB 쿼리 타임아웃

**Prometheus 쿼리 — 예외 클래스별 top 5**:
```promql
topk(5, sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage="memory"}[1h])))
```

**Alertmanager 규칙**:
```yaml
- alert: MemoryStorageErrorSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="memory"}[5m]) > 0.3
  for: 5m
  labels:
    severity: warning
    team: sre
  annotations:
    summary: "대화 이력 저장/조회 실패 급증"
    description: "사용자는 응답 받지만 이력이 날아가는 중 — JDBC/Redis 점검"
```

---

#### 7. `stage="cache"` — ResponseCache 저장/조회 실패 (fail-open)

**기록 경로**: `AgentExecutionCoordinator`의 2개 catch 블록 (R253) — `storeInCache`, `resolveCache`. 호출자 wrapping 전략으로 모든 캐시 구현체(`NoOp`/`Caffeine`/`RedisSemantic`) 호환.

**자주 등장하는 예외**:
- `JedisConnectionException` — `RedisSemanticResponseCache` Redis 연결 장애
- `SerializationException` — `CachedResponse` 직렬화 문제
- `OutOfMemoryError` — Caffeine 캐시 포화

**Prometheus 쿼리 — Memory vs Cache Redis 장애 상관 분석**:
```promql
# 두 stage가 동시에 급증 → Redis 인프라 장애 확정
rate(arc_reactor_eval_execution_error_total{stage="memory",exception=~".*Jedis.*"}[5m]) > 0.1
and
rate(arc_reactor_eval_execution_error_total{stage="cache",exception=~".*Jedis.*"}[5m]) > 0.1
```

**Alertmanager 규칙**:
```yaml
- alert: CacheStorageErrorSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="cache"}[5m]) > 0.5
  for: 3m
  labels:
    severity: warning
    team: sre
  annotations:
    summary: "응답 캐시 스토리지 실패 급증"
    description: "캐시 hit rate가 떨어지며 LLM 호출 비용이 증가하는 중"
```

---

#### 8. `stage="parsing"` — JSON 파싱 실패

**기록 경로**:
- `ToolArgumentParser.parseToolArguments()` (R254) — tool call JSON → Map
- `PlanExecuteStrategy.parsePlan()` (R254) — PLAN_EXECUTE JSON 배열 → List<PlanStep>

**중요**: 이 메트릭은 **프롬프트 엔지니어링 regression 탐지**의 핵심 축이다. LLM 모델 업그레이드 후 파싱 실패율이 급증하면 새 모델의 JSON 생성 품질 저하를 의심할 수 있다.

**자주 등장하는 예외**:
- `JsonParseException` — 기본적인 JSON 문법 오류
- `MismatchedInputException` — 예상과 다른 타입 (array vs object)
- `UnrecognizedPropertyException` — 예상치 못한 필드
- `InvalidDefinitionException` — Jackson 설정 문제

**Prometheus 쿼리 — 예외 클래스별 분포**:
```promql
topk(5, sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage="parsing"}[1h])))
```

**Alertmanager 규칙** (모델 배포 후 3시간 내):
```yaml
- alert: ParsingErrorSurgeAfterDeploy
  expr: rate(arc_reactor_eval_execution_error_total{stage="parsing"}[5m]) > 0.3
  for: 5m
  labels:
    severity: warning
    team: ml
  annotations:
    summary: "LLM JSON 파싱 실패 급증"
    description: "프롬프트/모델 변경 후 regression 의심 — tool call 및 plan 파싱 경로 점검"
```

---

#### 9. `stage="other"` — 분류 불가 잔여 예외 (catch-all)

**기록 경로**: `SpringAiAgentExecutor.execute()` 최상위 `catch (e: Exception)` 블록 (R255). 하위 8개 stage에 의해 이미 기록되지 않은 잔여 예외만 여기에 도달.

**목적**: 두 가지 신호 제공:
1. **새 stage 필요 신호**: 특정 예외 클래스가 `other`에 반복 등장 → 새 `ExecutionStage` enum 값 추가 고려
2. **분류 미흡 drill-down**: `topk` 쿼리로 가장 흔한 분류 미흡 예외 파악

**Prometheus 쿼리 — 새 stage 후보 발굴**:
```promql
topk(5, sum by (exception) (rate(arc_reactor_eval_execution_error_total{stage="other"}[24h])))
```

**Alertmanager 규칙**:
```yaml
- alert: UnclassifiedExceptionSurge
  expr: rate(arc_reactor_eval_execution_error_total{stage="other"}[15m]) > 0.5
  for: 10m
  labels:
    severity: warning
    team: platform
  annotations:
    summary: "분류 불가 예외 급증 — 새 stage 추가 검토 필요"
    description: "topk 쿼리로 반복되는 예외 클래스 확인 후 ExecutionStage enum 확장 고려"
```

---

### 9 Stage 통합 모니터링 쿼리

**전체 stage 실패 분포 (스택 차트)**:
```promql
sum by (stage) (rate(arc_reactor_eval_execution_error_total[5m]))
```

**Stage별 top 예외 heatmap**:
```promql
sum by (stage, exception) (rate(arc_reactor_eval_execution_error_total[5m]))
```

**Fault-tolerance 스타일별 그룹화**:
```promql
# Fail-open 계층 — 조용히 실패
sum(rate(arc_reactor_eval_execution_error_total{stage=~"hook|memory|cache|parsing"}[5m]))

# Fail-close 계층 — 명시적 거부
sum(rate(arc_reactor_eval_execution_error_total{stage=~"guard|output_guard"}[5m]))

# Catch-all
sum(rate(arc_reactor_eval_execution_error_total{stage="other"}[5m]))
```

### 대응 팀 라우팅 매트릭스

| 팀 | 담당 Stage | Alertmanager `team` 라벨 |
|----|-----------|--------------------------|
| SRE | `tool_call`(네트워크), `memory`, `cache`, `guard`(Redis) | `team: sre` |
| ML | `llm_call`, `parsing` | `team: ml` |
| 보안 | `guard`(정책), `output_guard` | `team: security` |
| Platform | `hook`, `other` | `team: platform` |
| MCP 팀 | `tool_call`(MCP 특정) | `team: mcp` |

### R255 이후 핵심 변화

- ✅ **9/9 stage 자동 기록** — 단일 메트릭 축으로 전체 실행 경로 커버
- ✅ **Fail-open 관측 공백 해소** — Hook/Memory/Cache의 조용한 실패 탐지 가능
- ✅ **정책 차단 vs 시스템 이상 분리** — `safety.rejection`과 `execution.error` 상호 보완
- ✅ **대응 팀 라우팅 기반 마련** — stage별 team 라벨로 자동 분배
- ✅ **프롬프트 regression 탐지** — `parsing` stage로 모델 배포 후 품질 모니터링

## 알림 규칙 예시

Prometheus alertmanager 규칙. `prometheus.yml`의 `rule_files` 참조로 추가한다.

```yaml
groups:
  - name: arc-reactor-eval
    interval: 30s
    rules:
      # 작업 성공률이 95% 미만으로 떨어지면 경고
      - alert: ArcReactorLowSuccessRate
        expr: |
          (
            sum(rate(arc_reactor_eval_task_completed_total{result="success"}[5m]))
            /
            sum(rate(arc_reactor_eval_task_completed_total[5m]))
          ) < 0.95
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Arc Reactor task success rate below 95%"
          description: "Current: {{ $value | humanizePercentage }}"

      # p95 지연이 15초 초과 시 경고
      - alert: ArcReactorHighP95Latency
        expr: |
          histogram_quantile(0.95,
            sum by (le) (rate(arc_reactor_eval_task_duration_seconds_bucket[5m]))
          ) > 15
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Arc Reactor p95 latency > 15s"

      # 시간당 비용이 예산을 초과하면 critical
      - alert: ArcReactorTokenCostBudgetExceeded
        expr: |
          sum(rate(arc_reactor_eval_token_cost_usd_total[1h]) * 3600) > 10
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "Arc Reactor hourly token cost > $10"
          description: "Current hourly rate: ${{ $value }}"

      # HITL 타임아웃이 10% 초과하면 운영 개입 필요
      - alert: ArcReactorHitlTimeoutHigh
        expr: |
          (
            sum(rate(arc_reactor_eval_human_override_total{outcome="timeout"}[15m]))
            /
            sum(rate(arc_reactor_eval_human_override_total[15m]))
          ) > 0.10
        for: 15m
        labels:
          severity: warning
        annotations:
          summary: "HITL timeout rate > 10% — reviewers may be overloaded"

      # Guard injection 거부율 급증
      - alert: ArcReactorInjectionSurge
        expr: |
          rate(arc_reactor_eval_safety_rejection_total{stage="guard",reason="injection"}[5m]) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Injection rejection rate > 0.5/sec — possible attack"
```

## 참조

- **R222** — `docs/production-readiness-report.md` Round 222 섹션: 6개 기본 메트릭 설계
- **R224** — Round 224 섹션: R223 SummaryKind 통합 (7번째 메트릭)
- **R233** — Round 233 섹션: 공통 `PiiPatterns.kt` 추출 (관련 보안 개선)
- **R234** — Round 234 섹션 (이 문서): 사용자 관측 가이드
- **R241** — Round 241 섹션: `ToolResponseSummary.compressionPercent()` 1급 시민화
- **R242** — Round 242 섹션: R241 압축률 1급 지표 → `tool.response.compression` DistributionSummary (8번째 메트릭)
- **R245** — Round 245 섹션: `execution.error` Counter + `ExecutionStage` enum 9개 (9번째 메트릭)
- **R246** — Round 246 섹션: `recordError(throwable)` ergonomic 확장 + `ArcToolCallbackAdapter` catch 연결
- **R247** — Round 247 섹션: 4개 adapter 생성 지점 자동 배선 (ObjectProvider 계층 전파)
- **R248** — Round 248 섹션: `RetryExecutor` LLM_CALL stage 자동 기록
- **R249** — Round 249 섹션: `HookExecutor` HOOK stage 자동 기록 (fail-open 공백 해소)
- **R250** — Round 250 섹션: `OutputGuardPipeline` OUTPUT_GUARD stage 자동 기록 (R250 마일스톤)
- **R251** — Round 251 섹션: `GuardPipeline` GUARD stage 자동 기록 (입출력 Guard 대칭 완성)
- **R252** — Round 252 섹션: `ConversationManager` MEMORY stage 자동 기록 (5개 catch 지점)
- **R253** — Round 253 섹션: `AgentExecutionCoordinator` CACHE stage 자동 기록 (호출자 wrapping)
- **R254** — Round 254 섹션: `ToolArgumentParser` + `PlanExecuteStrategy` PARSING stage 자동 기록
- **R255** — Round 255 섹션: `SpringAiAgentExecutor` OTHER stage 자동 기록 (🏆 9/9 100% 완성)
- **R256** — Round 256 섹션 (이 문서 확장): 9개 stage 운영 플레이북 + 대응 팀 라우팅 매트릭스
- **R259** — Round 259 섹션 (이 문서 확장): Grafana 대시보드 JSON에 6개 execution.error 패널 추가 (9→15 패널)
- **소스**:
  - `arc-core/src/main/kotlin/com/arc/reactor/agent/metrics/EvaluationMetricsCollector.kt`
  - `arc-core/src/main/kotlin/com/arc/reactor/agent/metrics/MicrometerEvaluationMetricsCollector.kt`
  - `arc-core/src/main/kotlin/com/arc/reactor/agent/metrics/EvaluationMetricsHook.kt`
  - `arc-core/src/main/kotlin/com/arc/reactor/agent/metrics/EvaluationMetricsCatalog.kt` (R234 신규)

## 다음 단계

1. `application.yml`에 `arc.reactor.evaluation.metrics.enabled=true` 추가
2. Prometheus가 Arc Reactor의 `/actuator/prometheus` 엔드포인트를 스크래핑하도록 설정
3. 위 Grafana JSON을 import하여 대시보드 생성
4. 알림 규칙을 `prometheus.yml`에 추가
5. 최초 운영 1주간 값을 관찰하여 임계치 튜닝

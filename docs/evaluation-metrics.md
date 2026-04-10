# Arc Reactor Evaluation Metrics — 관측 가이드

R222~R245 `EvaluationMetricsCollector`가 기록하는 9개 핵심 메트릭의 활성화, Prometheus 쿼리, Grafana 대시보드 가이드.

## 목차

1. [활성화](#활성화)
2. [메트릭 카탈로그](#메트릭-카탈로그)
3. [Prometheus 쿼리 예시](#prometheus-쿼리-예시)
4. [Grafana 대시보드 템플릿](#grafana-대시보드-템플릿)
5. [알림 규칙 예시](#알림-규칙-예시)
6. [참조](#참조)

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
    }
  ]
}
```

### 커스터마이징 팁

- 특정 모델만 추적: `filter by (model=~"gemini.*")` 조건 추가
- 도구별 드릴다운: `variables` 섹션에 `$tool` 템플릿 변수 추가
- 환경 분리: `job` 또는 `instance` 태그로 production vs staging 구분

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

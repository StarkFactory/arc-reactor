# Admin API Reference — 프론트엔드 개발 가이드

모든 API는 `Authorization: Bearer <token>` 헤더 필수. ADMIN 역할 이상만 접근 가능.

**필수 환경변수**: arc-admin 모듈의 API(Trace, ToolCall, TokenCost, Slack Activity, Eval, Usage, Latency, Conversation Analytics)는 `ARC_REACTOR_ADMIN_ENABLED=true` 필요. arc-web 모듈의 API(InputGuard, Retention, Models, RBAC, AgentSpec, AuditExport)는 항상 활성화.

---

## 1. Execution Trace (`/api/admin/traces`)

### GET `/api/admin/traces`
트레이스 목록 조회. Guard→LLM→Tool→OutputGuard 실행 흐름.

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `days` | int | 7 | 조회 기간 (일) |
| `limit` | int | 50 | 최대 건수 (1~200) |
| `status` | string | - | `error`이면 실패 트레이스만 |

**응답**: `[{ trace_id, time, total_duration_ms, span_count, success, run_id }]`

### GET `/api/admin/traces/{traceId}/spans`
특정 트레이스의 스팬 타임라인.

**응답**: `[{ span_id, parent_span_id, operation_name, service_name, duration_ms, success, error_class, attributes, time }]`

---

## 2. Tool Call 상세 (`/api/admin/tool-calls`)

### GET `/api/admin/tool-calls`
도구 호출 이력 조회.

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `runId` | string | - | 특정 세션의 도구 호출만 |
| `days` | int | 7 | 기간 (runId 없을 때) |
| `limit` | int | 100 | 최대 건수 (1~500) |

**응답**: `[{ run_id, tool_name, tool_source, mcp_server_name, success, duration_ms, error_class, error_message, time, call_index }]`

### GET `/api/admin/tool-calls/ranking`
도구별 사용 통계.

| 파라미터 | 타입 | 기본값 | 설명 |
|---------|------|--------|------|
| `days` | int | 30 | 기간 |

**응답**: `[{ tool_name, call_count, success_count, avg_duration_ms, p95_duration_ms }]`

---

## 3. 토큰/비용 분석 (`/api/admin/token-cost`)

### GET `/api/admin/token-cost/by-session?sessionId=xxx`
세션별 턴 단위 토큰/비용 내역.

**응답**: `[{ run_id, model, provider, step_type, prompt_tokens, completion_tokens, total_tokens, estimated_cost_usd, time }]`

### GET `/api/admin/token-cost/daily?days=30`
모델별 일별 토큰/비용 추이.

**응답**: `[{ day, model, prompt_tokens, completion_tokens, total_tokens, total_cost_usd }]`

### GET `/api/admin/token-cost/top-expensive?days=7&limit=20`
비용 상위 세션 목록.

**응답**: `[{ run_id, total_tokens, total_cost_usd, model, time }]`

---

## 4. Slack 활동 (`/api/admin/slack-activity`)

### GET `/api/admin/slack-activity/channels?days=30`
채널별 활동 통계.

**응답**: `[{ channel, session_count, unique_users, total_tokens, total_cost_usd, avg_latency_ms }]`

### GET `/api/admin/slack-activity/daily?days=30`
일별 Slack 메시지 수 추이.

**응답**: `[{ day, message_count, unique_users, success_count, failure_count }]`

---

## 5. Eval 대시보드 (`/api/admin/evals`)

### GET `/api/admin/evals/runs?days=30`
평가 런 목록.

**응답**: `[{ eval_run_id, total_cases, pass_count, avg_score, avg_latency_ms, total_tokens, total_cost, started_at, ended_at }]`

### GET `/api/admin/evals/pass-rate?days=30`
합격률 일별 추이.

**응답**: `[{ day, total, passed, avg_score }]`

---

## 6. 데이터 보존 정책 (`/api/admin/retention`)

### GET `/api/admin/retention`
현재 보존 정책 조회.

**응답**: `{ sessionRetentionDays, conversationRetentionDays, auditRetentionDays, metricRetentionDays }`

기본값: 세션 90일, 대화 365일, 감사 730일, 메트릭 180일.

### PUT `/api/admin/retention`
보존 정책 변경.

**요청**: `{ sessionRetentionDays?: int, conversationRetentionDays?: int, auditRetentionDays?: int, metricRetentionDays?: int }`

변경은 감사 로그에 기록됨. RuntimeSettingsService에 저장되어 재배포 없이 적용.

---

## 7. RAG 문서 분석 (`/api/admin/rag-analytics`)

### GET `/api/admin/rag-analytics/status`
후보 문서 상태별 통계 (PENDING/INGESTED/REJECTED).

**응답**: `[{ status, count, latest_captured }]`

### GET `/api/admin/rag-analytics/by-channel?days=30`
채널별 RAG 후보 생성 추이.

**응답**: `[{ channel, candidate_count, ingested, pending, rejected }]`

---

## 8. 대화 분석 (`/api/admin/conversation-analytics`)

### GET `/api/admin/conversation-analytics/by-channel?days=30`
채널별 성공/실패 통계.

**응답**: `[{ channel, total, success, failure, success_rate, avg_duration_ms }]`

### GET `/api/admin/conversation-analytics/failure-patterns?days=30`
실패 원인 분포 (에러 코드별, 상위 20개).

**응답**: `[{ error_class, count, latest }]`

### GET `/api/admin/conversation-analytics/latency-distribution?days=7`
응답 시간 분포 (히스토그램).

**응답**: `[{ bucket, count }]`

버킷: `< 1s`, `1-3s`, `3-5s`, `5-10s`, `> 10s`

---

---

## 9. 사용자 사용량/비용 상세 (`/api/admin/users/usage`)

### GET `/api/admin/users/usage/top?days=30&limit=20`
상위 사용자 (요청 수 기준).

### GET `/api/admin/users/usage/cost?days=30&limit=20`
사용자별 토큰/비용 상세.

**응답**: `[{ user_id, session_count, total_tokens, total_cost_usd, avg_latency_ms, last_activity }]`

### GET `/api/admin/users/usage/daily?days=30`
일별 전체 사용량 추이.

**응답**: `[{ day, session_count, total_tokens, total_cost_usd, unique_users }]`

---

## 10. Input Guard 설정 변경 (`/api/input-guard`)

### GET `/api/input-guard/pipeline`
파이프라인 단계 목록 (runtimeOverride 포함).

### PUT `/api/input-guard/settings`
Guard 단계 설정 변경 (RuntimeSettings 기반).

**요청**: `{ "settings": { "guard.stage.injection-detection.enabled": "false" } }`

**응답**: `{ "updated": 1, "note": "일부 변경은 서버 재시작 후 적용됩니다" }`

변경은 감사 로그에 기록됨. `guard.` 접두사가 아닌 키는 무시됨.

---

## 기존 API (이번 세션에서 구현)

| API | 엔드포인트 | 설명 |
|-----|-----------|------|
| 사용자 사용량 | `GET /api/admin/users/usage/top` | 상위 사용자 토큰 소비 |
| 레이턴시 | `GET /api/admin/metrics/latency/summary` | P50/P95/P99 |
| 레이턴시 시계열 | `GET /api/admin/metrics/latency/timeseries` | 시간대별 추이 |
| Input Guard | `GET /api/input-guard/pipeline` | 7단계 파이프라인 목록 |
| 모델 레지스트리 | `GET /api/admin/models` | 모델 목록 + 가격표 |
| RBAC | `GET /api/admin/rbac/roles` | 4역할 × 20권한 |
| RBAC 변경 | `PUT /api/admin/rbac/users/{id}/role` | 사용자 역할 변경 |
| 에이전트 스펙 | `GET/POST/PUT/DELETE /api/admin/agent-specs` | 멀티에이전트 CRUD |
| 감사 내보내기 | `GET /api/admin/audits/export` | CSV 내보내기 |

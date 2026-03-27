# 동적 스케줄러

> 이 문서는 Arc Reactor의 동적 스케줄러 시스템을 설명합니다 -- cron 기반 작업이 어떻게 정의, 실행, 재시도, 모니터링되는지를 다룹니다.

## 한 줄 요약

**cron 작업으로 반복 태스크를 스케줄링하여 단일 MCP 도구 또는 전체 ReAct 에이전트 루프를 실행하고, 재시도, 타임아웃, Slack/Teams 알림을 지원한다.**

---

## 왜 필요한가?

스케줄러 없이는 반복 작업에 외부 오케스트레이션(cron, Airflow 등)이 필요합니다:

```
외부 cron  →  curl POST /api/chat  →  매번 전체 에이전트 시작 오버헤드
```

문제점:
- **중앙 관리 불가**: 작업 정의가 애플리케이션 외부에 존재
- **실행 이력 없음**: 무엇이 언제 실행되었고 성공했는지 감사 추적 불가
- **재시도/타임아웃 없음**: 실패 시 수동 재실행 필요
- **알림 없음**: 결과가 팀 채널로 푸시되지 않음

동적 스케줄러 적용 후:

```
arc.reactor.scheduler.enabled=true
POST /api/scheduler/jobs  →  cron으로 작업 실행  →  Slack/Teams로 결과 전송
```

---

## 아키텍처

```
ApplicationReadyEvent
    │
    ▼
┌─ DynamicSchedulerService ──────────────────────────────────────────┐
│  onApplicationReady():                                              │
│    1. ScheduledJobStore에서 활성화된 모든 작업 로드                    │
│    2. Spring TaskScheduler를 통해 각 작업에 CronTrigger 등록         │
│                                                                     │
│  각 cron 트리거 발동 시:                                              │
│    ┌──────────────────────────────────────────────────────┐         │
│    │  runScheduledJob(job)                                 │         │
│    │    1. 상태 = RUNNING으로 표시                          │         │
│    │    2. runJobWithTimeoutAndRetry(job)                 │         │
│    │       ├─ withTimeout(executionTimeoutMs)             │         │
│    │       └─ runWithRetry(job)                           │         │
│    │           └─ dispatchJobByType(job)                  │         │
│    │               ├─ MCP_TOOL → runMcpToolJob()          │         │
│    │               └─ AGENT   → runAgentJob()             │         │
│    │    3. 실행 이력 기록                                   │         │
│    │    4. Slack / Teams 알림 전송                          │         │
│    │    5. 상태 = SUCCESS 또는 FAILED로 표시                │         │
│    └──────────────────────────────────────────────────────┘         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 실행 모드

### MCP_TOOL 모드

단일 MCP 도구를 직접 호출합니다. 기존 동작 방식입니다.

```
DynamicSchedulerService
    │
    ├─ mcpManager.ensureConnected(serverName)
    ├─ mcpManager.getToolCallbacks(serverName)
    ├─ 이름으로 도구 검색
    ├─ BeforeToolCall 훅 확인
    ├─ ToolApprovalPolicy 확인 (설정된 경우)
    └─ tool.call(resolvedArguments) → 결과 문자열
```

필수 필드: `mcpServerName`, `toolName`
선택 필드: `toolArguments` (실행 시점에 템플릿 변수 치환)

### AGENT 모드

전체 ReAct 에이전트 루프를 실행합니다. LLM이 등록된 모든 MCP 도구를 활용하여 추론하고 자연어 결과를 생성합니다.

```
DynamicSchedulerService
    │
    ├─ 시스템 프롬프트 결정 (우선순위 순):
    │    1. agentSystemPrompt (작업에 명시적으로 설정)
    │    2. personaId → PersonaStore.get() → resolveEffectivePrompt()
    │    3. 기본 페르소나 → resolveEffectivePrompt()
    │    4. 내장 폴백 프롬프트
    │
    ├─ agentPrompt 내 템플릿 변수 치환
    ├─ AgentCommand(systemPrompt, userPrompt, model, maxToolCalls) 빌드
    └─ agentExecutor.execute(command) → 결과
```

필수 필드: `agentPrompt`
선택 필드: `personaId`, `agentSystemPrompt`, `agentModel`, `agentMaxToolCalls`

**비용 주의**: AGENT 모드는 매 실행마다 LLM을 호출하므로 API 비용이 발생합니다.

---

## 템플릿 변수

모든 템플릿 변수는 작업에 설정된 타임존을 사용하여 실행 시점에 치환됩니다:

| 변수 | 출력 예시 | 설명 |
|---|---|---|
| `{{date}}` | `2024-12-25` | ISO 로컬 날짜 |
| `{{time}}` | `09:00:00` | ISO 로컬 시간 (소수점 초 제외) |
| `{{datetime}}` | `2024-12-25 09:00:00` | `yyyy-MM-dd HH:mm:ss` |
| `{{day_of_week}}` | `Wednesday` | 영어 전체 요일명 |
| `{{job_name}}` | `daily-summary` | 작업의 name 필드 |
| `{{job_id}}` | `abc-123` | 작업의 ID |

템플릿 변수는 `toolArguments` 값(MCP_TOOL 모드)과 `agentPrompt`(AGENT 모드) 모두에서 치환됩니다.

**예시**:
```json
{
  "agentPrompt": "오늘({{date}}, {{day_of_week}}) 주요 뉴스와 이벤트를 요약해줘."
}
```

---

## Cron 표현식 형식

스케줄러는 Spring의 6필드 cron 형식을 사용합니다:

```
초  분  시  일  월  요일
```

| 표현식 | 의미 |
|---|---|
| `0 0 9 * * *` | 매일 오전 9시 |
| `0 0 9 * * 1-5` | 평일 오전 9시 |
| `0 0 */2 * * *` | 2시간마다 |
| `0 30 14 * * *` | 매일 오후 2시 30분 |
| `0 0 0 1 * *` | 매월 1일 자정 |

각 작업에는 `timezone` 필드가 있습니다 (기본: `Asia/Seoul`). cron 트리거는 지정된 타임존에 따라 발동됩니다.

---

## 재시도와 타임아웃

### 재시도

`retryOnFailure = true`이면, 실패한 작업은 `maxRetryCount`회(기본: 3)까지 2초 간격으로 재시도됩니다.

```yaml
retryOnFailure: true
maxRetryCount: 3    # 최초 1회 + 재시도 2회
```

유효성 검사: 재시도 활성화 시 `maxRetryCount`는 1 이상이어야 합니다.

### 타임아웃

각 작업은 `executionTimeoutMs`를 지정할 수 있습니다. 미설정 시 `SchedulerProperties.defaultExecutionTimeoutMs`(300,000ms = 5분)가 사용됩니다.

```yaml
executionTimeoutMs: 60000   # 1분
```

유효 범위: 1,000ms ~ 3,600,000ms (1시간). 0 또는 null은 글로벌 기본값을 사용합니다.

---

## 알림

### Slack

작업에 `slackChannelId`를 설정합니다. `SlackMessageSender` 빈이 구성되어 있어야 합니다.

- **MCP_TOOL**: 코드 블록 내 결과 포함
- **AGENT**: 에이전트의 자연어 브리핑 포함

### Microsoft Teams

작업에 `teamsWebhookUrl`을 설정합니다. `TeamsMessageSender` 빈이 구성되어 있어야 합니다.

메시지 형식은 Slack과 동일합니다 (볼드 작업명 접두사가 포함된 Markdown).

메시지는 3,000자에서 잘리며 `...` 표시가 추가됩니다.

알림 실패는 경고로 로깅되며 작업 실행을 차단하지 않습니다.

---

## 실행 이력

각 작업 실행은 `ScheduledJobExecution` 항목으로 기록됩니다:

| 필드 | 설명 |
|---|---|
| `id` | 고유 실행 ID (`exec-{jobId}-{timestamp}`) |
| `jobId` | 상위 작업 ID |
| `jobName` | 상위 작업 이름 |
| `status` | `SUCCESS`, `FAILED`, `RUNNING`, `SKIPPED` |
| `result` | 출력 텍스트 (작업 저장소에서 5,000자로 잘림) |
| `durationMs` | 총 실행 시간 (밀리초) |
| `dryRun` | 드라이런 실행 여부 |
| `startedAt` | 실행 시작 시각 |
| `completedAt` | 실행 완료 시각 |

오래된 실행 기록은 자동으로 정리됩니다. `maxExecutionsPerJob` 속성(기본: 100)이 작업별 보관 항목 수를 제어합니다. 인메모리 저장소는 전체 항목을 200개로 제한합니다.

---

## 도구 인터페이스

4개의 에이전트용 도구가 자연어로 스케줄 작업을 관리할 수 있게 합니다:

| 도구 클래스 | 함수 | 설명 |
|---|---|---|
| `CreateScheduledJobTool` | `create_scheduled_job` | 새 AGENT 모드 작업 생성 |
| `ListScheduledJobsTool` | `list_scheduled_jobs` | 모든 작업과 상태 조회 |
| `UpdateScheduledJobTool` | `update_scheduled_job` | ID 또는 이름으로 부분 수정 |
| `DeleteScheduledJobTool` | `delete_scheduled_job` | ID 또는 이름으로 삭제 |

모든 도구는 `status` 또는 `error` 필드가 포함된 JSON 응답을 반환합니다. 중복 작업 이름은 거부됩니다. `CreateScheduledJobTool`은 항상 AGENT 모드 작업을 생성합니다.

---

## REST API

모든 엔드포인트는 ADMIN 역할이 필요합니다. 기본 경로: `/api/scheduler/jobs`

### 작업 목록 조회
```
GET /api/scheduler/jobs?offset=0&limit=50&tag=reports
```

### 작업 생성 (MCP_TOOL)
```
POST /api/scheduler/jobs
Content-Type: application/json

{
  "name": "daily-cleanup",
  "cronExpression": "0 0 2 * * *",
  "timezone": "Asia/Seoul",
  "jobType": "MCP_TOOL",
  "mcpServerName": "db-tools",
  "toolName": "cleanupExpiredSessions",
  "toolArguments": { "olderThanDays": 30 },
  "slackChannelId": "C123456",
  "retryOnFailure": true,
  "maxRetryCount": 3
}
```

### 작업 생성 (AGENT)
```
POST /api/scheduler/jobs
Content-Type: application/json

{
  "name": "morning-briefing",
  "cronExpression": "0 0 9 * * 1-5",
  "timezone": "Asia/Seoul",
  "jobType": "AGENT",
  "agentPrompt": "오늘({{date}}, {{day_of_week}}) 주요 지표와 알림을 요약해줘.",
  "personaId": "analyst-persona",
  "agentMaxToolCalls": 5,
  "slackChannelId": "C789012",
  "teamsWebhookUrl": "https://outlook.office.com/webhook/..."
}
```

### 작업 조회
```
GET /api/scheduler/jobs/{id}
```

### 작업 수정
```
PUT /api/scheduler/jobs/{id}
```

### 작업 삭제
```
DELETE /api/scheduler/jobs/{id}
```
응답: `204 No Content`

### 즉시 실행
```
POST /api/scheduler/jobs/{id}/trigger
```
응답: `{ "result": "..." }`

### 드라이런 (부작용 없음)
```
POST /api/scheduler/jobs/{id}/dry-run
```
응답: `{ "result": "...", "dryRun": true }`

드라이런은 작업 로직을 실행하지만 작업의 `lastStatus`/`lastResult`를 업데이트하거나 Slack/Teams 알림을 보내지 않습니다.

### 실행 이력
```
GET /api/scheduler/jobs/{id}/executions?limit=20&offset=0
```

---

## 설정

```yaml
arc:
  reactor:
    scheduler:
      enabled: false                      # 기본: false (opt-in)
      thread-pool-size: 5                 # 스케줄 태스크 실행 스레드 풀 크기
      default-timezone: Asia/Seoul        # 작업에서 타임존 미지정 시 기본값
      default-execution-timeout-ms: 300000 # 5분 기본 타임아웃
      max-executions-per-job: 100         # 작업별 실행 이력 보관 수
```

---

## 데이터베이스 스키마

### `scheduled_jobs` (V11 + V26 + V27 + V28 + V35)

```sql
CREATE TABLE scheduled_jobs (
    id                    VARCHAR(36)   PRIMARY KEY,
    name                  VARCHAR(200)  NOT NULL UNIQUE,
    description           TEXT,
    cron_expression       VARCHAR(100)  NOT NULL,
    timezone              VARCHAR(50)   NOT NULL DEFAULT 'Asia/Seoul',
    job_type              VARCHAR(20)   NOT NULL DEFAULT 'MCP_TOOL',
    mcp_server_name       VARCHAR(100),
    tool_name             VARCHAR(200),
    tool_arguments        TEXT          DEFAULT '{}',
    agent_prompt          TEXT,
    persona_id            VARCHAR(100),
    agent_system_prompt   TEXT,
    agent_model           VARCHAR(100),
    agent_max_tool_calls  INTEGER,
    tags                  VARCHAR(1000),
    slack_channel_id      VARCHAR(100),
    teams_webhook_url     VARCHAR(500),
    retry_on_failure      BOOLEAN       NOT NULL DEFAULT FALSE,
    max_retry_count       INTEGER       NOT NULL DEFAULT 3,
    execution_timeout_ms  BIGINT,
    enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
    last_run_at           TIMESTAMP,
    last_status           VARCHAR(20),
    last_result           TEXT,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### `scheduled_job_executions` (V28)

```sql
CREATE TABLE scheduled_job_executions (
    id           VARCHAR(100)              PRIMARY KEY,
    job_id       VARCHAR(100)              NOT NULL,
    job_name     VARCHAR(200)              NOT NULL,
    status       VARCHAR(20)               NOT NULL,
    result       TEXT,
    duration_ms  BIGINT                    NOT NULL DEFAULT 0,
    dry_run      BOOLEAN                   NOT NULL DEFAULT FALSE,
    started_at   TIMESTAMP WITH TIME ZONE  NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_job_executions_job_id     ON scheduled_job_executions(job_id);
CREATE INDEX idx_job_executions_started_at ON scheduled_job_executions(started_at DESC);
```

---

## 주의사항

1. **AGENT 모드 비용**: 각 AGENT 작업 실행은 LLM을 호출합니다. 1분마다 실행되는 cron = 시간당 60회 LLM 호출. 적절한 cron 간격을 사용하세요.
2. **타임존 불일치**: `timezone` 필드는 cron 트리거 타이밍과 템플릿 변수 치환(`{{date}}`, `{{time}}`) 모두를 제어합니다. 사용자 기대와 일치하는지 확인하세요.
3. **MCP 서버 연결**: MCP_TOOL 작업은 트리거 시점에 MCP 서버가 연결 해제되어 있으면 실패합니다. 스케줄러가 `mcpManager.ensureConnected()`를 호출하지만 서버 가용성을 보장하지는 않습니다.
4. **결과 잘림**: 작업 저장소의 `last_result`는 5,000자로 잘립니다. 실행 이력 레코드에는 전체 결과가 저장됩니다.
5. **중복 작업 이름**: 작업 이름은 고유해야 합니다. `CreateScheduledJobTool`은 생성 전 중복을 검사합니다.

---

## 주요 파일

| 파일 | 역할 |
|------|------|
| `scheduler/ScheduledJobModels.kt` | 데이터 클래스: ScheduledJob, ScheduledJobExecution, enum |
| `scheduler/ScheduledJobStore.kt` | 작업 저장소 인터페이스 |
| `scheduler/ScheduledJobExecutionStore.kt` | 실행 이력 저장소 인터페이스 |
| `scheduler/DynamicSchedulerService.kt` | 핵심 서비스: cron 등록, 실행, 재시도, 알림 |
| `scheduler/InMemoryScheduledJobStore.kt` | 인메모리 작업 저장소 (기본) |
| `scheduler/InMemoryScheduledJobExecutionStore.kt` | 인메모리 실행 저장소 (기본, 최대 200개) |
| `scheduler/JdbcScheduledJobStore.kt` | JDBC 작업 저장소 (PostgreSQL) |
| `scheduler/TeamsMessageSender.kt` | Teams 웹훅 인터페이스 |
| `scheduler/tool/CreateScheduledJobTool.kt` | 에이전트 도구: 작업 생성 |
| `scheduler/tool/ListScheduledJobsTool.kt` | 에이전트 도구: 작업 목록 |
| `scheduler/tool/UpdateScheduledJobTool.kt` | 에이전트 도구: 작업 수정 |
| `scheduler/tool/DeleteScheduledJobTool.kt` | 에이전트 도구: 작업 삭제 |
| `controller/SchedulerController.kt` | 스케줄러 관리 REST API |
| `agent/config/AgentPolicyAndFeatureProperties.kt` | SchedulerProperties 설정 |

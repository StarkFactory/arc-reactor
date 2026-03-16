# Dynamic Scheduler

> This document explains Arc Reactor's Dynamic Scheduler system -- how cron-based jobs are defined, executed, retried, and monitored at runtime.

## One-Line Summary

**Schedule recurring tasks as cron jobs that execute either a single MCP tool or a full ReAct agent loop, with retry, timeout, and Slack/Teams notification support.**

---

## Why Is It Needed?

Without a scheduler, recurring tasks require external orchestration (cron, Airflow, etc.):

```
External cron  →  curl POST /api/chat  →  Full agent startup overhead each time
```

Problems:
- **No centralized management**: Job definitions live outside the application
- **No execution history**: No audit trail of what ran, when, and whether it succeeded
- **No retry/timeout**: Failures require manual re-triggering
- **No notification**: Results are not pushed to team channels

With the Dynamic Scheduler:

```
arc.reactor.scheduler.enabled=true
POST /api/scheduler/jobs  →  Job runs on cron  →  Result sent to Slack/Teams
```

---

## Architecture

```
ApplicationReadyEvent
    │
    ▼
┌─ DynamicSchedulerService ──────────────────────────────────────────┐
│  onApplicationReady():                                              │
│    1. Load all enabled jobs from ScheduledJobStore                  │
│    2. Register CronTrigger for each job via Spring TaskScheduler    │
│                                                                     │
│  On each cron trigger fire:                                         │
│    ┌──────────────────────────────────────────────────────┐         │
│    │  executeJob(job)                                     │         │
│    │    1. Mark status = RUNNING                          │         │
│    │    2. runJobWithRetryAndTimeout(job)                 │         │
│    │       ├─ withTimeout(executionTimeoutMs)             │         │
│    │       └─ runWithRetry(job)                           │         │
│    │           └─ executeJobContent(job)                  │         │
│    │               ├─ MCP_TOOL → executeMcpToolJob()      │         │
│    │               └─ AGENT   → executeAgentJob()         │         │
│    │    3. Record execution history                       │         │
│    │    4. Send Slack / Teams notification                │         │
│    │    5. Mark status = SUCCESS or FAILED                │         │
│    └──────────────────────────────────────────────────────┘         │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Execution Modes

### MCP_TOOL Mode

Directly invokes a single MCP tool. This is the original behavior.

```
DynamicSchedulerService
    │
    ├─ mcpManager.ensureConnected(serverName)
    ├─ mcpManager.getToolCallbacks(serverName)
    ├─ Find tool by name
    ├─ Check BeforeToolCall hook
    ├─ Check ToolApprovalPolicy (if configured)
    └─ tool.call(resolvedArguments) → result string
```

Required fields: `mcpServerName`, `toolName`
Optional fields: `toolArguments` (template variables resolved at execution time)

### AGENT Mode

Runs the full ReAct agent loop. The LLM reasons over all registered MCP tools and produces a natural-language result.

```
DynamicSchedulerService
    │
    ├─ Resolve system prompt (priority order):
    │    1. agentSystemPrompt (explicit on job)
    │    2. personaId → PersonaStore.get() → resolveEffectivePrompt()
    │    3. Default persona → resolveEffectivePrompt()
    │    4. Built-in fallback prompt
    │
    ├─ Resolve template variables in agentPrompt
    ├─ Build AgentCommand(systemPrompt, userPrompt, model, maxToolCalls)
    └─ agentExecutor.execute(command) → result
```

Required fields: `agentPrompt`
Optional fields: `personaId`, `agentSystemPrompt`, `agentModel`, `agentMaxToolCalls`

**Cost warning**: AGENT mode invokes the LLM on every execution, which incurs API costs.

---

## Template Variables

All template variables are resolved at execution time using the job's configured timezone:

| Variable | Example Output | Description |
|---|---|---|
| `{{date}}` | `2024-12-25` | ISO local date |
| `{{time}}` | `09:00:00` | ISO local time (no fractional seconds) |
| `{{datetime}}` | `2024-12-25 09:00:00` | `yyyy-MM-dd HH:mm:ss` |
| `{{day_of_week}}` | `Wednesday` | Full English day name |
| `{{job_name}}` | `daily-summary` | The job's name field |
| `{{job_id}}` | `abc-123` | The job's ID |

Template variables are resolved in both `toolArguments` values (MCP_TOOL mode) and `agentPrompt` (AGENT mode).

**Example**:
```json
{
  "agentPrompt": "Summarize today's ({{date}}, {{day_of_week}}) key news and events."
}
```

---

## Cron Expression Format

The scheduler uses Spring's 6-field cron format:

```
second  minute  hour  day-of-month  month  day-of-week
```

| Expression | Meaning |
|---|---|
| `0 0 9 * * *` | Daily at 9:00 AM |
| `0 0 9 * * 1-5` | Weekdays at 9:00 AM |
| `0 0 */2 * * *` | Every 2 hours |
| `0 30 14 * * *` | Daily at 2:30 PM |
| `0 0 0 1 * *` | First day of every month at midnight |

Each job has a `timezone` field (default: `Asia/Seoul`). The cron trigger fires according to the specified timezone.

---

## Retry and Timeout

### Retry

When `retryOnFailure = true`, a failed job is retried up to `maxRetryCount` times (default: 3) with a 2-second delay between attempts.

```yaml
retryOnFailure: true
maxRetryCount: 3    # 1 initial + 2 retries
```

Validation: `maxRetryCount` must be >= 1 when retry is enabled.

### Timeout

Each job can specify `executionTimeoutMs`. If not set, the global default from `SchedulerProperties.defaultExecutionTimeoutMs` (300,000ms = 5 minutes) is used.

```yaml
executionTimeoutMs: 60000   # 1 minute
```

Valid range: 1,000ms to 3,600,000ms (1 hour). Value of 0 or null uses the global default.

---

## Notifications

### Slack

Set `slackChannelId` on the job. Requires a `SlackMessageSender` bean to be configured.

- **MCP_TOOL**: Message includes result in a code block
- **AGENT**: Message includes the agent's natural-language briefing

### Microsoft Teams

Set `teamsWebhookUrl` on the job. Requires a `TeamsMessageSender` bean to be configured.

Message format mirrors Slack (Markdown with bold job name prefix).

Messages are truncated at 3,000 characters with a trailing `...` indicator.

Notification failures are logged as warnings and never block job execution.

---

## Execution History

Each job execution is recorded as a `ScheduledJobExecution` entry:

| Field | Description |
|---|---|
| `id` | Unique execution ID (`exec-{jobId}-{timestamp}`) |
| `jobId` | Parent job ID |
| `jobName` | Parent job name |
| `status` | `SUCCESS`, `FAILED`, `RUNNING`, `SKIPPED` |
| `result` | Output text (truncated at 5,000 chars in job store) |
| `durationMs` | Total execution time in milliseconds |
| `dryRun` | Whether this was a dry-run execution |
| `startedAt` | Execution start timestamp |
| `completedAt` | Execution end timestamp |

Old executions are automatically cleaned up. The `maxExecutionsPerJob` property (default: 100) controls how many entries to retain per job. The in-memory store caps total entries at 200.

---

## Tool Interface

Four agent-facing tools allow the LLM to manage scheduled jobs via natural language:

| Tool Class | Function | Description |
|---|---|---|
| `CreateScheduledJobTool` | `create_scheduled_job` | Create a new AGENT-mode job |
| `ListScheduledJobsTool` | `list_scheduled_jobs` | List all jobs with status |
| `UpdateScheduledJobTool` | `update_scheduled_job` | Partial update by ID or name |
| `DeleteScheduledJobTool` | `delete_scheduled_job` | Delete by ID or name |

All tools return JSON responses with `status` or `error` fields. Duplicate job names are rejected. The `CreateScheduledJobTool` always creates AGENT-mode jobs.

---

## REST API

All endpoints require ADMIN role. Base path: `/api/scheduler/jobs`

### List Jobs
```
GET /api/scheduler/jobs?offset=0&limit=50&tag=reports
```

### Create Job (MCP_TOOL)
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

### Create Job (AGENT)
```
POST /api/scheduler/jobs
Content-Type: application/json

{
  "name": "morning-briefing",
  "cronExpression": "0 0 9 * * 1-5",
  "timezone": "Asia/Seoul",
  "jobType": "AGENT",
  "agentPrompt": "Summarize today's ({{date}}, {{day_of_week}}) key metrics and alerts.",
  "personaId": "analyst-persona",
  "agentMaxToolCalls": 5,
  "slackChannelId": "C789012",
  "teamsWebhookUrl": "https://outlook.office.com/webhook/..."
}
```

### Get Job
```
GET /api/scheduler/jobs/{id}
```

### Update Job
```
PUT /api/scheduler/jobs/{id}
```

### Delete Job
```
DELETE /api/scheduler/jobs/{id}
```
Response: `204 No Content`

### Trigger Immediate Execution
```
POST /api/scheduler/jobs/{id}/trigger
```
Response: `{ "result": "..." }`

### Dry Run (No Side Effects)
```
POST /api/scheduler/jobs/{id}/dry-run
```
Response: `{ "result": "...", "dryRun": true }`

Dry run executes the job logic but does not update the job's `lastStatus`/`lastResult` or send Slack/Teams notifications.

### Execution History
```
GET /api/scheduler/jobs/{id}/executions?limit=20&offset=0
```

---

## Configuration

```yaml
arc:
  reactor:
    scheduler:
      enabled: false                      # Default: false (opt-in)
      thread-pool-size: 5                 # Thread pool for scheduled task execution
      default-timezone: Asia/Seoul        # Default timezone when job omits it
      default-execution-timeout-ms: 300000 # 5 minutes default timeout
      max-executions-per-job: 100         # Execution history retention per job
```

---

## Database Schema

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

## Common Pitfalls

1. **AGENT mode costs**: Each AGENT job execution invokes the LLM. A cron running every minute = 60 LLM calls/hour. Always use appropriate cron intervals.
2. **Timezone mismatch**: The `timezone` field controls both cron trigger timing and template variable resolution (`{{date}}`, `{{time}}`). Ensure consistency with user expectations.
3. **MCP server connectivity**: MCP_TOOL jobs fail if the MCP server is disconnected at trigger time. The scheduler calls `mcpManager.ensureConnected()` but cannot guarantee the server is available.
4. **Result truncation**: `last_result` in the job store is truncated at 5,000 characters. Execution history records store the full result.
5. **Duplicate job names**: Job names must be unique. The `CreateScheduledJobTool` checks for duplicates before creating.

---

## Key Files

| File | Role |
|------|------|
| `scheduler/ScheduledJobModels.kt` | Data classes: ScheduledJob, ScheduledJobExecution, enums |
| `scheduler/ScheduledJobStore.kt` | Job store interface |
| `scheduler/ScheduledJobExecutionStore.kt` | Execution history store interface |
| `scheduler/DynamicSchedulerService.kt` | Core service: cron registration, execution, retry, notifications |
| `scheduler/InMemoryScheduledJobStore.kt` | In-memory job store (default) |
| `scheduler/InMemoryScheduledJobExecutionStore.kt` | In-memory execution store (default, max 200 entries) |
| `scheduler/JdbcScheduledJobStore.kt` | JDBC job store (PostgreSQL) |
| `scheduler/TeamsMessageSender.kt` | Teams webhook interface |
| `scheduler/tool/CreateScheduledJobTool.kt` | Agent tool: create job |
| `scheduler/tool/ListScheduledJobsTool.kt` | Agent tool: list jobs |
| `scheduler/tool/UpdateScheduledJobTool.kt` | Agent tool: update job |
| `scheduler/tool/DeleteScheduledJobTool.kt` | Agent tool: delete job |
| `controller/SchedulerController.kt` | REST API for scheduler management |
| `agent/config/AgentPolicyAndFeatureProperties.kt` | SchedulerProperties configuration |

# Arc Reactor Admin 전체 기능 검증 패트롤

> 4개 프로젝트 스택의 모든 Admin 기능을 Playwright + curl로 검증하는 자동 패트롤.
> 24개 feature, 111+ API endpoint, 20+ UI 페이지 전수 검사.

## 대상 스택

| 프로젝트 | 포트 | 역할 |
|----------|------|------|
| arc-reactor (backend) | 18081 | Admin API 백엔드 |
| arc-reactor-admin (frontend) | 3001 | Admin 대시보드 UI |
| swagger-mcp-server | 8081 | OpenAPI/Swagger MCP 서버 |
| atlassian-mcp-server | 8085 | Jira/Confluence/Bitbucket MCP 서버 |

---

## Phase 0: Liveness Check

```bash
# Backend: actuator may return 503 without DB, so check auth endpoint instead (401 = running)
curl -s -o /dev/null -w "%{http_code}" http://localhost:18081/api/auth/login -X POST -H "Content-Type: application/json" -d '{}' | grep -qE "^(401|200|400)" && echo "backend: UP" || echo "backend: DOWN"
curl -sf http://localhost:3001 -o /dev/null && echo "admin-ui: UP" || echo "admin-ui: DOWN"
curl -sf http://localhost:8081/actuator/health -o /dev/null && echo "swagger-mcp: UP" || echo "swagger-mcp: DOWN"
# Atlassian MCP: management port is 8086, not 8085
curl -sf http://localhost:8086/actuator/health -o /dev/null && echo "atlassian-mcp: UP" || echo "atlassian-mcp: DOWN"
```

If ANY server is DOWN: log it, skip dependent checks, do NOT start servers.

---

## Phase 1: Backend API Health (curl)

```bash
TOKEN=$(curl -sf http://localhost:18081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@arc-reactor.io","password":"admin"}' | jq -r '.token // empty')
```

If TOKEN is empty, log AUTH_FAILED and skip API checks.

### 1.1 Capabilities

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/capabilities | jq 'length'
```

### 1.2 Platform Health

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/health | jq '.'
```

### 1.3 Tenants

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/tenants | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/tenants/analytics | jq '.'
```

### 1.4 Pricing & Alerts

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/pricing | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/alerts/rules | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/alerts | jq 'length'
```

### 1.5 Tenant Dashboard

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/overview | jq '.'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/usage | jq '.requestCount // "NO_DATA"'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/quality | jq '.latencyP50 // "NO_DATA"'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/tools | jq '.toolRanking // "NO_DATA"'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/cost | jq '.monthlyCost // "NO_DATA"'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/slo | jq '.'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/tenant/quota | jq '.'
```

### 1.6 Operations Dashboard

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/ops/dashboard | jq '.'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/ops/metrics/names | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/ops/dashboard/trends | jq '.'
```

### 1.7 MCP Servers

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/mcp/servers | jq '.[].name'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/mcp/security | jq '.'
```

### 1.8 Personas

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/personas | jq 'length'
```

### 1.9 Prompt Templates

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/prompt-templates | jq 'length'
```

### 1.10 Output Guard

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/output-guard/rules | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/output-guard/rules/audits | jq 'length'
```

### 1.11 Tool Policy

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/tool-policy | jq '.'
```

### 1.12 Documents & RAG

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/rag-ingestion/candidates | jq 'length'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/rag-ingestion/policy | jq '.'
```

### 1.13 Approvals

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/approvals | jq 'length'
```

### 1.14 Feedback

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/feedback | jq 'length'
```

### 1.15 Scheduler

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/scheduler/jobs | jq 'length'
```

### 1.16 Audit

```bash
curl -sf -H "Authorization: Bearer $TOKEN" "http://localhost:18081/api/admin/audits?limit=10" | jq 'length'
```

### 1.17 Intents

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/intents | jq 'length'
```

### 1.18 Proactive Channels

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/proactive-channels | jq 'length'
```

### 1.19 Prompt Lab / Experiments

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/prompt-lab/experiments | jq 'length'
```

### 1.20 Sessions

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/sessions 2>/dev/null | jq 'length' || echo "sessions: NO_ENDPOINT_OR_MOCK"
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/models | jq 'length' || echo "models: NO_ENDPOINT"
```

### 1.21 MCP Server Preflight

```bash
curl -sf http://localhost:8081/admin/preflight | jq '.'
curl -sf http://localhost:8085/admin/preflight | jq '.'
```

Record every endpoint as OK (2xx), FAIL (4xx/5xx), or UNREACHABLE.

---

## Phase 2: Admin UI Full Feature Patrol (Playwright)

Use Playwright MCP tools. Desktop viewport 1440x900.

### 2.0 Login

1. `browser_navigate` → `http://localhost:3001/login`
2. `browser_snapshot` → verify `.login-terminal` visible, ASCII logo present
3. `browser_fill_form` → email: admin@arc-reactor.io, password: admin
4. `browser_click` submit button
5. `browser_wait_for` URL change to `/`
6. `browser_snapshot` → verify dashboard loaded

### 2.1 Dashboard (/)

1. `browser_snapshot` → verify:
   - Health score gauge (Arc Reactor Gauge 0-100)
   - Health bar with readiness status
   - Stat cards grid rendered
   - Trend charts visible
2. Click "Employee Value" button (developer mode) → verify modal opens with metrics
3. Close modal
4. Click "Operational Signals" button → verify modal with metric filter input
5. Close modal
6. Click a stat card → verify side drawer opens with details
7. Close drawer

### 2.2 Issues (/issues)

1. `browser_navigate` → `/issues`
2. `browser_snapshot` → verify:
   - System Topology visualization rendered
   - Summary chips with severity counts
   - Issue list with pagination
3. Click topology node → verify issue list filters by source
4. Click "Critical" severity chip → verify filtered results
5. Click "Clear filters" → verify all issues shown

### 2.3 Approvals (/approvals)

1. `browser_navigate` → `/approvals`
2. `browser_snapshot` → verify:
   - Ops summary panel (total, pending, timeout, decided cards)
   - Status filter dropdown
   - Approval list table
3. Click status filter → select "PENDING" → verify table filters
4. Click status filter → select "TIMED_OUT" → verify table filters
5. Click an approval row → verify detail panel opens on right
6. Verify detail panel shows: tool name, arguments JSON, action buttons (Approve/Reject)
7. If rows exist: verify Reject button opens modal with reason textarea

### 2.4 Sessions (/sessions)

1. `browser_navigate` → `/sessions`
2. `browser_snapshot` → verify session list or overview rendered

#### 2.4.1 Feed View (/sessions/feed)

1. `browser_navigate` → `/sessions/feed`
2. `browser_snapshot` → verify:
   - Search bar
   - Filter chips (channel, trust, feedback, persona, date range)
   - Session list
3. Type in search bar → verify list updates
4. Click a filter chip → verify dropdown opens

#### 2.4.2 Session Users (/sessions/users)

1. `browser_navigate` → `/sessions/users`
2. `browser_snapshot` → verify user list rendered

#### 2.4.3 Session Detail

1. If sessions exist, click a session row → verify:
   - Chat bubbles (message history) rendered
   - Session tags/metadata visible
   - "Load older messages" button (if applicable)

### 2.5 Feedback (/feedback)

1. `browser_navigate` → `/feedback`
2. `browser_snapshot` → verify:
   - Stat cards (total, positive, negative)
   - Filter dropdowns (rating, intent, model, date range)
   - Feedback list table with pagination
3. Click rating filter → select "thumbs_down" → verify filtered
4. Click a feedback row → verify detail panel shows query/response
5. Verify Export button is present and clickable

### 2.6 MCP Servers (/mcp-servers)

1. `browser_navigate` → `/mcp-servers`
2. `browser_snapshot` → verify:
   - Stat cards (total, connected, failed, blocked)
   - Search bar
   - Status filter dropdown
   - Server table with columns: name, status, transport, tools, allowed toggle
3. Test search: type a server name → verify list filters
4. Test status filter: select "CONNECTED" → verify filtered
5. Verify "Connect All Disconnected" bulk action button exists
6. Verify "Emergency Block All" bulk action button exists
7. If servers listed:
   a. Toggle "Allowed" switch on a row → verify state changes
   b. Click a server row → navigate to detail page

#### 2.6.1 MCP Server Detail (/mcp-servers/:name)

1. `browser_snapshot` → verify:
   - Server info (name, transport type, URL, status badge)
   - Connection controls (Connect/Disconnect button)
   - Tool list
   - Access policy section
2. If swagger server: verify Swagger Sources section visible
3. Click "Run Check" (preflight) → verify results display
4. Check access policy details rendered

### 2.7 Personas (/personas)

1. `browser_navigate` → `/personas`
2. `browser_snapshot` → verify:
   - Stat cards (total, active)
   - Persona list table
   - "Create Persona" button
3. Click "Create Persona" → verify modal opens with:
   - Emoji picker, name input, description textarea, settings
4. Close modal (Cancel/Escape)
5. If personas exist:
   a. Click a persona row → verify detail panel
   b. Verify Info tab and Playground tab exist
   c. Click Playground tab → verify chat simulator UI

### 2.8 Prompt Studio (/prompt-studio)

1. `browser_navigate` → `/prompt-studio`
2. `browser_snapshot` → verify:
   - Template list on left
   - Create template button
3. If templates exist:
   a. Click a template → verify detail panel on right
   b. Verify tabs: Versions, Experiments, Settings
   c. Click Versions tab → verify version list and activate/archive buttons
   d. Click Experiments tab → verify experiment list
   e. Click Settings tab → verify edit form

#### 2.8.1 Prompt Lab / Experiments (within Prompt Studio)

1. Click Experiments tab (or navigate to experiments section)
2. `browser_snapshot` → verify:
   - Status filter dropdown (all, PENDING, RUNNING, COMPLETED, FAILED, CANCELLED)
   - "Create Experiment" button
   - Experiment list table
3. If experiments exist:
   a. Click an experiment → verify detail panel with:
      - Status badge
      - Metadata display
      - Action buttons (Run, Cancel, Activate recommendation)
      - Trials table
      - Report display
4. Verify "Auto-Optimize" button/dialog is accessible
5. Verify "Analyze Feedback" button/dialog is accessible

### 2.9 Safety Rules (/safety-rules)

#### 2.9.1 Output Guard

1. `browser_navigate` → `/safety-rules`
2. `browser_snapshot` → verify:
   - Stat cards (total rules, active, reject, audit)
   - Rules table with columns: name, action, priority, enabled
   - "Create Rule" button
   - Simulation section (collapsible)
3. Expand simulation section → verify:
   - Preset buttons (Safe, PII, Secret)
   - Content textarea
   - "Include disabled rules" checkbox
   - "Run Simulation" button
4. Click a preset (e.g., PII) → verify textarea populates
5. Click "Run Simulation" → verify results show (status badge, matched rules)
6. If rules exist: click a rule row → verify detail panel with pattern/regex

#### 2.9.2 Tool Policy (if tab exists)

1. Switch to Tool Policy tab/section
2. `browser_snapshot` → verify:
   - Enabled toggle
   - Write tool names textarea
   - Deny write channels textarea
   - Config diff section
3. Verify Save and Reset buttons present

### 2.10 Documents (/documents)

1. `browser_navigate` → `/documents`
2. `browser_snapshot` → verify tab bar: Search, Register, Ingestion, Policy

#### 2.10.1 Search Tab

1. Verify: query input, topK slider, similarity threshold slider, search button
2. If possible: enter a query → click search → verify results table

#### 2.10.2 Register Tab

1. Click Register tab
2. `browser_snapshot` → verify:
   - Single document form (content textarea, metadata JSON)
   - Add button
   - Batch add section (JSON array input)

#### 2.10.3 Ingestion Tab

1. Click Ingestion tab
2. `browser_snapshot` → verify:
   - Status/channel filters
   - Candidate list
   - Approve/Reject buttons per candidate (if candidates exist)

#### 2.10.4 Policy Tab

1. Click Policy tab
2. `browser_snapshot` → verify:
   - Enabled toggle
   - Policy form inputs (allowed channels, blocked patterns, etc.)
   - Save/Reset buttons

### 2.11 Audit Log (/audit)

1. `browser_navigate` → `/audit`
2. `browser_snapshot` → verify:
   - Category/action filter inputs
   - Summary stats panel
   - Quick filter buttons (all, highRisk, rollbackReady)
   - Audit log table
3. Click "High-Risk Only" → verify table filters
4. Click "All" → verify unfiltered
5. If entries exist: click a row → verify detail panel with:
   - Changed fields as tags
   - Recovery console link
   - Rollback readiness info

### 2.12 Platform Admin (/platform-admin)

1. `browser_navigate` → `/platform-admin`
2. `browser_snapshot` → verify tab navigation exists

#### 2.12.1 Health Tab

1. Click Health tab
2. `browser_snapshot` → verify:
   - Platform health metrics (buffer usage, drop rate, write latency, cache hits)
   - Active alerts section
   - "Evaluate Alerts" button
   - "Invalidate Cache" button

#### 2.12.2 Tenants Tab

1. Click Tenants tab
2. `browser_snapshot` → verify:
   - Tenant list table
   - "Create Tenant" form (name, slug, plan dropdown)
   - Tenant analytics section
3. If tenants exist: verify Suspend/Activate action buttons

#### 2.12.3 Pricing Tab

1. Click Pricing tab
2. `browser_snapshot` → verify:
   - Pricing configuration table
   - Add/update pricing form
   - Alert rules table with edit/delete actions

#### 2.12.4 Roles Tab

1. Click Roles tab
2. `browser_snapshot` → verify:
   - User email lookup input
   - Search button
   - Role selector dropdown

### 2.13 Chat Inspector (/chat-inspector)

1. `browser_navigate` → `/chat-inspector`
2. `browser_snapshot` → verify:
   - Mode tabs (Chat vs Stream)
   - Message textarea
   - Config toolbar (Persona, Model, Template dropdowns)
   - Advanced section (collapsible)
   - Run button
3. Click Advanced → verify system prompt textarea, response format dropdown
4. Verify Persona dropdown has options
5. Verify Model dropdown has options

### 2.14 Integrations (/integrations)

1. `browser_navigate` → `/integrations`
2. `browser_snapshot` → verify:
   - Control plane tab with probe status
   - Connection status for Arc Reactor and MCP servers

#### 2.14.1 Tester Tabs

1. Click Slack tester tab → verify command/event test form
2. Click Error Report tab → verify error simulation form
3. Click Channels tab → verify proactive channels manager

### 2.15 Scheduler (/scheduler)

1. `browser_navigate` → `/scheduler`
2. `browser_snapshot` → verify:
   - Tab bar: Jobs vs Executions
   - Ops summary panel with stat cards

#### 2.15.1 Jobs Tab

1. Verify:
   - Quick filters (all, attention, failed, neverRun, stuckRunning, noRetry)
   - Jobs table (name, type, cron, enabled, last run, actions)
   - "Create Job" button
2. Click "Create Job" → verify modal with:
   - Name, description, cron expression inputs
   - Job type selector (AGENT/MCP_TOOL)
   - Timezone, enabled toggle
3. Close modal
4. If jobs exist:
   a. Click a job row → verify detail panel with execution history
   b. Verify action buttons: Trigger, Edit, Delete

#### 2.15.2 Executions Tab

1. Click Executions tab
2. `browser_snapshot` → verify execution list with status filters

### 2.16 Intents (/intents)

1. `browser_navigate` → `/intents`
2. `browser_snapshot` → verify:
   - Intent list table
   - "Create Intent" button (if exists)
3. If intents exist:
   a. Click an intent row → verify detail panel with intent definition
   b. Verify intent name, description, and configuration displayed
4. Verify CRUD controls: Create, Edit, Delete buttons present

### 2.17 Metrics Ingestion (/metrics-ingestion)

1. `browser_navigate` → `/metrics-ingestion`
2. `browser_snapshot` → verify:
   - MCP Health ingestion form/section
   - Tool Call ingestion form/section
   - Eval Result ingestion form/section (single + batch)
   - Batch ingestion section
3. Verify form inputs and submit buttons are present and properly labeled

### 2.18 Session User Detail (/sessions/users/:userId)

1. Navigate to `/sessions/users` first
2. If users listed: click a user row → navigate to `/sessions/users/:userId`
3. `browser_snapshot` → verify:
   - User info displayed
   - Session history list for that user
   - Session rows clickable

### 2.19 404 Not Found Page

1. `browser_navigate` → `http://localhost:3001/this-does-not-exist-patrol-test`
2. `browser_snapshot` → verify:
   - NotFoundPage renders (not a blank screen or crash)
   - Shows 404 message or navigation back to home

### 2.20 Redirect Routes Verification

Verify all legacy redirect routes work correctly:

1. `browser_navigate` → `/prompts` → verify URL redirected to `/prompt-studio`
2. `browser_navigate` → `/mcp-security` → verify URL redirected to `/mcp-servers`
3. `browser_navigate` → `/output-guard` → verify URL redirected to `/safety-rules?tab=output-guard`
4. `browser_navigate` → `/tool-policy` → verify URL redirected to `/safety-rules?tab=tool-policy`
5. `browser_navigate` → `/prompt-lab` → verify URL redirected to `/prompt-studio`
6. `browser_navigate` → `/tenant-admin` → verify URL redirected to `/platform-admin?tab=tenant`
7. `browser_navigate` → `/proactive-channels` → verify URL redirected to `/integrations?tab=channels`

---

## Phase 3: Workspace Mode Verification

### 3.1 Manager Mode

1. Switch to Manager mode (header toggle)
2. `browser_snapshot` → verify sidebar shows exactly 7 items (audience: all):
   - Operations: Dashboard, Issues, Approvals, Platform Admin
   - Monitoring: Sessions, Feedback, Audit
3. Verify developer-only routes redirect when accessed directly:
   a. `/personas` → redirect to `/`
   b. `/prompt-studio` → redirect to `/`
   c. `/documents` → redirect to `/`
   d. `/safety-rules` → redirect to `/`
   e. `/mcp-servers` → redirect to `/`
   f. `/chat-inspector` → redirect to `/`
   g. `/integrations` → redirect to `/`
   h. `/scheduler` → redirect to `/`

### 3.2 Developer Mode

1. Switch to Developer mode
2. `browser_snapshot` → verify sidebar shows all 15 items across 4 groups:
   - Operations (4, audience: all): Dashboard, Issues, Approvals, Platform Admin
   - AI Config (4, audience: developer): Personas, Prompt Studio, Documents, Safety Rules
   - Monitoring (3, audience: all): Sessions, Feedback, Audit
   - Dev Tools (4, audience: developer): MCP Servers, Chat Inspector, Integrations, Scheduler
3. Verify all 15 sidebar links are clickable and navigate correctly

---

## Phase 4: i18n Verification

1. Find language toggle (KO/EN) in header
2. Record current language
3. Switch language
4. `browser_snapshot` → verify text changed (e.g., "Dashboard" ↔ "대시보드")
5. Navigate to `/personas` → verify all text is in switched language
6. Navigate to `/scheduler` → verify job form labels are translated
7. Switch back to original language

---

## Phase 5: Responsive Check

### 5.1 Mobile (390x844)

1. `browser_resize` to 390x844
2. Navigate to `/` → `browser_snapshot` → verify:
   - Layout adapts, no horizontal overflow
   - Sidebar collapses to hamburger menu
3. Navigate to `/mcp-servers` → verify table responsive
4. Navigate to `/approvals` → verify split layout stacks vertically

### 5.2 Tablet (768x1024)

1. `browser_resize` to 768x1024
2. Navigate to `/` → `browser_snapshot` → verify layout adapts
3. Navigate to `/platform-admin` → verify tabs still accessible

### 5.3 Return to Desktop

1. `browser_resize` to 1440x900

---

## Phase 6: Cross-Stack Integration

### 6.1 MCP Server Registration

1. Navigate to `/mcp-servers`
2. Verify swagger-mcp-server (port 8081) listed with correct status
3. Verify atlassian-mcp-server (port 8085) listed with correct status
4. If either missing → log as CRITICAL

### 6.2 MCP Server Detail — Swagger

1. Click swagger-mcp-server in list
2. Verify detail page: transport type (SSE), URL (http://localhost:8081/sse)
3. Check Swagger Sources section: verify spec sources listed
4. Run preflight check → verify results

### 6.3 MCP Server Detail — Atlassian

1. Click atlassian-mcp-server in list
2. Verify detail page: transport type (SSE), URL (http://localhost:8085/sse)
3. Check access policy: verify Jira/Confluence/Bitbucket allowlists displayed
4. Run preflight check → verify results

### 6.4 Integrations Probe

1. Navigate to `/integrations`
2. Verify control plane shows connection status for:
   - Arc Reactor backend
   - Swagger MCP server
   - Atlassian MCP server
3. Check preflight results for each

### 6.5 Metric Ingestion

```bash
curl -sf -X POST http://localhost:18081/api/admin/metrics/ingest/mcp-health \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"serverName":"patrol-test","status":"HEALTHY","responseTimeMs":42}' \
  -w "\n%{http_code}"
```

Expected: 202

---

## Phase 7: Design Token Consistency

While navigating, verify these design tokens are applied consistently:

| Token | Expected Value |
|-------|---------------|
| --accent | #E0B85A (warm amber/gold) |
| --bg-root | #0C1017 (dark background) |
| --bg-surface | #111820 (cards/panels) |
| --text-primary | #F1F5F9 |
| --green | #34D399 (success badges) |
| --red | #F87171 (error badges) |

Check for:
- No raw hex colors outside of design tokens
- Consistent button styles (.btn-primary uses --accent)
- Font: Pretendard Variable for UI, IBM Plex Mono for data
- No horizontal scrollbar on any page at 1440x900

---

## Phase 8: Record Findings

Read `/Users/jinan/ai/arc-reactor/.improvements/admin-patrol-findings.md` and append:

```markdown
### YYYY-MM-DD HH:MM — Full Feature Patrol

**Stack Status:**
- backend (18081): UP/DOWN
- admin-ui (3001): UP/DOWN
- swagger-mcp (8081): UP/DOWN
- atlassian-mcp (8085): UP/DOWN

**API Endpoints:** X/21 categories responded OK
**UI Pages Checked:** X/25 pages loaded (including detail/nested pages)
**UI Features Verified:** X/Y interactions tested
**Redirect Routes:** X/7 verified

**Findings by Category:**

_Auth & Login:_
- [SEVERITY] description

_Dashboard:_
- [SEVERITY] description

_Issues:_
- [SEVERITY] description

_Approvals:_
- [SEVERITY] description

_Sessions:_
- [SEVERITY] description

_Feedback:_
- [SEVERITY] description

_MCP Servers:_
- [SEVERITY] description

_Personas:_
- [SEVERITY] description

_Prompt Studio:_
- [SEVERITY] description

_Safety Rules (Output Guard + Tool Policy):_
- [SEVERITY] description

_Documents:_
- [SEVERITY] description

_Audit:_
- [SEVERITY] description

_Platform Admin:_
- [SEVERITY] description

_Chat Inspector:_
- [SEVERITY] description

_Integrations:_
- [SEVERITY] description

_Scheduler:_
- [SEVERITY] description

_Intents:_
- [SEVERITY] description

_Metrics Ingestion:_
- [SEVERITY] description

_Prompt Lab / Experiments:_
- [SEVERITY] description

_Session User Detail:_
- [SEVERITY] description

_404 Not Found:_
- [SEVERITY] description

_Redirect Routes:_
- [SEVERITY] /prompts → /prompt-studio: OK/FAIL
- [SEVERITY] /mcp-security → /mcp-servers: OK/FAIL
- [SEVERITY] /output-guard → /safety-rules: OK/FAIL
- [SEVERITY] /tool-policy → /safety-rules: OK/FAIL
- [SEVERITY] /prompt-lab → /prompt-studio: OK/FAIL
- [SEVERITY] /tenant-admin → /platform-admin: OK/FAIL
- [SEVERITY] /proactive-channels → /integrations: OK/FAIL

_Workspace Mode:_
- [SEVERITY] description

_i18n:_
- [SEVERITY] description

_Responsive:_
- [SEVERITY] description

_Slack Integration:_
- Command test: OK/FAIL/SKIPPED
- Event test: OK/FAIL/SKIPPED
- Integrations Slack tab: OK/FAIL

_RAG & Documents:_
- [SEVERITY] description

_Semantic Cache:_
- Cache stats: OK/FAIL
- Cache invalidation: OK/FAIL

_Cross-Stack:_
- MCP registration: OK/MISSING
- Swagger preflight: OK/FAIL
- Atlassian preflight: OK/FAIL
- Metric ingestion: OK/FAIL

_Design Tokens:_
- [SEVERITY] description

_Performance:_
- Phase X took Y seconds (note if >60s)

---
```

Severity:
- `CRITICAL` — Feature broken, blank screen, server down, data loss risk
- `WARNING` — Partial rendering, slow load, missing data, UX degradation
- `INFO` — Minor cosmetic, missing i18n key, non-blocking
- `OK` — No issues (still log patrol completion)

---

## Phase 8.5: Slack Integration Test

If Slack is enabled (`ARC_REACTOR_SLACK_ENABLED=true`):

### 8.5.1 Slack Command Test

```bash
source ~/.arc-reactor-env
curl -s -X POST http://localhost:18081/api/slack/commands \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "command=%2Fask&text=patrol+health+check&user_id=U_PATROL&channel_id=$SLACK_CHANNEL_ID&response_url=https%3A%2F%2Fhttpbin.org%2Fpost&user_name=patrol-bot" \
  -w "\n%{http_code}"
```

Expected: 200 (immediate ACK)

### 8.5.2 Slack Event Test

```bash
curl -s -X POST http://localhost:18081/api/slack/events \
  -H "Content-Type: application/json" \
  -d '{"type":"event_callback","event":{"type":"app_mention","text":"<@BOT> patrol check","user":"U_PATROL","channel":"'$SLACK_CHANNEL_ID'"}}' \
  -w "\n%{http_code}"
```

Expected: 200

### 8.5.3 Navigate to Integrations Slack Tab

1. `browser_navigate` → `/integrations`
2. Click Slack tester tab
3. Verify command/event test forms are present and functional

---

## Phase 8.6: RAG & Semantic Cache Verification

### 8.6.1 RAG Document Management

1. Navigate to `/documents`
2. Verify 4 tabs present: Search, Register, Ingestion, Policy
3. **Search tab**: verify query input, topK slider, similarity threshold slider
4. **Register tab**: verify single doc form + batch add section
5. **Ingestion tab**: verify candidate list + approve/reject buttons
6. **Policy tab**: verify enabled toggle, form inputs, save/reset buttons

### 8.6.2 RAG Backend API

```bash
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/rag-ingestion/policy | jq '.'
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/rag-ingestion/candidates | jq 'length'
curl -sf -X POST http://localhost:18081/api/documents/search \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"query":"test","topK":3}' | jq 'length'
```

### 8.6.3 Semantic Cache Status

```bash
# Cache stats in platform health
curl -sf -H "Authorization: Bearer $TOKEN" http://localhost:18081/api/admin/platform/health | jq '{cacheExactHits, cacheSemanticHits, cacheMisses}'

# Cache invalidation endpoint
curl -sf -X POST http://localhost:18081/api/admin/platform/cache/invalidate \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

### 8.6.4 Missing Features (TODO — 개발 필요)

이 항목들은 아직 존재하지 않는 기능이다. 개발 완료 후 검증 항목으로 전환:
- [ ] `/rag-management` 페이지: 벡터 스토어 상태 (문서 수, 인덱스 크기, 임베딩 모델 정보)
- [ ] `/cache-management` 페이지: 캐시 설정 (TTL, threshold), 통계 (hit rate 추이), 선택적 무효화
- [ ] Backend: `GET /api/admin/vectorstore/stats` — 벡터 스토어 상태 엔드포인트
- [ ] Backend: `GET /api/admin/cache/stats` — 캐시 상세 통계 엔드포인트
- [ ] Backend: `POST /api/admin/cache/invalidate/pattern` — 패턴별 선택적 무효화

---

## Phase 9: Auto-Commit & Push (수정사항이 있을 때만)

패트롤 중 발견한 이슈를 코드로 수정한 경우:

```bash
cd /Users/jinan/ai/arc-reactor
CHANGES=$(git diff --stat HEAD)
UNTRACKED=$(git ls-files --others --exclude-standard | head -5)

if [ -n "$CHANGES" ] || [ -n "$UNTRACKED" ]; then
  echo "Changes detected — committing..."
  git add -A
  git commit -m "fix: admin patrol — 자동 수정 ($(date +%Y-%m-%d_%H:%M))

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
  git push origin main
  echo "Committed and pushed."
else
  echo "No changes to commit."
fi
```

**주의**: .improvements/ 파일(findings 등)은 커밋하지 않는다 (추적 파일).

---

## RULES

1. **READ-ONLY 기본**: 소스 코드는 버그 수정 외에 변경 금지
2. **Do NOT restart servers** — observe and report only
3. **Max 8 minutes** per patrol run (skip remaining if over time)
4. **Skip gracefully**: If a step fails, log it and move to next step
5. **Always write findings**: Even if most checks failed, record what you observed
6. **No CRUD writes on production data**: Do not create/update/delete personas, templates, jobs, rules, etc. Only verify the UI forms/buttons are present and functional
7. **Exception**: Metric ingestion test POST, Slack test commands are allowed
8. **버그 수정 시**: 수정 → 컴파일 확인 → Phase 9 자동 커밋/푸시
9. **느린 작업 기록**: 각 Phase 소요 시간 측정, 5분 초과 시 findings에 기록하여 다음 실행에서 최적화

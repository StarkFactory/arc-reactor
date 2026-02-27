# Migration Guide: Arc Reactor 3.x → 4.x

This guide covers all breaking changes, configuration updates, and new features introduced across the 4.x release line. A developer familiar with 3.x should be able to complete an upgrade in under an hour using this guide as a checklist.

## Overview

Arc Reactor 4.x is a significant release that builds on the 3.x runtime without breaking REST API paths. The major additions are:

- **Enterprise guard system**: 5-layer input/output guard pipeline aligned with OWASP LLM Top 10 (2025)
- **Admin control plane** (`arc-admin` module): tenant management, metrics ingestion, dashboards, alerting, and OpenTelemetry tracing
- **Hierarchical memory**: LLM-based conversation summarization for long-running sessions
- **Human-in-the-loop (HITL)**: approval pipeline for write-tool confirmation
- **Prompt Lab**: automated prompt evaluation and optimization
- **Intent classification**: route requests to specialized agent profiles
- **PostgreSQL-first production stance**: in-memory fallbacks removed for production persistence paths
- **Executor refactor**: `SpringAiAgentExecutor` complexity reduced via extracted coordinator classes

The 4.x line spans four releases: `4.0.0`, `4.0.1` (patch), `4.1.0`, and `4.2.x`.

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21 (required, enforced at build time) |
| Spring Boot | 3.5.x |
| Kotlin | 2.3.10 |
| Spring AI | 1.1.2 |
| MCP SDK | 1.0.0 (upgraded from 0.17.2 in 3.x) |
| PostgreSQL | Required for production persistence paths |

---

## Breaking Changes

### 1. PostgreSQL is now required for production persistence

In 3.x, all stores (memory, conversations, MCP) had in-memory fallbacks and fell back silently when no `DataSource` was present.

In 4.x, production persistence paths enforce PostgreSQL. The in-memory fallback is removed for stores that require durability. Attempting to run in production without a `DataSource` will result in a startup failure.

**Before (3.x):**
```yaml
# No datasource = silently used in-memory stores
spring:
  datasource: # optional
```

**After (4.x):**
```yaml
# PostgreSQL is expected for production
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: ${DB_PASSWORD}
  flyway:
    enabled: true
```

For local development or testing, you can override this behavior:
```yaml
arc:
  reactor:
    postgres:
      required: false
```

Or via the test Gradle flag:
```bash
./gradlew test -Pdb=true   # Include PostgreSQL/PGVector/Flyway deps
```

### 2. MCP SDK upgraded from 0.17.2 to 1.0.0

The MCP SDK received a major version bump. The key behavioral change is that `StdioClientTransport` now requires `McpJsonMapper` explicitly.

**Before (3.x with 0.17.2):**
- HTTP transport was partially documented as a future option
- SDK surface was unstable

**After (4.x with 1.0.0):**
- HTTP transport remains **not supported**; use SSE for remote servers
- `StdioClientTransport` requires `McpJsonMapper` — if you instantiate this directly, pass the mapper explicitly
- MCP server registration via REST API is unchanged

If you have any code that manually constructs `StdioClientTransport`, update it to pass `McpJsonMapper`. If you only use the REST registration API (`POST /api/mcp/servers`), no changes are needed.

### 3. `AgentProperties` split into two files

In 3.9.1+, the large `AgentProperties.kt` was split. Policy-specific and feature-toggle properties were extracted to `AgentPolicyAndFeatureProperties.kt`.

**If you reference internal property classes directly** (uncommon in application code), update your imports:

| Class | Old Location | New Location |
|-------|-------------|-------------|
| `ApprovalProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `ToolPolicyProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `MultimodalProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `RagProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `OutputGuardProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `IntentProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `SchedulerProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `BoundaryProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |
| `MemoryProperties` | `AgentProperties.kt` | `AgentPolicyAndFeatureProperties.kt` |

The top-level `AgentProperties` data class and its `@ConfigurationProperties(prefix = "arc.reactor")` binding are unchanged — all YAML keys remain identical.

### 4. `McpToolCallback` extracted from `McpManager`

`McpToolCallback` was extracted from `McpManager.kt` into its own file. If you import `McpToolCallback` directly, update the import path. If you only interact with the MCP system through the REST API or the `AgentExecutor`, no change is needed.

### 5. `AssistantMessage` construction changed

The `AssistantMessage` constructor is now protected in Spring AI 1.1.x. Use the builder instead.

**Before (3.x):**
```kotlin
val msg = AssistantMessage(content = "Hello", toolCalls = emptyList())
```

**After (4.x):**
```kotlin
val msg = AssistantMessage.builder()
    .content("Hello")
    .toolCalls(emptyList())
    .build()
```

This affects any custom code that constructs `AssistantMessage` directly (e.g., custom hooks or test utilities). `AgentTestFixture` helper methods handle this internally.

### 6. `arc-core` bootJar disabled; use `arc-app` for runtime

`arc-core` is now a library module — its `bootJar` task is disabled. The executable runtime moved to `arc-app`.

**Before (3.x):**
```bash
./gradlew :arc-core:bootRun
./gradlew :arc-core:bootJar
```

**After (4.x):**
```bash
./gradlew :arc-app:bootRun
./gradlew :arc-app:bootJar
```

If you have CI/CD pipelines or Dockerfiles that reference `:arc-core` as the run target, update them to `:arc-app`.

### 7. Provider API keys: no empty defaults in `application.yml`

API keys for LLM providers must be set via environment variables only. Do not declare them with empty defaults in `application.yml`.

**Before (3.x — incorrect pattern that may have worked):**
```yaml
spring:
  ai:
    openai:
      api-key: ""  # empty default
```

**After (4.x — required pattern):**
```yaml
# application.yml — no key declaration
# Set keys via environment variables only:
# GEMINI_API_KEY
# SPRING_AI_OPENAI_API_KEY
# SPRING_AI_ANTHROPIC_API_KEY
```

---

## Configuration Changes

### New properties added in 4.x

All new properties are opt-in with safe defaults. Your existing `application.yml` continues to work without changes.

#### Guard system extensions (new in 4.x)

```yaml
arc:
  reactor:
    guard:
      # Existing (unchanged)
      enabled: true
      rate-limit-per-minute: 10
      injection-detection-enabled: true

      # New in 4.x
      rate-limit-per-hour: 100              # hourly rate limit (default: 100)
      unicode-normalization-enabled: true    # NFKC + zero-width strip (default: true)
      max-zero-width-ratio: 0.1             # rejection threshold for zero-width chars
      canary-token-enabled: false           # system prompt leakage detection (opt-in)
      canary-seed: "arc-reactor-canary"     # override per deployment for uniqueness
      tool-output-sanitization-enabled: false  # indirect injection defense (opt-in)
      audit-enabled: true                   # per-stage latency + audit trail
      topic-drift-enabled: false            # Crescendo attack defense (opt-in)
      tenant-rate-limits:                   # per-tenant rate limits (optional)
        tenant-a:
          per-minute: 20
          per-hour: 200
```

#### Output guard (new in 4.x)

```yaml
arc:
  reactor:
    output-guard:
      enabled: false              # opt-in
      pii-masking-enabled: true
      dynamic-rules-enabled: true
      dynamic-rules-refresh-ms: 3000
      custom-patterns:
        - pattern: "(?i)internal\\s+use\\s+only"
          action: REJECT
          name: "Internal Document"
```

#### Hierarchical memory summarization (new in 4.1.x)

```yaml
arc:
  reactor:
    memory:
      summary:
        enabled: false            # opt-in
        trigger-message-count: 20
        recent-message-count: 10
        llm-model: gemini-2.0-flash   # null = use default provider
        max-narrative-tokens: 500
```

#### Human-in-the-loop approval (new in 4.x)

```yaml
arc:
  reactor:
    approval:
      enabled: false              # opt-in
      timeout-ms: 300000          # 5 minutes
      resolved-retention-ms: 604800000  # 7 days
      tool-names:
        - delete_order
        - process_refund
```

#### Input/output boundary policy (new in 4.x)

```yaml
arc:
  reactor:
    boundaries:
      input-min-chars: 1
      input-max-chars: 5000       # was guard.max-input-length in some 3.x docs
      system-prompt-max-chars: 0  # disabled by default
      output-min-chars: 0
      output-max-chars: 0
      output-min-violation-mode: warn   # warn | retry_once | fail
```

#### Intent classification (new in 3.4.0, refined in 4.x)

```yaml
arc:
  reactor:
    intent:
      enabled: false              # opt-in
      confidence-threshold: 0.6
      llm-model: null             # null = default provider
      rule-confidence-threshold: 0.8
      blocked-intents: []
```

#### Prompt Lab (new in 4.2.x)

```yaml
arc:
  reactor:
    prompt-lab:
      enabled: false              # opt-in
      schedule:
        enabled: false
        cron: "0 0 2 * * *"      # daily at 2 AM
        template-ids: []          # empty = all templates
```

### Property renames

No property keys were renamed between 3.x and 4.x. All `arc.reactor.*` keys are backward-compatible.

---

## API Changes

### No endpoint path changes

REST API paths are unchanged. No migration is required for clients.

| Endpoint | Path | Status |
|----------|------|--------|
| Chat | `POST /api/chat` | Unchanged |
| Streaming | `POST /api/chat/stream` | Unchanged |
| Sessions | `GET/DELETE /api/sessions` | Unchanged |
| Models | `GET /api/models` | Unchanged |
| Personas | `/api/personas` | Unchanged |
| Prompt Templates | `/api/prompt-templates` | Unchanged |
| MCP Servers | `/api/mcp/servers` | Unchanged |
| Auth | `/api/auth` | Unchanged (when `auth.enabled=true`) |
| Documents | `/api/documents` | Unchanged (when `rag.enabled=true`) |

### New API versioning header (4.0.0+)

Arc Reactor now responds with API version headers. Sending `X-Arc-Api-Version` in requests is optional (defaults to `v1`).

```http
# Optional request header
X-Arc-Api-Version: v1

# Response headers added in 4.x
X-Arc-Api-Version: v1
X-Arc-Api-Supported-Versions: v1
```

No client changes are required unless you have strict header validation.

### New endpoints added in 4.x

These are additions; they do not change existing behavior.

| New Endpoint | Module | Condition |
|-------------|--------|-----------|
| `POST /api/prompt-lab/experiments` | `arc-core` | `prompt-lab.enabled=true` |
| `GET /api/prompt-lab/experiments/{id}/report` | `arc-core` | `prompt-lab.enabled=true` |
| `POST /api/prompt-lab/auto-optimize` | `arc-core` | `prompt-lab.enabled=true` |
| `/api/intents` (CRUD) | `arc-web` | `intent.enabled=true` |
| `/api/approvals` | `arc-web` | `approval.enabled=true` |
| `/api/scheduler` | `arc-web` | `scheduler.enabled=true` |
| `/api/output-guard/rules` | `arc-web` | `output-guard.enabled=true` |
| `/api/admin/metrics` | `arc-admin` | Admin module active |
| `/api/admin/tenants` | `arc-admin` | Admin module active |
| `/api/admin/alerts` | `arc-admin` | Admin module active |

---

## Dependency Changes

### Gradle

Update your `build.gradle.kts` (or `build.gradle`) to reflect the new versions:

```kotlin
// Root build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "3.5.9" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

// If you pin coroutines version explicitly
val coroutinesVersion = "1.10.2"
```

**Spring AI version** in submodule `build.gradle.kts`:
```kotlin
val springAiVersion = "1.1.2"

dependencies {
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
}
```

**MCP SDK** upgraded from `0.17.2` to `1.0.0`:
```kotlin
// Before (3.x)
implementation("io.modelcontextprotocol.sdk:mcp:0.17.2")

// After (4.x)
implementation("io.modelcontextprotocol.sdk:mcp:1.0.0")
```

**MockK** upgraded:
```kotlin
// Before
testImplementation("io.mockk:mockk:1.14.5")

// After
testImplementation("io.mockk:mockk:1.14.9")
```

### Maven (if applicable)

```xml
<!-- Spring Boot parent -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.9</version>
</parent>

<!-- Spring AI BOM -->
<dependencyManagement>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>1.1.2</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>

<!-- MCP SDK -->
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Feature Migrations

### Enabling the Admin Module (`arc-admin`)

The `arc-admin` module provides a control plane for tenant management, cost tracking, metrics, SLO dashboards, alerting, and OpenTelemetry tracing. It is a separate Gradle module and must be added as a dependency.

**Step 1: Add dependency to `arc-app/build.gradle.kts`:**
```kotlin
dependencies {
    implementation(project(":arc-admin"))
}
```

**Step 2: Add PostgreSQL and JDBC dependencies (required):**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("org.postgresql:postgresql")
```

**Step 3: Configure Flyway and datasource:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: ${DB_PASSWORD}
  flyway:
    enabled: true
```

**Step 4: Admin access**

Admin APIs follow the existing RBAC model. When `auth.enabled=false`, all requests are treated as admin. When auth is enabled, the `ADMIN` role is required.

### Enabling Hierarchical Memory Summarization

Hierarchical memory compresses old conversation turns into a structured narrative while keeping recent messages verbatim. This prevents context loss in long sessions without truncating everything.

**Requirements:** PostgreSQL (`JdbcConversationSummaryStore` is the production store).

```yaml
arc:
  reactor:
    memory:
      summary:
        enabled: true
        trigger-message-count: 20   # summarize after 20 messages
        recent-message-count: 10    # keep last 10 verbatim
        llm-model: gemini-2.0-flash # model for summarization
        max-narrative-tokens: 500
```

No code changes are needed — the `ConversationManager` picks up the configuration automatically.

### Enabling Human-in-the-Loop (HITL) Approval

HITL pauses tool execution for listed tools and waits for human confirmation before proceeding.

```yaml
arc:
  reactor:
    approval:
      enabled: true
      timeout-ms: 300000     # 5-minute approval window
      tool-names:
        - delete_record
        - send_email
```

Approval requests are managed via `POST /api/approvals` and `PUT /api/approvals/{id}`.

For custom approval routing, implement the `ToolApprovalPolicy` interface and register it as a Spring bean — it takes precedence over `tool-names`.

### Enabling Prompt Lab

Prompt Lab automates prompt optimization by analyzing negative feedback, generating improved candidates, and running controlled experiments.

**Requirements:** `FeedbackStore` (from existing feedback feature), `PromptTemplateStore`.

```yaml
arc:
  reactor:
    prompt-lab:
      enabled: true
      schedule:
        enabled: true
        cron: "0 0 2 * * *"       # daily at 2 AM
        template-ids:
          - "your-template-id"
```

Access the REST API at `/api/prompt-lab/experiments` (admin only). See [Prompt Lab documentation](../architecture/prompt-lab.md) for the full evaluation pipeline reference.

### Enabling Multi-Agent (Supervisor Pattern)

Multi-agent was introduced in 1.0.0 but refined in 4.x. No configuration changes are required — it is a code-level pattern using the `MultiAgent` DSL.

```kotlin
val result = MultiAgent.supervisor()
    .node("order") {
        systemPrompt = "Handle order-related tasks"
        description = "Order lookup, modification, cancellation"
        tools = listOf(orderLookupTool)
    }
    .node("refund") {
        systemPrompt = "Handle refund tasks"
        description = "Refund requests, status checks"
        tools = listOf(refundTool)
    }
    .execute(command) { node ->
        SpringAiAgentExecutor(
            chatModel = chatModel,
            properties = properties,
            toolCallbacks = node.tools
        )
    }
```

See [Multi-Agent Guide](../architecture/multi-agent.md) for the full pattern reference.

### Enabling RAG

RAG configuration is unchanged from 3.x. If you were already using it, no migration is needed.

```yaml
arc:
  reactor:
    rag:
      enabled: true
      top-k: 10
      similarity-threshold: 0.7
      rerank-enabled: true
```

Build with PostgreSQL/PGVector:
```bash
./gradlew bootJar -Pdb=true
```

### Enabling Intent Classification

Intent classification routes requests to specialized agent profiles based on content analysis.

```yaml
arc:
  reactor:
    intent:
      enabled: true
      confidence-threshold: 0.6
```

Register intent definitions via `POST /api/intents` (admin only). Each intent maps to a profile that can override the model, system prompt, max tool calls, and allowed tools.

### Enabling the Enterprise Guard System

The guard system received a major upgrade in 4.x with five defense layers. Existing `guard.enabled=true` configurations continue to work — the new layers are all opt-in.

```yaml
arc:
  reactor:
    guard:
      enabled: true                         # L0: always on
      unicode-normalization-enabled: true   # L0: NFKC normalization
      injection-detection-enabled: true     # L0: 28 regex patterns
      canary-token-enabled: true            # L2: system prompt leakage (opt-in)
      tool-output-sanitization-enabled: true # L3: indirect injection defense (opt-in)
      topic-drift-enabled: true             # L1: Crescendo attack defense (opt-in)
    output-guard:
      enabled: true                         # post-execution output validation
      pii-masking-enabled: true
```

Custom guard stages still register as `@Component` beans implementing `GuardStage` (input) or `OutputGuardStage` (output). Use `order >= 10` for custom stages to avoid conflicts with built-in stages (0–9).

---

## Removed Features

None of the 3.x features were removed in 4.x. The changes are additive, with the exception of the in-memory store fallback for production persistence paths (see Breaking Changes section).

---

## Step-by-Step Upgrade Checklist

Work through these steps in order. Commit after each group passes.

### Phase 1: Build and dependency updates

1. Update Kotlin plugin version to `2.3.10` in root `build.gradle.kts`
2. Update Spring Boot plugin version to `3.5.9`
3. Update Spring AI BOM version to `1.1.2` in submodule build files
4. Update MCP SDK version from `0.17.2` to `1.0.0`
5. Update MockK to `1.14.9` in test dependencies
6. Run: `./gradlew compileKotlin compileTestKotlin` — fix any compilation errors (target: 0 warnings)
7. Update any `./gradlew :arc-core:bootRun` references to `./gradlew :arc-app:bootRun`

### Phase 2: PostgreSQL and datasource

8. Add PostgreSQL datasource configuration to `application.yml` (or `application-prod.yml`)
9. Verify Flyway migrations run cleanly: `./gradlew :arc-app:bootRun` with a test database
10. If running tests without PostgreSQL: add `arc.reactor.postgres.required=false` to test application properties, or use the H2 test profile
11. Run: `./gradlew test` — all tests must pass

### Phase 3: API key configuration

12. Audit `application.yml` for any LLM provider keys declared with empty defaults
13. Move them to environment variables: `GEMINI_API_KEY`, `SPRING_AI_OPENAI_API_KEY`, `SPRING_AI_ANTHROPIC_API_KEY`
14. Remove empty `api-key: ""` declarations from YAML

### Phase 4: Code changes (if applicable)

15. If you construct `AssistantMessage` directly: migrate to `AssistantMessage.builder().content(...).build()`
16. If you import `McpToolCallback` directly: verify the import path resolves after the extraction
17. If you import policy property classes (e.g., `ApprovalProperties`): verify imports resolve from `AgentPolicyAndFeatureProperties.kt`
18. If you create `StdioClientTransport` manually: pass `McpJsonMapper` explicitly

### Phase 5: CI/CD and tooling

19. Update CI pipelines: replace `:arc-core` boot tasks with `:arc-app`
20. Update Dockerfile `COPY` or `ENTRYPOINT` references if they point to `arc-core` boot jar
21. Verify integration test command: `./gradlew :arc-core:test :arc-web:test -PincludeIntegration --tests "com.arc.reactor.integration.*"`
22. Add external integration gate to CI when needed: `-PincludeExternalIntegration`

### Phase 6: Opt-in features (as needed)

23. Admin module: add `arc-admin` dependency and PostgreSQL JDBC config
24. Hierarchical memory: enable `arc.reactor.memory.summary.enabled=true` with PostgreSQL
25. HITL approval: enable `arc.reactor.approval.enabled=true` and list tool names
26. Prompt Lab: enable `arc.reactor.prompt-lab.enabled=true` (requires feedback + template stores)
27. Output guard: enable `arc.reactor.output-guard.enabled=true` for post-response validation
28. Intent classification: enable `arc.reactor.intent.enabled=true` and define intents via API

### Phase 7: Final validation

29. Run full test suite: `./gradlew test`
30. Run integration tests: `./gradlew test -PincludeIntegration`
31. Smoke test `POST /api/chat` and `POST /api/chat/stream` against a running instance
32. Verify the API version header appears in responses: `X-Arc-Api-Version: v1`

---

## Troubleshooting

**Startup fails with "DataSource required" error**

Add PostgreSQL datasource config or set `arc.reactor.postgres.required=false` for development.

**`AssistantMessage` constructor is inaccessible**

Use the builder: `AssistantMessage.builder().content(text).toolCalls(calls).build()`.

**MCP server registration fails after upgrade**

Verify you are not using HTTP transport — only SSE and STDIO are supported with MCP SDK 1.0.0.

**Tests fail with missing bean for `ConversationSummaryStore`**

Add `arc.reactor.memory.summary.enabled=false` to your test properties, or add the H2 test dependency and enable the `db` profile.

**`bootRun` task not found on `:arc-core`**

Use `./gradlew :arc-app:bootRun` — the boot task moved to the `arc-app` assembly module.

---

## Related Documentation

- [Architecture Guide](../architecture/architecture.md)
- [Guard & Hook System](../architecture/guard-hook.md)
- [Multi-Agent Guide](../architecture/multi-agent.md)
- [Prompt Lab Guide](../architecture/prompt-lab.md)
- [Hierarchical Memory](../architecture/memory-rag.md)
- [Module Layout](../architecture/module-layout.md)
- [Configuration Quickstart](../getting-started/configuration-quickstart.md)
- [Deployment Guide](../getting-started/deployment.md)
- [v4.0.0 Release Notes](v4.0.0.md)
- [v4.1.0 Release Notes](v4.1.0.md)

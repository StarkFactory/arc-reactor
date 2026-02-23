# Hierarchical Conversation Memory

## One-Line Summary

**Compresses old conversation messages into structured facts + narrative summary while preserving recent messages verbatim, preventing critical context loss in long conversations.**

---

## Problem

The default `takeLast(maxConversationTurns * 2)` strategy drops early messages after ~5-10 minutes of active conversation. Key information like order numbers, agreed conditions, and user requirements is permanently lost from the LLM's context.

With a 128K token context window, the default strategy utilizes only 2-10% of available capacity.

## Solution: 3-Layer Context

```
┌─────────────────────────────────────────────────┐
│  What the LLM receives                          │
│                                                 │
│  [System Prompt]              ← persona/role    │
│  [SystemMessage: Facts]       ← key-value pairs │
│  [SystemMessage: Narrative]   ← conversation flow│
│  [Recent N messages verbatim] ← exact originals │
│  [Current UserMessage]        ← current turn    │
│                                                 │
│  → MessageTrimmer adjusts to fit token budget   │
└─────────────────────────────────────────────────┘
```

| Layer | What it preserves | Format |
|-------|-------------------|--------|
| **Facts** | Numbers, entities, conditions, decisions | `- order_id: #1234` key-value pairs |
| **Narrative** | Conversation flow, tone, agreements | Free-text paragraph |
| **Recent Window** | Last N messages exactly as spoken | Original messages |

---

## When It Activates

```
message count ≤ triggerMessageCount  →  takeLast (existing behavior)
message count > triggerMessageCount  →  3-layer hierarchical mode
```

The feature is **disabled by default**. Enable via configuration:

```yaml
arc:
  reactor:
    memory:
      summary:
        enabled: true
        trigger-message-count: 20   # switch to hierarchical above this
        recent-message-count: 10    # keep this many messages verbatim
        llm-model: gemini-2.0-flash # lightweight model for summarization
        max-narrative-tokens: 500   # narrative length limit
```

---

## Architecture

### Component Overview

```
ConversationManager
  ├── loadHistory()     ← decides takeLast vs hierarchical
  ├── saveHistory()     ← triggers async summarization
  │
  ├── ConversationSummaryService (LLM-based)
  │     └── Single LLM call → JSON { facts, narrative }
  │
  └── ConversationSummaryStore
        ├── InMemoryConversationSummaryStore (default)
        └── JdbcConversationSummaryStore (@Primary with DataSource)
```

### Summarization Timing: Async + Lazy Fallback

```
saveHistory() called
  ├── Save messages (synchronous)
  └── Launch async summarization (background)
       └── Failure is silently ignored

loadHistory() called
  ├── Cached summary exists & fresh  →  use immediately
  ├── No cache or stale              →  synchronous generation (lazy fallback)
  └── Summarization fails            →  takeLast fallback (existing behavior)
```

This dual strategy ensures:
- **Most requests** hit the pre-computed cache (no latency)
- **First request** after threshold may incur 1-3s summarization cost
- **Failures** never break the conversation — graceful degradation guaranteed

### Data Flow

```
[30 messages in MemoryStore]
     │
     ├── messages[0..19]  → LLM summarization
     │     ↓
     │   Facts: [order_id: #1234, status: approved, amount: $50]
     │   Narrative: "Customer requested order cancellation and agreed to refund terms"
     │     ↓
     │   Stored in ConversationSummaryStore (cached)
     │
     └── messages[20..29] → kept verbatim (recent window)
           ↓
         [SystemMessage: Facts] + [SystemMessage: Narrative] + [10 recent messages]
           ↓
         Passed to LLM as conversation context
```

---

## Safety Mechanisms

| Scenario | Behavior |
|----------|----------|
| LLM summarization fails | Falls back to `takeLast` (existing behavior) |
| Empty summary (no facts, blank narrative) | Falls back to `takeLast` |
| `recentMessageCount` > total messages | Returns all messages, skips summarization |
| Duplicate summarization for same session | Previous job cancelled, only latest runs |
| Application shutdown | `DisposableBean.destroy()` cancels async scope |
| Session deleted | Summary store cascade-deleted via `SessionController` |
| MessageTrimmer encounters summary | Leading SystemMessages are protected from trimming |
| Summary-of-summary drift | Never done — always re-summarizes from original messages |

### Original Messages Are Never Deleted

Summaries are a **view layer** over the original messages. The MemoryStore retains all messages regardless of summarization status. If the summary feature is disabled later, conversations continue working with `takeLast`.

---

## LLM Prompt Design

A single LLM call extracts both facts and narrative:

```
You are a conversation summarizer. Extract structured facts and a brief
narrative summary from the conversation below.

Return JSON:
{
  "facts": [
    {"key": "...", "value": "...", "category": "ENTITY|DECISION|CONDITION|STATE|NUMERIC|GENERAL"}
  ],
  "narrative": "2-3 sentence summary of conversation flow and tone"
}

## Existing facts (merge, update, or remove as needed):
- order_id: #1234

## Conversation:
[user]: I'd like to cancel order #1234
[assistant]: I can help with that. Your order totals $50...
...
```

### Fact Categories

| Category | Example |
|----------|---------|
| `ENTITY` | `order_id: #1234`, `customer_name: John` |
| `DECISION` | `refund_approved: yes` |
| `CONDITION` | `refund_condition: within 30 days` |
| `STATE` | `order_status: cancelled` |
| `NUMERIC` | `refund_amount: $50.00` |
| `GENERAL` | `topic: order cancellation` |

---

## Database Schema

Flyway migration `V20__create_conversation_summaries.sql`:

```sql
CREATE TABLE IF NOT EXISTS conversation_summaries (
    session_id       VARCHAR(255) PRIMARY KEY,
    narrative        TEXT         NOT NULL,
    facts_json       TEXT         NOT NULL DEFAULT '[]',
    summarized_up_to INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

- `facts_json`: Jackson-serialized `List<StructuredFact>`
- `summarized_up_to`: Message index up to which the summary covers (for staleness detection)
- UPSERT semantics on save (INSERT or UPDATE by session_id)

---

## Auto-Configuration

```
@ConditionalOnProperty("arc.reactor.memory.summary.enabled", havingValue = "true")
└── MemorySummaryConfiguration
      ├── ConversationSummaryService  (LlmConversationSummaryService)
      ├── ConversationSummaryStore    (InMemoryConversationSummaryStore)
      │
      └── @ConditionalOnClass(JdbcTemplate) + @ConditionalOnProperty("spring.datasource.url")
            └── JdbcConversationSummaryStore (@Primary, overrides InMemory)
```

Follows the same pattern as other optional features (Intent Classification, RAG, Auth).

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `arc.reactor.memory.summary.enabled` | `false` | Enable hierarchical memory |
| `arc.reactor.memory.summary.trigger-message-count` | `20` | Switch from takeLast to hierarchical above this count |
| `arc.reactor.memory.summary.recent-message-count` | `10` | Number of recent messages to keep verbatim |
| `arc.reactor.memory.summary.llm-model` | `gemini-2.0-flash` | Model used for summarization |
| `arc.reactor.memory.summary.max-narrative-tokens` | `500` | Maximum narrative length |

---

## Interaction with MessageTrimmer

The `ConversationMessageTrimmer` runs **after** `loadHistory()` assembles the context. It respects hierarchical memory by:

1. **Protecting leading SystemMessages** — Facts and Narrative SystemMessages at the start of the message list are never trimmed
2. **Token budget includes SystemMessages** — Their token cost is counted toward the context window budget
3. **Trimming starts after SystemMessages** — Old user/assistant pairs are removed from after the SystemMessages, preserving the summary context

```
Before trimming:  [Facts] [Narrative] [old-user] [old-asst] [recent-user] [recent-asst] [current-user]
After trimming:   [Facts] [Narrative] [recent-user] [recent-asst] [current-user]
                   ↑ protected         ↑ trimmed from here
```

---

## Reference Code

- [`memory/ConversationManager.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/ConversationManager.kt) — 3-layer history assembly + async trigger
- [`memory/summary/ConversationSummaryModels.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/summary/ConversationSummaryModels.kt) — Data models (StructuredFact, ConversationSummary)
- [`memory/summary/ConversationSummaryStore.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/summary/ConversationSummaryStore.kt) — Store interface
- [`memory/summary/InMemoryConversationSummaryStore.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/summary/InMemoryConversationSummaryStore.kt) — Default in-memory implementation
- [`memory/summary/JdbcConversationSummaryStore.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/summary/JdbcConversationSummaryStore.kt) — JDBC/PostgreSQL implementation
- [`memory/summary/LlmConversationSummaryService.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/memory/summary/LlmConversationSummaryService.kt) — LLM-based summarization
- [`autoconfigure/ArcReactorMemorySummaryConfiguration.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorMemorySummaryConfiguration.kt) — Bean registration
- [`agent/impl/ConversationMessageTrimmer.kt`](../../../../arc-core/src/main/kotlin/com/arc/reactor/agent/impl/ConversationMessageTrimmer.kt) — Token-aware context trimming with SystemMessage protection
- [`V20__create_conversation_summaries.sql`](../../../../arc-core/src/main/resources/db/migration/V20__create_conversation_summaries.sql) — Database migration

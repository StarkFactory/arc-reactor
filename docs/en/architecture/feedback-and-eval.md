# Feedback and Evaluation

> This document explains Arc Reactor's Feedback system -- how user feedback is collected, auto-enriched with execution metadata, and exported for offline evaluation.

## One-Line Summary

**Collect thumbs-up/thumbs-down feedback on agent responses, auto-enrich with execution metadata, and export in eval-testing schema format.**

---

## Why Is It Needed?

Without a feedback system, there is no structured way to measure agent quality:

```
User: "This answer was wrong"  →  Lost in chat history
User: "Great response!"        →  No record anywhere
```

Problems:
- **No quality signal**: No way to know which responses are good or bad
- **No eval data**: No structured dataset for offline evaluation
- **No metadata correlation**: Cannot link feedback to specific models, prompts, or tools used
- **Manual data collection**: Building eval datasets requires manual effort

With the Feedback system:

```
POST /api/feedback  →  Stored with execution metadata  →  GET /api/feedback/export  →  Eval pipeline
```

---

## Architecture

```
Agent Execution
    │
    ▼
┌─ FeedbackMetadataCaptureHook (order=250) ──────────────────────────┐
│  AfterAgentComplete:                                                │
│    Cache execution metadata in memory:                              │
│    - runId, userId, userPrompt, agentResponse                      │
│    - toolsUsed, durationMs, sessionId, templateId                  │
│    TTL: 1 hour, Max: 10,000 entries                                │
└─────────────────────────────────────────────────────────────────────┘
    │
    │  (User submits feedback within 1 hour)
    │
    ▼
┌─ FeedbackController ───────────────────────────────────────────────┐
│  POST /api/feedback                                                 │
│    1. Parse rating (THUMBS_UP / THUMBS_DOWN)                       │
│    2. If runId provided → metadataCaptureHook.get(runId)           │
│    3. Auto-enrich: explicit values > cached metadata > empty       │
│    4. feedbackStore.save(feedback)                                  │
│    5. Return 201 Created                                           │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ FeedbackStore ────────────────────────────────────────────────────┐
│  InMemoryFeedbackStore (default)                                    │
│    └─ ConcurrentHashMap, sorted by timestamp desc                  │
│  JdbcFeedbackStore (PostgreSQL)                                     │
│    └─ feedback table, dynamic WHERE clause for filters             │
└─────────────────────────────────────────────────────────────────────┘
    │
    ▼
  GET /api/feedback/export  →  Eval-testing schema JSON
```

---

## Data Model

```kotlin
data class Feedback(
    val feedbackId: String,      // UUID
    val query: String,           // User's original prompt
    val response: String,        // Agent response
    val rating: FeedbackRating,  // THUMBS_UP or THUMBS_DOWN
    val timestamp: Instant,      // Submission time
    val comment: String?,        // Free-text comment
    val sessionId: String?,      // Conversation session ID
    val runId: String?,          // Agent execution run ID
    val userId: String?,         // User who submitted
    val intent: String?,         // Classified intent
    val domain: String?,         // Business domain (e.g. "order", "refund")
    val model: String?,          // LLM model used
    val promptVersion: Int?,     // Prompt template version number
    val toolsUsed: List<String>?, // Tools invoked during execution
    val durationMs: Long?,       // Total execution duration (ms)
    val tags: List<String>?,     // Arbitrary tags for filtering
    val templateId: String?      // Prompt template ID
)

enum class FeedbackRating {
    THUMBS_UP, THUMBS_DOWN
}
```

---

## Auto-Enrichment

When a user submits feedback with a `runId`, the controller automatically enriches the feedback with cached execution metadata from `FeedbackMetadataCaptureHook`.

### Enrichment Priority

For each field, the following priority applies:

1. **Explicit request value** (non-blank) -- always wins
2. **Cached metadata** from hook -- used when request value is blank/null
3. **Empty default** -- used when neither is available

Fields enriched from cache:
- `query` (from `userPrompt`)
- `response` (from `agentResponse`)
- `toolsUsed`
- `durationMs`
- `sessionId`
- `templateId`

### FeedbackMetadataCaptureHook

This `AfterAgentCompleteHook` runs at order 250 (after webhooks) and caches execution metadata in memory:

- **TTL**: 1 hour (entries older than this are evicted)
- **Max entries**: 10,000 (oldest evicted when exceeded)
- **Eviction throttle**: At most once per 30 seconds
- **Fail-open**: Never blocks agent response delivery

---

## Store Implementations

### InMemoryFeedbackStore (Default)

- Thread-safe via `ConcurrentHashMap`
- Supports all filter combinations (rating, time range, intent, sessionId, templateId)
- Not persistent -- data lost on server restart

### JdbcFeedbackStore (PostgreSQL)

- Persistent storage in the `feedback` table
- Dynamic WHERE clause construction for combined filters
- List fields (`toolsUsed`, `tags`) stored as JSON TEXT columns
- Flyway migrations: V17 (base table) + V24 (templateId column)

---

## REST API

### Submit Feedback (Any User)
```
POST /api/feedback
Content-Type: application/json

{
  "rating": "thumbs_up",
  "query": "How do I reset my password?",
  "response": "Go to Settings > Security > Reset Password...",
  "comment": "Very helpful!",
  "runId": "run-abc-123",
  "sessionId": "session-xyz",
  "intent": "account_help",
  "domain": "account",
  "tags": ["helpful", "accurate"]
}
```

Response: `201 Created`

Minimal submission (with auto-enrichment via runId):
```json
{
  "rating": "thumbs_down",
  "runId": "run-abc-123",
  "comment": "The answer was incorrect"
}
```

### List Feedback (Admin)
```
GET /api/feedback?rating=thumbs_down&from=2024-01-01T00:00:00Z&to=2024-12-31T23:59:59Z&intent=refund&offset=0&limit=50
```

All filter parameters are optional and AND-combined:
- `rating`: `thumbs_up` or `thumbs_down`
- `from` / `to`: ISO 8601 timestamps
- `intent`: Exact match
- `sessionId`: Exact match
- `templateId`: Exact match

### Export for Eval (Admin)
```
GET /api/feedback/export
```

Response:
```json
{
  "version": 1,
  "exportedAt": "2024-12-25T09:00:00Z",
  "source": "arc-reactor",
  "items": [
    {
      "feedbackId": "...",
      "query": "...",
      "response": "...",
      "rating": "thumbs_up",
      "timestamp": "...",
      "comment": "...",
      "toolsUsed": ["searchDocs", "summarize"],
      "durationMs": 3500,
      "templateId": "support-v2"
    }
  ]
}
```

This format conforms to the eval-testing `schemas/feedback.schema.json` data contract.

### Get Feedback (Any User)
```
GET /api/feedback/{feedbackId}
```

### Delete Feedback (Admin)
```
DELETE /api/feedback/{feedbackId}
```

Response: `204 No Content`

---

## Integration with PromptLab Eval

The feedback export endpoint produces data that can be directly consumed by the PromptLab evaluation pipeline:

```
Feedback Export  →  eval-testing schema  →  PromptLab Eval Runner
                                              │
                                              ├─ Compare model A vs B
                                              ├─ Compare prompt v1 vs v2
                                              └─ Track quality over time
```

Key fields for evaluation:
- `templateId` + `promptVersion`: Which prompt produced the response
- `model`: Which LLM was used
- `rating`: Ground truth quality signal
- `toolsUsed`: Which tools were invoked
- `durationMs`: Performance metric

---

## Configuration

The feedback system does not have a dedicated configuration block. It is activated when a `FeedbackStore` bean is registered.

`InMemoryFeedbackStore` is auto-configured by default. When PostgreSQL and `JdbcTemplate` are available, `JdbcFeedbackStore` is registered.

The `FeedbackController` is conditionally activated via `@ConditionalOnBean(FeedbackStore::class)`.

---

## Database Schema

### `feedback` (V17 + V24)

```sql
CREATE TABLE feedback (
    feedback_id    VARCHAR(36)   PRIMARY KEY,
    query          TEXT          NOT NULL,
    response       TEXT          NOT NULL,
    rating         VARCHAR(20)   NOT NULL,
    timestamp      TIMESTAMP     NOT NULL,
    comment        TEXT,
    session_id     VARCHAR(255),
    run_id         VARCHAR(36),
    user_id        VARCHAR(255),
    intent         VARCHAR(50),
    domain         VARCHAR(50),
    model          VARCHAR(100),
    prompt_version INTEGER,
    tools_used     TEXT,          -- JSON array
    duration_ms    BIGINT,
    tags           TEXT,          -- JSON array
    template_id    VARCHAR(255)
);

CREATE INDEX idx_feedback_rating      ON feedback (rating);
CREATE INDEX idx_feedback_timestamp   ON feedback (timestamp);
CREATE INDEX idx_feedback_session_id  ON feedback (session_id);
CREATE INDEX idx_feedback_run_id      ON feedback (run_id);
CREATE INDEX idx_feedback_template_id ON feedback (template_id);
```

---

## Common Pitfalls

1. **Auto-enrichment TTL**: Metadata is cached for 1 hour. Feedback submitted after that window will not be auto-enriched -- the client must provide `query` and `response` explicitly.
2. **Rating format**: The API accepts case-insensitive strings (`thumbs_up`, `THUMBS_UP`, `Thumbs_Up`). Invalid values return `400 Bad Request`.
3. **JSON list columns**: `tools_used` and `tags` are stored as JSON TEXT in PostgreSQL. They are deserialized on read -- malformed JSON silently returns `null`.
4. **Export size**: The export endpoint returns all feedback entries. For large datasets, consider using the filtered list endpoint with pagination.
5. **ConditionalOnBean**: The `FeedbackController` is only registered when a `FeedbackStore` bean exists. If you see 404 on `/api/feedback`, ensure the store bean is configured.

---

## Key Files

| File | Role |
|------|------|
| `feedback/FeedbackModels.kt` | Feedback data class, FeedbackRating enum |
| `feedback/FeedbackStore.kt` | FeedbackStore interface, InMemoryFeedbackStore |
| `feedback/JdbcFeedbackStore.kt` | JDBC implementation with dynamic filtering |
| `hook/impl/FeedbackMetadataCaptureHook.kt` | AfterAgentCompleteHook for auto-enrichment cache |
| `controller/FeedbackController.kt` | REST API for feedback submission, listing, export, deletion |

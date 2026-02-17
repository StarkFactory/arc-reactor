# Intent Classification

> This document explains Arc Reactor's Intent Classification system — how user input is analyzed, matched to a registered intent, and used to dynamically configure the agent pipeline.

## One-Line Summary

**Classify user input and apply per-intent pipeline overrides (model, tools, prompt) before the agent executes.**

---

## Why Is It Needed?

Without intent classification, every request uses the same agent configuration:

```
User: "Hello"           → model=openai, maxToolCalls=10, fullSystemPrompt
User: "Refund order"    → model=openai, maxToolCalls=10, fullSystemPrompt
User: "Analyze Q4 data" → model=openai, maxToolCalls=10, fullSystemPrompt
```

Problems:
- **Wasted tokens**: Simple greetings don't need expensive models or tools
- **Lack of specialization**: Refund requests need a refund-specific prompt, not a generic one
- **No tool scoping**: Every request sees all tools, increasing LLM confusion

With intent classification:

```
User: "Hello"           → model=gemini, maxToolCalls=0   (cheap, no tools)
User: "Refund order"    → model=openai, maxToolCalls=5,  systemPrompt="Refund specialist..."
User: "Analyze Q4 data" → model=openai, maxToolCalls=10, tools=[analyzeData, generateReport]
```

---

## Architecture

```
User Input
    │
    ▼
┌─ CompositeIntentClassifier ─────────────────────────────────────┐
│                                                                  │
│  1. RuleBasedIntentClassifier (0 tokens)                        │
│     └─ Keyword/synonym match? confidence >= 0.8?                │
│         ├─ YES → Return result (skip LLM)                       │
│         └─ NO  → Fall through to LLM                            │
│     Scoring: weighted keywords, synonym expansion,              │
│              negative keyword exclusion                          │
│                                                                  │
│  2. LlmIntentClassifier (~200-500 tokens)                       │
│     └─ Send compact prompt to LLM                               │
│         ├─ Success → Return classified intent(s)                │
│         └─ Failure → Fall back to rule result                   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ IntentResolver ────────────────────────────────────────────────┐
│  1. Check confidence >= threshold (default 0.6)                 │
│  2. Look up IntentDefinition in registry                        │
│  3. Merge profiles (multi-intent tool merging)                  │
│  4. Return ResolvedIntent with profile                          │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ SpringAiAgentExecutor ────────────────────────────────────────┐
│  resolveIntent() — called after guard/hooks pass:              │
│    1. Classify input via IntentResolver                         │
│    2. Check blockedIntents → GUARD_REJECTED if blocked          │
│    3. Apply profile overrides to AgentCommand                   │
│    4. Fail-safe: on error, use original command                 │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
  ReAct Loop (with effective command)
```

**Key design principles**:
- The intent system **never blocks** requests. Classification failure or low confidence = default pipeline runs as usual.
- Intent resolution is **fail-safe**: exceptions fall back to the original command (except `BlockedIntentException`).
- Blocked intents return `GUARD_REJECTED` error code.

---

## Enhanced Rule-Based Classification

The rule-based classifier supports three advanced features beyond simple keyword matching:

### Synonyms

Map each keyword to alternative forms. A synonym match counts as a match for the original keyword (no double-counting).

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund", "cancel"),
    synonyms = mapOf(
        "refund" to listOf("리펀드", "돌려줘"),
        "cancel" to listOf("캔슬", "취소")
    )
)
```

Input `"리펀드 해주세요"` matches the `"refund"` keyword via its synonym.

### Keyword Weights

Assign higher weights to more discriminative keywords. Default weight is `1.0`.

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund", "order"),
    keywordWeights = mapOf("refund" to 3.0)
    // "refund" weight=3.0, "order" weight=1.0 (default)
)
```

If only `"refund"` matches: confidence = 3.0 / 4.0 = **0.75** (instead of 0.5 with equal weights).

### Negative Keywords

Phrases that immediately exclude the intent when matched. Useful for disambiguating similar intents.

```kotlin
IntentDefinition(
    name = "refund",
    keywords = listOf("refund"),
    negativeKeywords = listOf("refund policy")
)
```

Input `"Tell me about refund policy"` is excluded from the refund intent, allowing a FAQ intent to match instead.

### Scoring Algorithm

```
scoreIntent(intent, text):
  1. Any negativeKeyword matched in text? → exclude (return null)
  2. For each keyword:
     - variants = [keyword] + synonyms[keyword]
     - weight = keywordWeights[keyword] ?: 1.0
     - Any variant matched? → matchedWeight += weight
     - totalWeight += weight
  3. confidence = matchedWeight / totalWeight (capped at 1.0)
```

---

## Blocked Intents

Specific intents can be blocked at the executor level. A blocked intent returns `GUARD_REJECTED` error code.

```yaml
arc:
  reactor:
    intent:
      blocked-intents: refund, data_analysis
```

This is useful for temporarily disabling certain intent paths without removing their definitions.

---

## Configuration

```yaml
arc:
  reactor:
    intent:
      enabled: true                    # Default: false (opt-in)
      confidence-threshold: 0.6        # Min confidence to apply profile
      llm-model: gemini                # LLM for classification (null = default)
      rule-confidence-threshold: 0.8   # Min rule confidence to skip LLM
      max-examples-per-intent: 3       # Few-shot examples in LLM prompt
      max-conversation-turns: 2        # Conversation context (2 turns = 4 messages)
      blocked-intents:                 # Intents to reject (GUARD_REJECTED)
```

---

## REST API

### List Intents
```
GET /api/intents
```

### Get Intent
```
GET /api/intents/{name}
```

### Create Intent
```
POST /api/intents
Content-Type: application/json

{
  "name": "refund",
  "description": "Refund requests, return processing",
  "examples": ["I want a refund", "Return this order"],
  "keywords": ["refund", "return", "cancel"],
  "synonyms": {
    "refund": ["리펀드", "돌려줘"],
    "return": ["반송", "반품"]
  },
  "keywordWeights": {
    "refund": 3.0,
    "return": 2.0
  },
  "negativeKeywords": ["refund policy", "return policy"],
  "profile": {
    "systemPrompt": "You are a refund specialist.",
    "maxToolCalls": 5,
    "allowedTools": ["checkOrder", "processRefund"]
  }
}
```

### Update Intent
```
PUT /api/intents/{name}
```

### Delete Intent
```
DELETE /api/intents/{name}
```

---

## Tool Allowlist (`allowedTools`)

If `profile.allowedTools` is set, tool calls not included in the list are blocked at execution time and returned as an error tool response.

---

## Key Files

| File | Role |
|------|------|
| `intent/model/IntentModels.kt` | Data classes: IntentDefinition, IntentProfile, IntentResult |
| `intent/IntentClassifier.kt` | Classifier interface |
| `intent/IntentRegistry.kt` | Registry interface + InMemoryIntentRegistry |
| `intent/IntentResolver.kt` | Orchestrates classification + profile application |
| `intent/impl/RuleBasedIntentClassifier.kt` | Keyword matching with synonyms, weights, negatives (0 tokens) |
| `intent/impl/LlmIntentClassifier.kt` | LLM-based classification |
| `intent/impl/CompositeIntentClassifier.kt` | Rule → LLM cascading |
| `intent/impl/JdbcIntentRegistry.kt` | Persistent registry (JDBC) |
| `intent/example/CustomerServiceIntents.kt` | Example intent definitions with all features |
| `controller/IntentController.kt` | REST API for intent CRUD |
| `agent/impl/SpringAiAgentExecutor.kt` | IntentResolver integration + blocked intent handling |

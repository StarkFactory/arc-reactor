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
│     └─ Keyword match? confidence >= 0.8?                        │
│         ├─ YES → Return result (skip LLM)                       │
│         └─ NO  → Fall through to LLM                            │
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
┌─ ChatController ────────────────────────────────────────────────┐
│  Apply profile overrides to AgentCommand:                       │
│    command.copy(model=..., systemPrompt=..., maxToolCalls=...)  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
  Agent Executor (unchanged)
```

**Key design principle**: The intent system **never blocks** requests. Classification failure or low confidence = default pipeline runs as usual.

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
  "keywords": ["refund", "return"],
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

## Key Files

| File | Role |
|------|------|
| `intent/model/IntentModels.kt` | Data classes: IntentDefinition, IntentProfile, IntentResult |
| `intent/IntentClassifier.kt` | Classifier interface |
| `intent/IntentRegistry.kt` | Registry interface + InMemoryIntentRegistry |
| `intent/IntentResolver.kt` | Orchestrates classification + profile application |
| `intent/impl/RuleBasedIntentClassifier.kt` | Keyword matching (0 tokens) |
| `intent/impl/LlmIntentClassifier.kt` | LLM-based classification |
| `intent/impl/CompositeIntentClassifier.kt` | Rule → LLM cascading |
| `intent/impl/JdbcIntentRegistry.kt` | Persistent registry (JDBC) |
| `controller/IntentController.kt` | REST API for intent CRUD |

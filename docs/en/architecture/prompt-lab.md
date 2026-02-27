# Prompt Lab: Self-Optimizing Prompt Agent

> Automated prompt optimization pipeline that analyzes user feedback, generates improved prompt candidates, and evaluates them through a 3-tier evaluation engine.

## Overview

Prompt Lab closes the feedback loop between user interactions and prompt quality. Instead of manually iterating on system prompts, it automates the entire cycle:

```
                         Prompt Lab Pipeline

  Feedback        Prompt           Experiment         Report
  Analyzer   -->  Candidate   -->  Orchestrator  -->  Generator
                  Generator
     |               |                 |                  |
 FeedbackStore   PromptTemplate    AgentExecutor     ExperimentStore
 (existing)      Store (existing)  (existing)
                     |                 |
                DRAFT versions    3-Tier Eval
                                      |
                               Recommendation
                                      |
                                HITL Activate
                               (optional)
```

**Key capabilities:**

1. Analyze `THUMBS_DOWN` feedback to identify prompt weaknesses
2. Auto-generate improved prompt candidates via LLM
3. Run controlled experiments: baseline vs candidates on identical test queries
4. Evaluate responses through a 3-tier pipeline (structural, rules, LLM judge)
5. Generate comparison reports with confidence-scored recommendations
6. HITL activation gate for deploying the winning version

## Enabling Prompt Lab

```yaml
arc:
  reactor:
    prompt-lab:
      enabled: true
```

All beans are registered via `PromptLabConfiguration` with `@ConditionalOnProperty(prefix = "arc.reactor.prompt-lab", name = ["enabled"], havingValue = "true")`.

## Package Structure

```
arc-core/.../promptlab/
  model/
    EvaluationTier.kt              # STRUCTURAL, RULES, LLM_JUDGE
    PromptLabModels.kt             # Experiment, Trial, Report, etc.
  eval/
    PromptEvaluator.kt             # Evaluator interface
    StructuralEvaluator.kt         # Tier 1: JSON structure validation
    RuleBasedEvaluator.kt          # Tier 2: deterministic rules
    LlmJudgeEvaluator.kt           # Tier 3: LLM semantic judgment
    EvaluationPipeline.kt          # Sequential fail-fast orchestration
  analysis/
    FeedbackAnalyzer.kt            # Weakness identification from feedback
    PromptCandidateGenerator.kt    # LLM-based prompt improvement
  hook/
    ExperimentCaptureHook.kt       # AfterAgentComplete: metadata capture
  ExperimentStore.kt               # Interface + InMemoryExperimentStore
  ExperimentOrchestrator.kt        # Experiment execution engine
  ReportGenerator.kt               # Comparison report synthesis
  PromptLabScheduler.kt            # Cron-based auto-optimization
  PromptLabProperties.kt           # Configuration properties
  autoconfigure/
    PromptLabConfiguration.kt      # Bean registration

arc-web/.../controller/
  PromptLabController.kt           # REST API (ADMIN only)
```

## 3-Tier Evaluation Engine

Each response is evaluated sequentially through three tiers. If a lower tier fails, higher tiers are skipped (fail-fast).

```
Response --> [Tier 1: Structural] --> [Tier 2: Rules] --> [Tier 3: LLM Judge]
              FREE, instant           FREE, instant       PAID, slow
```

### Tier 1: Structural Evaluator

Validates the response structure:

- **JSON parseable**: Can the response be parsed as valid JSON?
- **Required fields**: Does it contain `type` (one of `answer|error|action|briefing|clarification|search`) and `message` (or `summary` for briefings)?
- **Scoring**: `1.0` (valid JSON + all fields), `0.5` (plain text, non-JSON — pass), `0.3` (JSON but missing required fields — fail)

### Tier 2: Rule-Based Evaluator

Deterministic rules ported from eval-testing assertions:

| Rule | Condition | Check |
|------|-----------|-------|
| Short Answer | Search intent + type=answer | Message must be >= 50 chars |
| Action Confirmation | Mutation intent + success=true | Must contain confirmation phrase |
| Error Quality | type=error | Must have suggestions list OR message >= 20 chars |
| Clarification-Only | type=clarification | Must not be question-only pattern |

Regex patterns are compiled in `companion object` to avoid hot-path allocation.

### Tier 3: LLM Judge Evaluator

Calls a judge LLM to score responses on a rubric:

- **Default rubric**: Helpfulness (25), Accuracy (25), Completeness (25), Safety (25)
- **Custom rubric**: Override via `EvaluationConfig.customRubric`
- **Budget control**: Tracks cumulative token usage via `AtomicInteger`. When `llmJudgeBudgetTokens` is exhausted, returns `score=0.5` with reason "Budget exhausted"
- **Same-model warning**: Logs a warning if `model == judgeModel` (does not block)

**LLM response format:**

```json
{"pass": true, "score": 0.85, "reason": "Response is helpful and accurate..."}
```

### Pipeline Configuration

```kotlin
data class EvaluationConfig(
    val structuralEnabled: Boolean = true,
    val rulesEnabled: Boolean = true,
    val llmJudgeEnabled: Boolean = true,
    val llmJudgeBudgetTokens: Int = 100_000,
    val customRubric: String? = null
)
```

## Feedback Analysis

`FeedbackAnalyzer` processes `THUMBS_DOWN` feedback to identify prompt weaknesses:

```kotlin
val analysis = feedbackAnalyzer.analyze(
    templateId = "my-template",
    since = Instant.parse("2026-01-01T00:00:00Z"),  // optional: Cron incremental
    maxSamples = 50
)
// analysis.weaknesses: [PromptWeakness(category, description, frequency, exampleQueries)]
// analysis.sampleQueries: [TestQuery(query, intent, domain)]
```

**Process:**

1. Fetch negative feedback from `FeedbackStore` filtered by `templateId`
2. Send feedback (query + response + comment) to LLM for pattern analysis
3. LLM classifies weaknesses: `short_answer`, `missing_sources`, `incorrect_info`, `no_tool_usage`, `missing_context`, `off_topic`, `poor_formatting`, `other`
4. Extract unique queries as `TestQuery` objects (preserving intent/domain)

**Important:** Feedback must have `templateId` set to be analyzed. The `FeedbackMetadataCaptureHook` automatically captures `promptTemplateId` from `HookContext.metadata` (set by `ChatController`). Pre-existing feedback without `template_id` will not be picked up.

## Prompt Candidate Generation

`PromptCandidateGenerator` creates improved prompt versions:

```kotlin
val candidateIds = candidateGenerator.generate(
    templateId = "my-template",
    analysis = analysis,
    candidateCount = 3
)
// Returns: List<String> of created PromptVersion IDs (DRAFT status)
```

**Process:**

1. Get the current active version of the template
2. Build a meta-prompt containing: current system prompt, identified weaknesses (categories, frequencies, examples), improvement instructions
3. Request N diverse candidates from LLM (each using a different improvement strategy)
4. Save each candidate as a new `PromptVersion` via `PromptTemplateStore`
5. Record changelog: "Auto-generated by Prompt Lab: {weakness summary}"

## Experiment Lifecycle

### States

```
PENDING --> RUNNING --> COMPLETED
                   \--> FAILED
                   \--> CANCELLED
```

### Experiment Model

```kotlin
data class Experiment(
    val id: String,
    val name: String,
    val templateId: String,
    val baselineVersionId: String,       // current ACTIVE version
    val candidateVersionIds: List<String>, // versions to compare
    val testQueries: List<TestQuery>,
    val evaluationConfig: EvaluationConfig,
    val model: String? = null,           // target LLM for evaluation
    val judgeModel: String? = null,      // Tier 3 judge LLM
    val temperature: Double = 0.3,
    val repetitions: Int = 1,            // repeat count for variance
    val autoGenerated: Boolean = false,
    val status: ExperimentStatus = PENDING,
    // ... timestamps, error info
)
```

### Execution Flow

`ExperimentOrchestrator.execute()`:

1. Validate experiment is `PENDING`, transition to `RUNNING`
2. For each version (baseline + candidates):
   - For each test query:
     - For each repetition:
       - Build `AgentCommand` with version's system prompt
       - Execute via `AgentExecutor.execute()`
       - Evaluate response through `EvaluationPipeline`
       - Record `Trial` with response, evaluations, token usage, duration
3. Save all trials to `ExperimentStore`
4. Generate report via `ReportGenerator`
5. Transition to `COMPLETED` (or `FAILED` on error)

**Timeout:** Enforced via `withTimeout(experimentTimeoutMs)` (default: 10 minutes).

### Auto Pipeline

`ExperimentOrchestrator.runAutoPipeline()` combines all stages:

```
Analyze Feedback --> Generate Candidates --> Create Experiment --> Execute --> Report
```

Skips if negative feedback count is below `minNegativeFeedback` threshold (default: 5).

## Report Generation

`ReportGenerator` synthesizes experiment results into an `ExperimentReport`:

### Version Summary

Per-version aggregated metrics:

| Metric | Description |
|--------|-------------|
| `passRate` | Trials where ALL evaluations passed / total trials |
| `avgScore` | Average evaluation score across all trials |
| `avgDurationMs` | Average response time |
| `totalTokens` | Cumulative token usage |
| `tierBreakdown` | Per-tier pass rate and avg score |
| `toolUsageFrequency` | Tool name -> usage count |
| `errorRate` | Failed trials / total trials |

### Recommendation

Best version selection using weighted scoring: `passRate * 0.6 + avgScore * 0.4`

| Confidence | Condition |
|------------|-----------|
| `HIGH` | >10% pass rate gap between best and baseline |
| `MEDIUM` | 5-10% gap |
| `LOW` | <5% gap or insufficient data |

The recommendation includes:
- `improvements`: What got better (pass rate, score, speed)
- `warnings`: What got worse (error rate, token usage)

## Hook Integration

`ExperimentCaptureHook` (order=270) captures experiment metadata during trial execution:

- **Activation**: Only when `context.metadata` contains `promptlab.experimentId` and `promptlab.versionId`
- **Storage**: `ConcurrentHashMap` with 1-hour TTL, max 10,000 entries
- **Error policy**: `failOnError = false` (fail-open)
- **Purpose**: Observability and indirect trigger scenarios

Metadata keys injected by `ExperimentOrchestrator`:

```kotlin
ExperimentCaptureHook.EXPERIMENT_ID_KEY  // "promptlab.experimentId"
ExperimentCaptureHook.VERSION_ID_KEY     // "promptlab.versionId"
ExperimentCaptureHook.RUN_ID_KEY         // "promptlab.runId"
```

## Cron Scheduling

`PromptLabScheduler` enables periodic auto-optimization:

```yaml
arc:
  reactor:
    prompt-lab:
      schedule:
        enabled: true
        cron: "0 0 2 * * *"          # daily at 2 AM
        template-ids:                  # empty = all templates
          - "template-1"
          - "template-2"
```

**Behavior:**

- Runs `runAutoPipeline()` for each configured template
- Tracks `lastRunTime` to pass `since` parameter (incremental analysis)
- Uses `AtomicBoolean` lock to prevent concurrent runs
- Executes on `Dispatchers.IO` to avoid blocking Spring's scheduler thread

## REST API

All endpoints require Admin access. Base path: `/api/prompt-lab`

### Experiment CRUD

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/experiments` | Create experiment (validates limits) |
| `GET` | `/experiments` | List experiments (filter: `status`, `templateId`) |
| `GET` | `/experiments/{id}` | Get experiment details |
| `POST` | `/experiments/{id}/run` | Start experiment (async) |
| `POST` | `/experiments/{id}/cancel` | Cancel running experiment |
| `GET` | `/experiments/{id}/status` | Poll experiment status |
| `GET` | `/experiments/{id}/trials` | Get trial data |
| `GET` | `/experiments/{id}/report` | Get comparison report |
| `DELETE` | `/experiments/{id}` | Delete experiment |

### Automation

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/auto-optimize` | Trigger full auto pipeline (async) |
| `POST` | `/analyze` | Run feedback analysis only |
| `POST` | `/experiments/{id}/activate` | Activate recommended version (HITL gate) |

### Input Validation

The controller enforces configuration limits before experiment creation:

- `testQueries.size <= maxQueriesPerExperiment` (default: 100)
- `1 + candidateVersionIds.size <= maxVersionsPerExperiment` (default: 10)
- `repetitions <= maxRepetitions` (default: 5)
- Concurrent running experiments limited to `maxConcurrentExperiments` (default: 3, returns 429)

### Example: Create and Run Experiment

```bash
# Create experiment
curl -X POST http://localhost:8080/api/prompt-lab/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Improve support prompts",
    "templateId": "support-template",
    "baselineVersionId": "v1",
    "candidateVersionIds": ["v2", "v3"],
    "testQueries": [
      {"query": "How do I reset my password?", "intent": "account", "domain": "support"},
      {"query": "Where are the docs?", "intent": "docs", "domain": "knowledge"}
    ]
  }'

# Run experiment (async)
curl -X POST http://localhost:8080/api/prompt-lab/experiments/{id}/run

# Poll status
curl http://localhost:8080/api/prompt-lab/experiments/{id}/status

# Get report
curl http://localhost:8080/api/prompt-lab/experiments/{id}/report

# Activate recommended version (HITL)
curl -X POST http://localhost:8080/api/prompt-lab/experiments/{id}/activate
```

### Example: Auto-Optimize

```bash
curl -X POST http://localhost:8080/api/prompt-lab/auto-optimize \
  -H "Content-Type: application/json" \
  -d '{"templateId": "support-template", "candidateCount": 3}'
```

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `arc.reactor.prompt-lab.enabled` | `false` | Enable Prompt Lab |
| `...max-concurrent-experiments` | `3` | Max simultaneous running experiments |
| `...max-queries-per-experiment` | `100` | Max test queries per experiment |
| `...max-versions-per-experiment` | `10` | Max versions (baseline + candidates) |
| `...max-repetitions` | `5` | Max repetition count per trial |
| `...default-judge-model` | `null` | Default LLM for Tier 3 judge |
| `...default-judge-budget-tokens` | `100,000` | Token budget for LLM judge per experiment |
| `...experiment-timeout-ms` | `600,000` | Experiment execution timeout (10 min) |
| `...candidate-count` | `3` | Number of auto-generated candidates |
| `...min-negative-feedback` | `5` | Min negative feedback to trigger auto pipeline |
| `...schedule.enabled` | `false` | Enable cron scheduling |
| `...schedule.cron` | `0 0 2 * * *` | Cron expression (default: daily 2 AM) |
| `...schedule.template-ids` | `[]` | Target templates (empty = all) |

## Experiment Store

`InMemoryExperimentStore` provides thread-safe storage with automatic eviction:

- **Thread safety**: `ConcurrentHashMap` + `CopyOnWriteArrayList`
- **Capacity**: Default 1,000 experiments
- **Eviction**: Oldest terminal-status experiments (`COMPLETED`, `FAILED`, `CANCELLED`) evicted when capacity exceeded
- **Override**: Implement `ExperimentStore` interface and register as a bean

## Interaction with Guard System

The Guard pipeline and Prompt Lab are independent systems. If the Guard system (from `feat/enterprise-guard-system`) is active:

- `ToolOutputSanitizer` and `CanaryToken` apply equally to baseline and candidate trials
- This preserves comparison fairness — both versions face the same security constraints
- Guard does not affect feedback collection or the hook system used by Prompt Lab

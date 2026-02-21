# Agent Benchmarking (Cost-Aware)

This guide benchmarks Arc Reactor as an **agent system**, not just an LLM.

## What to Measure

| Metric | Why it matters |
|---|---|
| Task success rate | Measures real business completion quality |
| Tool call precision | Detects wrong or unnecessary tool invocations |
| Policy/guard violation rate | Verifies governance controls |
| End-to-end latency (p50/p95/p99) | Captures user-perceived speed |
| Timeout/error rate | Tracks operational reliability |
| Cost per successful task | Controls economics in production |

## Cost-Control Strategy

Use a three-tier benchmark pipeline:

1. Tier 0 (No LLM cost): deterministic tests with mocks and replay fixtures
2. Tier 1 (Low cost): small sampled prompts on cheaper model settings
3. Tier 2 (Release gate): limited full benchmark with strict budget cap

### Practical Budget Controls

- Fix request count per run (for example 20/50/100 prompts)
- Cap max output tokens
- Reuse fixed prompt set and compare version-to-version
- Run Tier 2 only on release branches/tags
- Fail benchmark job when budget threshold is exceeded

## Suggested Benchmark Cadence

- Per PR: Tier 0 + Tier 1 smoke (small sample)
- Nightly: Tier 1 expanded
- Release candidate: Tier 2 controlled full run

## Benchmark Runbook (Arc Reactor)

### 1) Baseline correctness without LLM spend

```bash
./gradlew test
```

### 2) Chat API load and latency benchmark (k6)

```bash
BASE_URL=http://localhost:8080 \
VUS=5 \
DURATION=1m \
PROMPTS='What is 2+2?|Summarize incident response policy' \
scripts/load/run-chat-load-test.sh
```

### 3) Slack gateway benchmark (existing)

```bash
BASE_URL=http://localhost:8080 MODE=mixed VUS=30 DURATION=2m \
scripts/load/run-slack-load-test.sh
```

## Release Gate Example

Define minimum gate expectations before shipping:

- task success rate >= 95%
- p95 latency <= 3s
- timeout/error rate < 1%
- policy violation rate = 0
- benchmark cost <= pre-approved run budget

## Notes

- Always compare against a fixed baseline dataset.
- For model changes, separate quality deltas from infrastructure deltas.
- Archive benchmark reports per release tag for traceability.

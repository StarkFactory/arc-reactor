# Releases Package

Versioned release notes and upgrade highlights.

- GitHub Release automation runs on tag push (`v*`) via `.github/workflows/release.yml`.
- [v5.10.0](v5.10.0.md) — 2026-03-16 — Rate limit HTTP 429 fix, Kotlin defaults aligned with application.yml
- [v5.9.0](v5.9.0.md) — 2026-03-16 — MediaAttachment.equals() precedence fix, missing @Valid on document deletion
- [v5.8.2](v5.8.2.md) — 2026-03-16 — CLAUDE.md accuracy fixes (7 documentation-code mismatches)
- [v5.8.1](v5.8.1.md) — 2026-03-16 — Critical fix: MicrometerAgentMetrics bean priority over NoOp
- [v5.8.0](v5.8.0.md) — 2026-03-16 — Concurrency bug fixes (CAS, atomic delete, double-start, MDC), ChatOptions expansion
- [v5.7.0](v5.7.0.md) — 2026-03-16 — RAG performance optimization (similarity threshold, topK, retry, reranking defaults)
- [v5.6.0](v5.6.0.md) — 2026-03-16 — MCP registration SSRF fix for private addresses
- [v5.5.0](v5.5.0.md) — 2026-03-16 — Agent response quality, guard stage ordering fix
- [v5.4.0](v5.4.0.md) — 2026-03-16 — Guard stage ordering, @ConditionalOnMissingBean compliance, observability
- [v5.3.0](v5.3.0.md) — 2026-03-16 — OUTPUT_GUARD error codes return 422, scheduler index, Helm resources
- [v5.2.0](v5.2.0.md) — 2026-03-16 — RAG grounding, document deduplication, post-v5.1.1 stabilization
- [v5.1.0](v5.1.0.md) — 2026-03-16 — RAG pipeline (adaptive routing, query decomposition, compression), multi-agent, scheduler
- [v5.0.0](v5.0.0.md) — 2026-03-16 — Security hardening (deny-by-default), reliability, performance, observability
- [v4.8.0](v4.8.0.md) — 2026-03-10 — Grounded routing, identity propagation, MCP reconnection, Docker CI updates
- [v4.7.6](v4.7.6.md) — 2026-03-05 — Redis optional-runtime fallback hardening, runtime classpath fix, Helm/K8s config parity
- [v4.7.4](v4.7.4.md) — 2026-03-04 — Event-loop blocking offload for LLM/tool paths and ToolDefinition metadata adapter compatibility fix
- [v4.7.3](v4.7.3.md) — 2026-03-01 — Mandatory JWT auth + tenant fail-close, Docker image source scope fix, Flyway immutability CI guard and runbook
- [v4.7.2](v4.7.2.md) — 2026-03-01 — Tool callback deduplication, Slack local-tool documentation alignment, RAG example endpoint fixes
- [v4.7.1](v4.7.1.md) — 2026-03-01 — Slack runtime hardening, error-report timeout enforcement, mode-aware validation script
- [v4.5.0](v4.5.0.md) — 2026-02-28 — Documentation overhaul, CLAUDE.md/AGENTS.md rewrite, gitleaks config
- [v4.4.0](v4.4.0.md) — 2026-02-28 — Helm chart, Docker image publication, example guides, regression test coverage
- [v4.3.0](v4.3.0.md) — 2026-02-28 — Prompt Lab, security hardening (P0), CORS/HSTS fixes, 3.x → 4.x migration guide
- [Migration Guide: 3.x → 4.x](migration-3x-to-4x.md)
- [v4.1.0](v4.1.0.md)
- [v4.0.1](v4.0.1.md)
- [v4.0.0](v4.0.0.md)
- [v3.9.7](v3.9.7.md)
- [Release Readiness Checklist](release-readiness-checklist.md)
- [v3.9.3](v3.9.3.md)
- [v3.9.2](v3.9.2.md)
- [v3.9.1](v3.9.1.md)
- [v3.9.0](v3.9.0.md)
- [v3.8.2](v3.8.2.md)
- [v3.8.1](v3.8.1.md)

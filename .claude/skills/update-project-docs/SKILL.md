---
name: update-project-docs
description: Update CLAUDE.md and AGENTS.md to reflect current project state and latest best practices
disable-model-invocation: true
argument-hint: "[focus area: e.g. 'new module' or 'after major refactor']"
---

Update CLAUDE.md and AGENTS.md for this project. Follow these steps:

## Step 1 — Read current state

Read both files in full:
- `CLAUDE.md`
- `AGENTS.md`

Also read any recently changed files if $ARGUMENTS mentions a specific focus area.

## Step 2 — Audit CLAUDE.md

Apply this litmus test to every line: **"Would removing this cause Claude to make mistakes?"**

**Delete if**:
- Readable from code (feature toggle lists → read `AgentPolicyAndFeatureProperties.kt`, API endpoints → read controllers, domain terms → read class names/KDoc, error codes → read `GlobalExceptionHandler.kt`)
- Standard Kotlin/Spring conventions Claude already knows
- Self-evident practices ("write clean code", "follow SOLID")
- Information that changes frequently (specific line counts, PR numbers)

**Keep if**:
- Non-obvious Gradle/build flags (`-Pdb=true`, `-PincludeIntegration`)
- Design decisions invisible in code (Guard=fail-close vs Hook=fail-open)
- Gotchas that caused real bugs (coroutine pitfalls, mock chain patterns)
- Extension point conventions (`@ConditionalOnMissingBean`, error string return)
- CI policy and PR rules
- Security invariants (`NEVER` patterns)

**Target**: 100–150 lines. Warn if exceeding 180. Use `@.claude/rules/kotlin-spring.md` import for code conventions.

## Step 3 — Audit AGENTS.md

Check for these standard sections and add/update as needed:
1. **Project overview** — 1 paragraph: what it IS and WHY it exists
2. **Environment setup** — required env vars, prerequisites
3. **Validate/build commands** — exact commands with flags
4. **Module structure** — table of modules and their purpose
5. **Architecture** — request flow + key files
6. **Critical gotchas** — non-obvious bugs (should match CLAUDE.md)
7. **Key defaults** — config values (table format)
8. **Code rules** — non-obvious conventions only
9. **Extension points** — component rules table
10. **Testing rules** — framework-specific helpers and patterns
11. **PR rules** — CI gates, cost policy

Update stale content: new modules added, new gotchas discovered from recent PRs/fixes.

## Step 4 — Write updated files

Use Write tool for both files. Preserve all content that passed the audit.

## Step 5 — Verify

```bash
wc -l CLAUDE.md AGENTS.md
```

Report line counts. If CLAUDE.md > 180 lines, identify and cut more.

## Principles (source: Anthropic official docs + agents.md spec, March 2026)

- CLAUDE.md loads every session — bloat causes rules to be ignored
- AGENTS.md is read by Codex, Gemini CLI, Copilot CLI too — keep it self-contained
- Redundancy between the two is acceptable — AGENTS.md readers don't have CLAUDE.md context
- `@` imports for large reference content (loads on demand, not every session)
- Critical gotchas should appear in BOTH files

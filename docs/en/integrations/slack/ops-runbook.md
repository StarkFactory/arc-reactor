# Slack Metrics and Load Test Runbook

## 1) Where to check metrics

After Arc Reactor starts:

- Metric names: `GET /actuator/metrics`
- Single metric: `GET /actuator/metrics/{name}`
- Prometheus scrape: `GET /actuator/prometheus`
- Ops dashboard snapshot API: `GET /api/ops/dashboard` (ADMIN)
- Ops metric name list API: `GET /api/ops/metrics/names` (ADMIN)

Default exposure settings are configured in `arc-core/src/main/resources/application.yml`.

## 2) Slack metrics added

- `arc.slack.inbound.total`
  - tags: `entrypoint`, `event_type`
- `arc.slack.duplicate.total`
  - tags: `event_type`
- `arc.slack.dropped.total`
  - tags: `entrypoint`, `reason`, `event_type`
- `arc.slack.handler.duration`
  - tags: `entrypoint`, `event_type`, `success`
- `arc.slack.api.duration`
  - tags: `method`, `outcome`
- `arc.slack.api.retry.total`
  - tags: `method`, `reason`
- `arc.slack.response_url.total`
  - tags: `outcome`

## 3) Load test automation

Added k6 script and launcher:

- Script: `scripts/load/slack-gateway-load.js`
- Runner: `scripts/load/run-slack-load-test.sh`

Example:

```bash
BASE_URL=http://localhost:8080 MODE=mixed VUS=100 DURATION=3m \
SLACK_SIGNING_SECRET=your_secret \
scripts/load/run-slack-load-test.sh
```

Parameters:

- `MODE=events|commands|mixed`
- `VUS` concurrent users
- `SLEEP_SECONDS` delay between virtual user requests (default `0.1`)
- `DURATION` test duration
- `SLACK_SIGNING_SECRET` required when signature verification is enabled

## 4) Backpressure defaults

Use fail-fast mode by default under heavy load to avoid queue growth.

- `ARC_REACTOR_SLACK_FAIL_FAST_ON_SATURATION=true`
  - Drop immediately on saturation (`arc.slack.dropped.total{reason="queue_overflow"}` increases)
- `ARC_REACTOR_SLACK_NOTIFY_ON_DROP=false`
  - Disable drop-notification message sends (prevents Slack API amplification)
- `ARC_REACTOR_SLACK_REQUEST_TIMEOUT_MS`
  - Used as queue wait timeout only when `fail-fast=false`

## 5) Admin dynamic output-guard rule API

Runtime rule management:

- `GET /api/output-guard/rules`
- `POST /api/output-guard/rules` (ADMIN)
- `PUT /api/output-guard/rules/{id}` (ADMIN)
- `DELETE /api/output-guard/rules/{id}` (ADMIN)
- `POST /api/output-guard/rules/simulate` (ADMIN, dry-run)
- `GET /api/output-guard/rules/audits?limit=100` (ADMIN)

Operations notes:

- Each rule has `priority` (lower value applies first).
- Rule updates trigger immediate cache invalidation (no wait for periodic refresh).
- Use `simulate` first, then persist validated rules.

## 6) MCP duplicate tool-name cleanup

When multiple MCP servers expose the same tool name, Arc Reactor keeps one server
per tool and logs warnings such as:

- `Duplicate MCP tool name 'send_message' detected across servers...`

Use the cleanup script to detect and remove fully redundant servers.

- Script: `scripts/ops/cleanup_mcp_duplicate_servers.py`
- Default mode: dry-run (no deletion)

Example (dry-run):

```bash
scripts/ops/cleanup_mcp_duplicate_servers.py \
  --base-url http://localhost:8080 \
  --email admin@arc-reactor.local \
  --password 'admin-pass-123' \
  --tenant-id default
```

Apply deletion:

```bash
scripts/ops/cleanup_mcp_duplicate_servers.py \
  --base-url http://localhost:8080 \
  --email admin@arc-reactor.local \
  --password 'admin-pass-123' \
  --tenant-id default \
  --keep qa-slack-mcp \
  --apply
```

Include stale-server cleanup (for long-lived failed connections):

```bash
scripts/ops/cleanup_mcp_duplicate_servers.py \
  --base-url http://localhost:8080 \
  --email admin@arc-reactor.local \
  --password 'admin-pass-123' \
  --tenant-id default \
  --cleanup-stale \
  --stale-status FAILED \
  --stale-min-age-seconds 7200 \
  --apply
```

Notes:

- `--keep` can be repeated to pin servers that must never be deleted.
- Only servers whose entire tool set is shadowed are deletion candidates.
- `--cleanup-stale` adds stale candidates by status/age (default stale status: `FAILED`).
- Use `--max-duplicate-tools`, `--max-redundant-servers`, and `--fail-on-threshold` for alert/CI guardrails.
- Prefer running dry-run first and verifying with `GET /api/mcp/servers/{name}`.

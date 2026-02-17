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

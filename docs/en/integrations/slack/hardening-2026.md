# Slack Integration Hardening Check (2026-02)

This document records the Slack hardening updates applied to `arc-slack` based on current Slack recommendations.

## Changes Applied

1. Event deduplication by `event_id`
- Ignore duplicate Events API deliveries using Slack `event_id`.
- Log retry headers (`X-Slack-Retry-Num`, `X-Slack-Retry-Reason`).
- Related code:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventDeduplicator.kt`

2. Concurrency queue timeout in request handling
- Apply `requestTimeoutMs` to active slash command / events handling paths.
- On permit acquisition timeout:
  - Slash command: send a busy message via `response_url`
  - Event path: send a busy message in the thread
- Related code:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt`

3. Slack Web API retry improvements for 429/5xx
- Honor `Retry-After` for 429 responses first.
- Retry 5xx with bounded backoff attempts.
- Related code:
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/service/SlackMessagingService.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/config/SlackProperties.kt`
  - `arc-slack/src/main/kotlin/com/arc/reactor/slack/config/SlackAutoConfiguration.kt`

4. New operations config keys
- `arc.reactor.slack.api-max-retries`
- `arc.reactor.slack.api-retry-default-delay-ms`
- `arc.reactor.slack.event-dedup-enabled`
- `arc.reactor.slack.event-dedup-ttl-seconds`
- `arc.reactor.slack.event-dedup-max-entries`
- Default samples updated in:
  - `arc-core/src/main/resources/application.yml`

## Tests

- Run: `./gradlew :arc-slack:test`
- Result: passed

Added/updated tests:
- `SlackEventDeduplicatorTest`
- `SlackEventControllerTest` (event_id dedupe cases)
- `SlackMessagingServiceTest` (429/5xx retry cases)
- `SlackCommandControllerTest` (queue-timeout busy response cases)

## Remaining Recommendations

1. Multi-instance dedupe
- Current dedupe is in-memory and single-instance scoped.
- For multi-pod/server deployments, move dedupe to Redis (short TTL).

2. Observability expansion
- Split Slack endpoint metrics for success/failure/duplicate-drop/queue-timeout/429 in Prometheus.

3. Admin policy integration
- Apply dynamic restricted-word/sensitive-data policies to Slack input/output paths so admins can adjust behavior at runtime.

## References

- Slack Events API (retry, 3-second response, event_id): https://docs.slack.dev/apis/events-api/
- Slack Events API - HTTP Request URLs: https://docs.slack.dev/apis/events-api/using-http-request-urls/
- Slack Web API Rate Limits (`Retry-After`): https://docs.slack.dev/apis/web-api/rate-limits/
- Slash Commands: https://docs.slack.dev/interactivity/implementing-slash-commands
- Slack Java SDK issue (`X-Slack-Retry-*` access): https://github.com/slackapi/java-slack-sdk/issues/676
- Slack Bolt Python issue (idempotence / event_id): https://github.com/slackapi/bolt-python/issues/564

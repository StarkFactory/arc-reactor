#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

RUN_RUNTIME_VALIDATION="${RUN_RUNTIME_VALIDATION:-false}"

printf "==> Running Slack regression unit suite\n"
./gradlew :arc-slack:test \
  --tests "com.arc.reactor.slack.DefaultSlackCommandHandlerTest" \
  --tests "com.arc.reactor.slack.handler.SlackSlashIntentParserTest" \
  --tests "com.arc.reactor.slack.handler.SlackReminderStoreTest" \
  --tests "com.arc.reactor.slack.handler.SlackResponseTextFormatterTest" \
  --no-daemon

if [[ "$RUN_RUNTIME_VALIDATION" == "true" ]]; then
  printf "\n==> Running Slack runtime validation suite\n"
  ./scripts/dev/validate-slack-runtime.sh
else
  printf "\n==> Skipping runtime validation (set RUN_RUNTIME_VALIDATION=true to enable)\n"
fi

printf "\nPASS: Slack regression validation completed\n"

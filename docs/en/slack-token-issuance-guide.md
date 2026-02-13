# Slack Token Issuance Guide (xoxb / xapp)

This document explains how to issue Slack tokens required for Arc Reactor integration.

## 1) Which tokens do you need?

- `SLACK_BOT_TOKEN` (`xoxb-...`)
  - Used by the bot for Slack Web API calls such as `chat.postMessage`
- `SLACK_APP_TOKEN` (`xapp-...`)
  - Required only for Socket Mode (WebSocket transport)
  - Must include `connections:write` scope

## 2) Open Slack App settings

1. Go to `https://api.slack.com/apps`
2. Select your app (or create one via `Create New App`)

## 3) Issue Bot Token (`xoxb`)

1. Open `OAuth & Permissions`
2. Add required bot scopes in `Scopes`
   - Minimal example: `chat:write`, `commands`, `app_mentions:read`
   - In production, keep scope set as small as possible
3. Click `Install to Workspace` or `Reinstall to Workspace`
4. Copy `Bot User OAuth Token` (`xoxb-...`)

Environment variable:

```bash
SLACK_BOT_TOKEN=xoxb-...
```

## 4) Issue App-Level Token (`xapp`) for Socket Mode

1. Open `Settings > Basic Information`
2. In `App-Level Tokens`, click `Generate Token and Scopes`
3. Enter token name (for example `arc-reactor-socket`)
4. Select `connections:write`
5. Generate and copy the `xapp-...` token

Environment variable:

```bash
SLACK_APP_TOKEN=xapp-...
```

## 5) Enable Socket Mode

1. Open `Settings > Socket Mode`
2. Turn on `Enable Socket Mode`

Notes:
- With Socket Mode enabled, events/interactions are delivered over WebSocket instead of public HTTP callbacks.
- If you want standard webhook delivery, keep `transport-mode=events_api` and disable Socket Mode.

## 6) Arc Reactor config examples

### Events API mode

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=events_api
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
```

### Socket Mode

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=socket_mode
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
```

## 7) Post-setup validation checklist

- Verify `xoxb` token validity
  - Confirm Slack API calls succeed (for example message posting)
- Verify `xapp` token validity
  - Confirm server log contains `Slack Socket Mode gateway connected successfully`
- Reinstall after permission changes
  - Scope updates in `OAuth & Permissions` require `Reinstall to Workspace`

## 8) Security recommendations

- Do not store tokens in source code, docs, or chat logs
- Rotate immediately if exposure is suspected
- Inject via environment variables or a secret manager
- Keep scopes minimal

## 9) Official references

- Slack app management: https://api.slack.com/apps
- Token types: https://docs.slack.dev/authentication/tokens/
- OAuth install flow: https://docs.slack.dev/authentication/installing-with-oauth
- Using Socket Mode: https://docs.slack.dev/apis/events-api/using-socket-mode/
- Socket Mode overview: https://api.slack.com/apis/connections/socket
- `connections:write` scope: https://api.slack.com/scopes/connections%3Awrite
- `apps.connections.open`: https://api.slack.com/methods/apps.connections.open

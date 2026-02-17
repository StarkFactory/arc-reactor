# Slack Transport Mode Guide (Events API vs Socket Mode)

This document explains how to choose and configure Slack integration transport mode in Arc Reactor.
It is based on the latest official Slack docs for Events API, Socket Mode, and the Java SDK.

## 1) Which mode should you use?

- Use `events_api` when:
  - You can expose a stable public HTTPS callback endpoint.
  - You want the standard Slack webhook model (Events URL + Slash Command URL).
- Use `socket_mode` when:
  - Your service runs on a private/internal network.
  - Outbound WebSocket is easier than exposing inbound public callbacks.

## 2) Arc Reactor behavior by mode

- Default: `socket_mode` (when not explicitly configured)
- `events_api`
  - Active endpoints: `/api/slack/events`, `/api/slack/commands`
  - Request signature verification supported (`X-Slack-Signature`)
- `socket_mode`
  - Direct WebSocket connection to Slack
  - Slack HTTP endpoints are disabled (no public callback URL needed)
  - Signature verification filter is not applied

## 3) Common prerequisites

- Bot token (`xoxb-...`)
- Required OAuth bot scopes
- App installed/reinstalled into the workspace

## 4) Events API setup

1. Enable **Event Subscriptions** in your Slack app config.
2. Set `Request URL` to `https://<your-domain>/api/slack/events`.
3. Add required bot events (for example `app_mention`, `message.channels`).
4. Configure Slash Command `Request URL` as `https://<your-domain>/api/slack/commands`.
5. Set Arc Reactor env vars.

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=events_api
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
ARC_REACTOR_SLACK_SIGNATURE_VERIFICATION=true
```

## 5) Socket Mode setup

1. Enable **Socket Mode** in your Slack app config.
2. Create an app-level token (`xapp-...`).
3. Ensure the app-level token has `connections:write` scope.
4. Configure Event Subscriptions, Slash Commands, and Interactivity as needed.
   - In Socket Mode, app features still need to be configured the same way; only transport changes.
5. Set Arc Reactor env vars.

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=socket_mode
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
ARC_REACTOR_SLACK_SOCKET_BACKEND=java_websocket
```

Optional retry tuning:

```bash
ARC_REACTOR_SLACK_SOCKET_CONNECT_RETRY_INITIAL_DELAY_MS=1000
ARC_REACTOR_SLACK_SOCKET_CONNECT_RETRY_MAX_DELAY_MS=30000
```

## 6) application.yml example

```yaml
arc:
  reactor:
    slack:
      enabled: true
      transport-mode: socket_mode # or events_api
      bot-token: ${SLACK_BOT_TOKEN:}
      app-token: ${SLACK_APP_TOKEN:}
      signing-secret: ${SLACK_SIGNING_SECRET:}
      signature-verification-enabled: true
      socket-backend: java_websocket
      socket-connect-retry-initial-delay-ms: 1000
      socket-connect-retry-max-delay-ms: 30000
```

## 7) Runtime checklist

- Common
  - Monitor `arc.slack.inbound.total` and `arc.slack.dropped.total`
  - Validate saturation policy: `fail-fast-on-saturation`, `notify-on-drop`
- Events API
  - Inspect retry headers (`X-Slack-Retry-Num`, `X-Slack-Retry-Reason`)
  - Track signature verification failures
- Socket Mode
  - Monitor WebSocket close/error logs
  - Verify app-level token lifecycle (rotation/revocation)

## 8) Common mistakes

- Missing `SLACK_APP_TOKEN` while using `socket_mode`
- Missing `connections:write` scope on `xapp` token
- Missing request URL/signing secret in `events_api`
- Forgetting app reinstall after scope changes

## 9) Official references

- Socket Mode overview: https://api.slack.com/apis/connections/socket
- Using Socket Mode: https://docs.slack.dev/apis/events-api/using-socket-mode/
- `apps.connections.open`: https://api.slack.com/methods/apps.connections.open
- `connections:write` scope: https://api.slack.com/scopes/connections%3Awrite
- Java SDK Socket Mode guide: https://tools.slack.dev/java-slack-sdk/guides/socket-mode/

## 10) Detailed token issuance guide

- `docs/en/integrations/slack/token-issuance.md`

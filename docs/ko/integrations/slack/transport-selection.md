# Slack 전송 모드 선택/세팅 가이드 (Events API vs Socket Mode)

이 문서는 Arc Reactor에서 Slack 연동 방식을 선택하고 설정하는 방법을 정리한다.
최신 Slack 공식 가이드(Events API, Socket Mode, Java SDK)를 기준으로 작성했다.

## 1) 어떤 모드를 선택할까

- `events_api` 권장 상황
  - 인터넷에서 접근 가능한 HTTPS 콜백 URL을 안정적으로 운영할 수 있다.
  - Slack 표준 패턴(Events Request URL + Slash Command Request URL)으로 운영하고 싶다.
- `socket_mode` 권장 상황
  - 서버가 내부망에 있어 인바운드 공개 URL을 만들기 어렵다.
  - 방화벽/프록시 정책상 아웃바운드 WebSocket만 허용하는 구조가 유리하다.

## 2) Arc Reactor에서의 동작 차이

- 기본값: `socket_mode` (환경변수/설정 미지정 시)
- `events_api`
  - 활성 엔드포인트: `/api/slack/events`, `/api/slack/commands`
  - 서명 검증(`X-Slack-Signature`) 사용 가능
- `socket_mode`
  - Slack과 WebSocket으로 직접 연결
  - HTTP Slack 엔드포인트 비활성화(공개 콜백 URL 불필요)
  - 서명 검증 필터는 적용되지 않음(Slack 공식 문서도 Socket Mode에서는 서명 검증이 필수 경로가 아님)

## 3) 공통 준비

- Slack Bot Token(`xoxb-...`) 발급
- 봇에 필요한 OAuth Scope 부여
- 워크스페이스에 앱 설치/재설치

## 4) Events API 설정 방법

1. Slack App 설정에서 **Event Subscriptions** 활성화
2. `Request URL`에 `https://<your-domain>/api/slack/events` 등록
3. 필요한 Bot Event(예: `app_mention`, `message.channels`) 추가
4. Slash Commands 설정에서 `Request URL`을 `https://<your-domain>/api/slack/commands`로 설정
5. Arc Reactor 환경변수 설정

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=events_api
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
ARC_REACTOR_SLACK_SIGNATURE_VERIFICATION=true
```

## 5) Socket Mode 설정 방법

1. Slack App 설정에서 **Socket Mode** 활성화
2. App-level Token(`xapp-...`) 생성
3. App-level Token에 `connections:write` scope 포함
4. Slash Commands, Event Subscriptions, Interactivity를 필요에 맞게 켠다
   - Socket Mode에서도 Slack 앱 기능 자체는 동일하게 설정해야 이벤트가 전달된다.
5. Arc Reactor 환경변수 설정

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=socket_mode
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
ARC_REACTOR_SLACK_SOCKET_BACKEND=java_websocket
```

선택 옵션:

```bash
ARC_REACTOR_SLACK_SOCKET_CONNECT_RETRY_INITIAL_DELAY_MS=1000
ARC_REACTOR_SLACK_SOCKET_CONNECT_RETRY_MAX_DELAY_MS=30000
```

## 6) application.yml 예시

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

## 7) 운영 시 권장 체크리스트

- 공통
  - `arc.slack.inbound.total`, `arc.slack.dropped.total` 메트릭 모니터링
  - 포화 시 정책 확인: `fail-fast-on-saturation`, `notify-on-drop`
- Events API
  - Slack 재시도 헤더(`X-Slack-Retry-Num`, `X-Slack-Retry-Reason`) 로그 점검
  - 서명 검증 실패율 점검
- Socket Mode
  - WebSocket close/error 로그 점검
  - App-level Token 만료/회수 여부 점검

## 8) 자주 하는 실수

- `socket_mode`인데 `SLACK_APP_TOKEN` 누락
- `xapp` 토큰에 `connections:write` scope 미부여
- Events API 모드에서 `Request URL`/서명 시크릿 누락
- 앱 권한 변경 후 재설치 미실행

## 9) 공식 레퍼런스

- Slack Socket Mode 개요: https://api.slack.com/apis/connections/socket
- Socket Mode 사용 가이드: https://docs.slack.dev/apis/events-api/using-socket-mode/
- `apps.connections.open`: https://api.slack.com/methods/apps.connections.open
- `connections:write` scope: https://api.slack.com/scopes/connections%3Awrite
- Slack Java SDK Socket Mode: https://tools.slack.dev/java-slack-sdk/guides/socket-mode/

## 10) 토큰 발급 상세 가이드

- `docs/ko/integrations/slack/token-issuance.md`

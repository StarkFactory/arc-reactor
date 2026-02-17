# Slack 토큰 발급 가이드 (xoxb / xapp)

이 문서는 Arc Reactor의 Slack 연동에 필요한 토큰을 Slack App 콘솔에서 발급하는 방법을 정리한다.

## 1) 어떤 토큰이 필요한가

- `SLACK_BOT_TOKEN` (`xoxb-...`)
  - 봇이 `chat.postMessage` 같은 Web API 호출을 할 때 사용
- `SLACK_APP_TOKEN` (`xapp-...`)
  - Socket Mode(WebSocket 연결)에서만 필요
  - 반드시 `connections:write` scope 필요

## 2) Slack App 콘솔 진입

1. `https://api.slack.com/apps` 접속
2. 대상 앱 선택 (없으면 `Create New App`으로 생성)

## 3) Bot Token (`xoxb`) 발급

1. 왼쪽 메뉴 `OAuth & Permissions` 이동
2. `Scopes`에서 필요한 Bot Token Scope 추가
   - 최소 예시: `chat:write`, `commands`, `app_mentions:read`
   - 운영 시 실제 기능에 맞는 최소 권한만 부여
3. 페이지 상단/하단 `Install to Workspace` 또는 `Reinstall to Workspace` 실행
4. 발급된 `Bot User OAuth Token` 값(`xoxb-...`) 확인

환경변수 예시:

```bash
SLACK_BOT_TOKEN=xoxb-...
```

## 4) App-Level Token (`xapp`) 발급 (Socket Mode용)

1. 왼쪽 메뉴 `Settings > Basic Information` 이동
2. `App-Level Tokens` 섹션에서 `Generate Token and Scopes` 클릭
3. Token Name 입력 (예: `arc-reactor-socket`)
4. scope로 `connections:write` 선택
5. 생성 후 `xapp-...` 값 복사

환경변수 예시:

```bash
SLACK_APP_TOKEN=xapp-...
```

## 5) Socket Mode 활성화

1. 왼쪽 메뉴 `Settings > Socket Mode` 이동
2. `Enable Socket Mode` 토글 ON

주의:
- Socket Mode를 켜면 이벤트/인터랙션은 HTTP 대신 WebSocket으로 전달된다.
- Events API를 계속 쓰려면 `transport-mode=events_api`로 두고 Socket Mode를 끄면 된다.

## 6) Arc Reactor 설정 예시

### Events API 모드

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=events_api
SLACK_BOT_TOKEN=xoxb-...
SLACK_SIGNING_SECRET=...
```

### Socket Mode 모드

```bash
ARC_REACTOR_SLACK_ENABLED=true
ARC_REACTOR_SLACK_TRANSPORT_MODE=socket_mode
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
```

## 7) 발급 후 검증 체크리스트

- `xoxb`가 유효한지
  - API 호출 성공 여부(예: 메시지 전송)로 확인
- `xapp`가 유효한지
  - 서버 로그에 `Slack Socket Mode gateway connected successfully` 확인
- scope 변경 후 재설치했는지
  - `OAuth & Permissions` 변경 후 `Reinstall to Workspace` 필요

## 8) 보안 운영 권장사항

- 토큰을 코드/문서/채팅에 평문으로 남기지 말 것
- 노출 의심 시 즉시 rotate(폐기 후 재발급)
- 환경변수 또는 Secret Manager로만 주입
- 최소 권한(scope 최소화) 유지

## 9) 공식 레퍼런스

- Slack 앱 관리: https://api.slack.com/apps
- Tokens 개요: https://docs.slack.dev/authentication/tokens/
- OAuth 설치 흐름: https://docs.slack.dev/authentication/installing-with-oauth
- Socket Mode 사용: https://docs.slack.dev/apis/events-api/using-socket-mode/
- Socket Mode 개요: https://api.slack.com/apis/connections/socket
- `connections:write` scope: https://api.slack.com/scopes/connections%3Awrite
- `apps.connections.open`: https://api.slack.com/methods/apps.connections.open

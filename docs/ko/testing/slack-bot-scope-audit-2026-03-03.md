# Slack Bot 권한(scope) 점검 리포트 (기준일: 2026-03-03)

## 0) 지금 바로 요청할 scope (요약)

아래 세트로 먼저 앱 권한을 요청하면 현재 Arc Reactor 구현 + 확장(Canvas 포함) 기준으로 바로 시작 가능:

- Bot Token Scopes:
  - `app_mentions:read`
  - `chat:write`
  - `commands`
  - `channels:read`
  - `channels:history`
  - `groups:read`
  - `groups:history`
  - `im:read`
  - `im:history`
  - `mpim:read`
  - `mpim:history`
  - `users:read`
  - `users:read.email`
  - `reactions:write`
  - `files:write`
  - `canvases:write`
- 선택(운영 편의):
  - `chat:write.public` (봇 미참여 public 채널 쓰기 필요 시)
  - `channels:join` (자동 채널 조인 필요 시)
- App-level token(Socket Mode):
  - `connections:write`
- 별도 주의:
  - `search:read`는 user token 중심 제약 이슈가 있어 기본 권장 세트에서는 제외.
    필요한 경우에만 별도 토큰 전략으로 도입.

## 점검 범위

- Arc Reactor 백엔드 구현 기준 실제 사용 Slack 기능 분석
- Slack 공식 문서(2026-03-03 확인) 기준 scope 존재 여부/요구사항 대조
- DM/채널/스레드 답변 요구사항 충족 여부
- Canvas API 권한 가능 여부 및 "내 것만 생성/수정" 정책 가능성

## 1) 현재 백엔드 구현 기능 (코드 기준)

### A. 대화 진입/응답

- `app_mention` 이벤트 처리 후 스레드 답변
- `message` 이벤트는 기본적으로 `thread_ts`가 있을 때 처리
- 옵션으로 DM top-level 메시지 자동응답 가능:
  - `ARC_REACTOR_SLACK_PROCESS_DM_WITHOUT_THREAD=true`
- Slash Command(`/api/slack/commands`) 지원
- Socket Mode 지원 (`appToken` 필수)
- Slack 응답은 `chat.postMessage` + 필요 시 `response_url` 폴백

코드 근거:

- 이벤트 라우팅: `arc-slack/.../SlackEventProcessor.kt`
- 메시지 전송: `arc-slack/.../SlackMessagingService.kt`
- Slash endpoint: `arc-slack/.../SlackCommandController.kt`
- Socket Mode: `arc-slack/.../SlackSocketModeGateway.kt`

### B. Slack Tools (LLM Tool)

기본 11개 도구 + Canvas 옵션 2개가 구현되어 있음:

- 기본: `send_message`, `reply_to_thread`, `list_channels`, `find_channel`, `read_messages`, `read_thread_replies`, `add_reaction`, `get_user_info`, `find_user`, `search_messages`, `upload_file`
- Canvas(옵션): `create_canvas`, `append_canvas`
- Canvas 활성화: `ARC_REACTOR_SLACK_TOOLS_CANVAS_ENABLED=true`

코드 근거:

- 도구 scope 매핑: `arc-slack/.../SlackScopeAwareLocalToolFilter.kt`
- Slack API 호출 메서드: `arc-slack/.../SlackApiClient.kt`
- 헬스체크 required scopes: `arc-slack/.../SlackApiHealthIndicator.kt`

## 2) 공식 문서 기준 scope/기능 존재 여부

아래는 공식 문서에서 확인된 핵심 사항:

- `chat.postMessage`: 존재, `chat:write` 필요
- `conversations.history`: 존재, 채널 타입별로 `channels:history`/`groups:history`/`im:history`/`mpim:history`
- `conversations.replies`: 존재, 채널 타입별 history scope 요구
- `conversations.list`: 존재, `channels:read`/`groups:read`/`im:read`/`mpim:read` 조합
- `users.info`, `users.list`: 존재, `users:read` (+ 이메일은 `users:read.email`)
- `reactions.add`: 존재, `reactions:write`
- 파일 업로드(v2 경로): `files.getUploadURLExternal`, `files.completeUploadExternal` 존재, `files:write`
- `search.messages`: 존재, `search:read` 필요
- Socket Mode: 존재, App-level 토큰 + `connections:write` 필요
- Slash commands scope: `commands` 존재
- Canvas API: `canvases.create`, `canvases.edit`, `canvases.access.set` 존재
- Canvas scope: `canvases:write`, `canvases:read` 존재

## 3) 요구사항 대비 갭 분석

요구사항: "답변/스레드 답변/채널 답변/DM 답변 기본 지원"

### 충족

- 채널 답변: 가능 (`chat.postMessage`)
- 스레드 답변: 가능 (`thread_ts` 사용)
- Slash command 기반 응답: 가능

### 현재 상태/주의점

1. 일반 DM 메시지 자동응답
- 기본은 기존과 동일(스레드 중심)이며, 운영 판단에 따라 옵션으로 활성화 가능.
- 설정: `ARC_REACTOR_SLACK_PROCESS_DM_WITHOUT_THREAD=true`

2. private/DM scope 모델링
- scope-aware 로직에 conversation type 기반 any-scope 매칭을 추가함.
- 설정: `ARC_REACTOR_SLACK_TOOLS_CONVERSATION_SCOPE_MODE`
  - `public_only` (기본)
  - `include_private_and_dm` (private/DM scope 포함)

3. `search_messages`의 토큰 타입 이슈(지속)
- `search:read`는 공식 scope 페이지 기준 user token 중심으로 안내됨.
- 현재 Slack Tools는 bot token(`xoxb`) 전제로 구성되어 있어 `search_messages`는 운영에서 실패 가능성이 큼.

4. Canvas allowlist 가드
- `append_canvas`는 allowlist 등록된 canvasId만 수정 가능.
- allowlist는 `create_canvas` 성공 시 자동 등록됨.
- 설정:
  - `ARC_REACTOR_SLACK_TOOLS_CANVAS_ALLOWLIST_ENFORCED=true` (기본)
  - `ARC_REACTOR_SLACK_TOOLS_CANVAS_MAX_OWNED_IDS=5000`

## 4) 권장 scope 세트

### A. Core (채널/스레드/멘션/슬래시)

- `chat:write`
- `app_mentions:read`
- `channels:history`
- `commands`

Socket Mode 사용 시 추가:

- App-level token scope: `connections:write`

### B. Core + private 채널 + DM까지 확장

- Core 전체 +
- `groups:history`
- `im:history`
- `mpim:history`

### C. Slack Tools 전체(현재 구현 기준)

- `chat:write`
- `channels:read`
- `groups:read` (private channel list/find 위해 권장)
- `channels:history`
- `groups:history` (private message read 위해 권장)
- `users:read`
- `users:read.email`
- `reactions:write`
- `files:write`
- `search:read` (단, user token 전략 별도 필요)
- `canvases:write` (Canvas 도구 사용 시)

선택 권장:

- `chat:write.public` (봇이 아직 참여하지 않은 public 채널에 쓰기 필요 시)
- `channels:join` (자동 조인 워크플로우 필요 시)

## 5) Canvas 가능 여부 (핵심 질문)

질문: "Canvas 접근 가능한가? 토큰 권한 가질 수 있나? 남의 캔버스 수정 없이 내 것만 새로 작성 가능?"

결론:

1. 가능 여부
- 공식 문서에 Canvas API/Scope가 존재함.
- `canvases:write` scope는 bot/user token 모두 가능으로 문서에 표시됨.

2. 내 것만 생성
- `canvases.create`는 "행위 주체가 소유(owner)인 standalone canvas"를 생성하는 메서드로 문서화되어 있음.
- 따라서 "새로 내 캔버스 생성"은 가능.

3. 남의 캔버스 수정 제한
- `canvases.edit`는 해당 캔버스에 write access가 있어야 수정 가능.
- 즉 권한이 없으면 수정 불가.
- 다만 scope 자체만으로 "내가 만든 캔버스만" 강제하는 옵션은 없음.
- 백엔드 정책으로 생성된 canvas ID를 추적하고, 허용 리스트 외 edit를 차단하는 방식이 필요.

4. 소유권/접근 제어
- `canvases.access.set`는 소유권 변경/권한 설정 API이며, 소유권 변경은 현재 owner만 수행 가능.
- 문서에 paid plan 관련 제약이 명시되어 있으므로 워크스페이스 플랜 확인 필요.

## 6) 코드 반영 상태

1. DM 기본 응답 옵션
- 반영 완료: `message + thread_ts == null` DM 처리 옵션 추가.

2. scope-aware 필터 정교화
- 반영 완료: `requiredAny` 매칭으로 private/DM scope 제어 가능.

3. `search_messages` 토큰 전략 분리
- bot token만 쓰는 환경에서는 기본 비활성화 권장.
- 필요 시 user token 기반 별도 클라이언트/권한 모델 도입.

4. Canvas 안전 가드
- 반영 완료: `create_canvas`로 생성한 canvas ID만 `append_canvas`에서 수정 허용.

## 7) 공식 문서 출처

- Slack scopes overview: https://docs.slack.dev/authentication/scopes/
- chat.postMessage: https://docs.slack.dev/reference/methods/chat.postMessage
- conversations.list: https://docs.slack.dev/reference/methods/conversations.list
- conversations.history: https://docs.slack.dev/reference/methods/conversations.history
- conversations.replies: https://docs.slack.dev/reference/methods/conversations.replies
- users.info: https://docs.slack.dev/reference/methods/users.info
- users.list: https://docs.slack.dev/reference/methods/users.list
- reactions.add: https://docs.slack.dev/reference/methods/reactions.add
- files.getUploadURLExternal: https://docs.slack.dev/reference/methods/files.getUploadURLExternal
- files.completeUploadExternal: https://docs.slack.dev/reference/methods/files.completeUploadExternal
- search.messages: https://docs.slack.dev/reference/methods/search.messages
- search:read scope: https://docs.slack.dev/reference/scopes/search.read/
- commands scope: https://docs.slack.dev/reference/scopes/commands/
- chat:write.public scope: https://docs.slack.dev/reference/scopes/chat.write.public/
- channels:join scope: https://docs.slack.dev/reference/scopes/channels.join/
- Socket Mode guide: https://docs.slack.dev/apis/events-api/using-socket-mode/
- apps.connections.open: https://docs.slack.dev/reference/methods/apps.connections.open/
- connections:write scope: https://docs.slack.dev/reference/scopes/connections.write/
- app_mention event: https://docs.slack.dev/reference/events/app_mention/
- message.channels event: https://docs.slack.dev/reference/events/message.channels/
- message.groups event: https://docs.slack.dev/reference/events/message.groups/
- message.im event: https://docs.slack.dev/reference/events/message.im/
- message.mpim event: https://docs.slack.dev/reference/events/message.mpim/
- canvases.create: https://docs.slack.dev/reference/methods/canvases.create/
- canvases.edit: https://docs.slack.dev/reference/methods/canvases.edit/
- canvases.access.set: https://docs.slack.dev/reference/methods/canvases.access.set/
- canvases:write scope: https://docs.slack.dev/reference/scopes/canvases.write/
- canvases:read scope: https://docs.slack.dev/reference/scopes/canvases.read/

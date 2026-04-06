# Slack 봇 설정 가이드

Arc Reactor와 연결할 Slack 앱을 만드는 과정.

---

## 1. Slack 앱 생성

1. https://api.slack.com/apps → **Create New App** → **From scratch**
2. 앱 이름: `Reactor` (또는 원하는 이름)
3. 워크스페이스 선택 → **Create App**

## 2. Socket Mode 활성화

왼쪽 메뉴 → **Socket Mode** → **Enable Socket Mode** ON

## 3. App-Level Token 생성

왼쪽 메뉴 → **Basic Information** → **App-Level Tokens** → **Generate Token**
- Token Name: `socket-mode`
- Scope: `connections:write` 추가
- **Generate** → `xapp-...` 토큰 복사 → 환경변수 `SLACK_APP_TOKEN`에 저장

## 4. Bot Token Scopes 설정

왼쪽 메뉴 → **OAuth & Permissions** → **Bot Token Scopes** → 아래 16개 추가:

- `app_mentions:read` — @멘션 수신
- `chat:write` — 메시지 전송
- `channels:read` — public 채널 정보
- `channels:history` — public 채널 메시지 읽기
- `groups:read` — private 채널 정보
- `groups:history` — private 채널 메시지 읽기
- `im:read` — DM 정보
- `im:history` — DM 메시지 읽기
- `mpim:read` — 그룹 DM 정보
- `mpim:history` — 그룹 DM 메시지 읽기
- `users:read` — 사용자 프로필 조회
- `users:read.email` — 사용자 이메일 조회
- `reactions:write` — 이모지 리액션 추가
- `commands` — Slash Command 사용
- `files:write` — 파일 업로드
- `assistant:write` — Agents & AI Apps 상태/프롬프트 설정 (선택)
- `canvases:write` — 캔버스 편집 (선택)

## 5. Event Subscriptions 설정

왼쪽 메뉴 → **Event Subscriptions** → **Enable Events** ON

**Subscribe to bot events** → 아래 8개 추가:

| 이벤트 | 용도 |
|--------|------|
| `app_mention` | @멘션 이벤트 수신 |
| `message.channels` | public 채널 메시지 수신 (스레드 자동 응답) |
| `message.groups` | private 채널 메시지 수신 |
| `message.im` | DM 메시지 수신 |
| `message.mpim` | 그룹 DM 메시지 수신 |
| `reaction_added` | 이모지 반응 피드백 수집 |
| `assistant_thread_started` | Agents & AI Apps 대화 시작 |
| `assistant_thread_context_changed` | Agents & AI Apps 채널 전환 |

**Save Changes** 클릭

## 6. Agents & AI Apps 활성화 (선택)

Slack 사이드바에서 봇을 클릭하여 바로 대화를 시작할 수 있는 기능.

왼쪽 메뉴 → **Agents & AI Apps** (또는 **App Home**) → 설정:

1. **App Home** → **Show Tabs** → **Messages Tab** ON
2. Bot Events에 `assistant_thread_started`, `assistant_thread_context_changed` 추가 (5단계에서 이미 추가됨)
3. Bot Token Scopes에 `assistant:write` 추가 (Suggested Prompts, 상태 표시 등 사용 시)

활성화 후 Slack 사이드바 → **Apps** → Reactor 클릭 → DM처럼 대화 가능.

> **참고**: `assistant:write` scope는 `assistant.threads.setStatus`, `assistant.threads.setSuggestedPrompts`, `assistant.threads.setTitle` API 호출에 필요. 기본 대화만 할 경우 없어도 동작함.

## 7. Slash Command 등록 (선택)

왼쪽 메뉴 → **Slash Commands** → **Create New Command**
- Command: `/reactor`
- Short Description: `Reactor AI 업무 어시스턴트`
- Usage Hint: `[질문 또는 명령어]`
- **Save**

## 8. 앱 설치

왼쪽 메뉴 → **Install App** → **Install to Workspace** → **Allow**

**Bot User OAuth Token** (`xoxb-...`) 복사 → 환경변수 `SLACK_BOT_TOKEN`에 저장

> **중요**: Scope나 Event 추가/변경 후 반드시 **Reinstall to Workspace** 실행. 재설치 전까지 변경사항 미반영.

## 9. 환경변수 설정

```
SLACK_BOT_TOKEN=xoxb-...        # Bot User OAuth Token
SLACK_APP_TOKEN=xapp-...        # App-Level Token
SLACK_CHANNEL_ID=C0AQ...        # 기본 채널 ID
ARC_REACTOR_SLACK_ENABLED=true
```

## 10. 봇 채널 초대

사용할 Slack 채널에서 `/invite @Reactor` 실행

## 11. 검증

채널에서 `@Reactor 안녕` 멘션 → 응답 오면 성공!

---

## 주의사항

- **Bot Token과 App Token은 같은 앱에서 발급해야 함** — 다른 앱 조합이면 Socket Mode 연결은 되지만 이벤트가 안 옴
- **Scope/Event 변경 후 Reinstall 필수** — 변경사항은 재설치 전까지 반영 안 됨
- **Granular 토큰 판별법** — 토큰이 `ATATT3xFfGF0`으로 시작하면 API Gateway 모드 필요
- **Private 채널** — 봇이 해당 채널에 초대되어 있어야 메시지 수신 가능

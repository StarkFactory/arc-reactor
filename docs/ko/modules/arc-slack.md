# arc-slack

## 개요

arc-slack은 Arc Reactor를 Slack에 연결합니다. 들어오는 메시지(Events API 및 Socket Mode), 슬래시 명령어, 요청 서명 검증, 배압(backpressure) 제한을 처리한 뒤 `AgentExecutor`에 위임하고, 에이전트 응답을 Slack 채널 또는 스레드로 전송합니다.

주요 기능:

- **이중 전송 방식** — Socket Mode(WebSocket, 공개 URL 불필요) 또는 Events API(HTTP 콜백); 배포 환경에 따라 설정
- **스레드 인식 세션** — Slack 스레드 타임스탬프가 `sessionId`에 매핑되어 스레드 내 대화 이력 유지
- **HMAC-SHA256 서명 검증** — timing-safe 비교와 재생 공격 방지(5분 허용 윈도우)로 모든 수신 요청을 Slack 서명 시크릿으로 검증
- **배압 제한** — `maxConcurrentRequests` 초과 시 세마포어 기반 리미터가 이벤트를 드롭하거나 큐에 넣음; fail-fast 모드는 큐 없이 즉시 거부
- **이벤트 중복 제거** — 인메모리 LRU 캐시에 `event_id`를 저장하여 at-least-once 전달에서 중복 처리 방지
- **Slack API 오류 재시도** — `SlackMessagingService`가 `429` 및 `5xx` 응답 시 설정 가능한 지연으로 재시도
- **Micrometer 연동** — `MeterRegistry`가 있을 때 `MicrometerSlackMetricsRecorder`로 선택적 메트릭 수집

## 활성화

**프로퍼티:**
```yaml
arc:
  reactor:
    slack:
      enabled: true
      bot-token: "xoxb-..."
      signing-secret: "..."
      # Socket Mode 사용 시 (기본값):
      app-token: "xapp-..."
      transport-mode: SOCKET_MODE
```

**Gradle 의존성:**
```kotlin
implementation("com.arc.reactor:arc-slack")
```

이벤트 및 명령어 핸들러를 활성화하려면 `AgentExecutor` 빈이 있어야 합니다. 없으면 메시징 서비스와 서명 검증기만 등록됩니다.

## Slack 로컬 도구(LocalTool)

이제 `arc-slack`은 별도 외부 Slack 어댑터 없이도 Slack 도구를 Arc Reactor `LocalTool` 빈으로 직접 제공합니다.

활성화 설정:

```yaml
arc:
  reactor:
    slack:
      tools:
        enabled: true
        bot-token: "${SLACK_BOT_TOKEN}"
        tool-exposure:
          scope-aware-enabled: true
          fail-open-on-scope-resolution-error: true
```

활성화 시 아래 11개 도구가 등록됩니다:
- `send_message`
- `reply_to_thread`
- `list_channels`
- `find_channel`
- `read_messages`
- `read_thread_replies`
- `add_reaction`
- `get_user_info`
- `find_user`
- `search_messages`
- `upload_file`

`tool-exposure.scope-aware-enabled=true`이면 Slack OAuth scope를 기준으로 도구 노출이 자동 필터링됩니다.

## 주요 컴포넌트

| 클래스 | 역할 |
|---|---|
| `SlackAutoConfiguration` | 모든 빈 연결; 조건부로 Socket Mode 또는 Events API 빈 생성 |
| `SlackSocketModeGateway` | 지수 백오프 재연결로 WebSocket 연결을 유지하는 `SmartLifecycle` 빈 |
| `SlackEventController` | Events API 콜백용 HTTP 엔드포인트 (`POST /api/slack/events`) |
| `SlackCommandController` | 슬래시 명령어용 HTTP 엔드포인트 (`POST /api/slack/commands`) |
| `SlackEventProcessor` | 중복 제거와 배압을 적용한 뒤 `SlackEventHandler`로 디스패치 |
| `SlackCommandProcessor` | 배압 적용 후 `SlackCommandHandler`로 디스패치 |
| `SlackBackpressureLimiter` | 세마포어 기반 동시성 가드; fail-fast 또는 큐 모드 |
| `SlackEventDeduplicator` | 처리된 `event_id` 값의 인메모리 LRU 캐시 |
| `DefaultSlackEventHandler` | 봇 멘션 태그 제거, 스레드를 세션으로 매핑, `AgentExecutor` 호출 후 응답 전송 |
| `DefaultSlackCommandHandler` | 슬래시 명령어 처리; 즉시 ack 전송 후 에이전트로 처리 |
| `SlackMessagingService` | 재시도가 포함된 Slack Web API 래퍼 (`chat.postMessage`, `chat.postEphemeral`, response URL) |
| `SlackSignatureVerifier` | timing-safe 비교를 사용하는 HMAC-SHA256 검증기 |
| `SlackSignatureWebFilter` | 컨트롤러 도달 전 모든 요청을 검증하는 WebFlux 필터 (Events API 전용) |
| `SlackSystemPromptFactory` | Slack 발신 에이전트 호출에 주입되는 system prompt 생성 |
| `SlackMetricsRecorder` | Slack 특화 메트릭 기록 인터페이스 (NoOp 또는 Micrometer 구현체) |

## 설정

모든 프로퍼티는 `arc.reactor.slack` 접두사를 사용합니다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모듈 활성화 |
| `transport-mode` | `SOCKET_MODE` | `SOCKET_MODE` 또는 `EVENTS_API` |
| `bot-token` | `""` | Slack Bot User OAuth Token (`xoxb-...`) |
| `app-token` | `""` | Socket Mode용 Slack App-Level Token (`xapp-...`) |
| `signing-secret` | `""` | 서명 검증을 위한 Slack signing secret |
| `signature-verification-enabled` | `true` | HMAC-SHA256 요청 검증 활성화 |
| `timestamp-tolerance-seconds` | `300` | 서명 검증에서 허용하는 최대 시간 오차 |
| `max-concurrent-requests` | `5` | 동시 에이전트 실행 최대 수 |
| `request-timeout-ms` | `30000` | 에이전트 실행 타임아웃 (ms) |
| `fail-fast-on-saturation` | `true` | 용량 초과 시 큐에 넣지 않고 즉시 거부 |
| `notify-on-drop` | `false` | 이벤트 드롭 시 Slack에 "busy" 메시지 전송 |
| `api-max-retries` | `2` | `429`/`5xx`에서 Slack API 재시도 횟수 |
| `api-retry-default-delay-ms` | `1000` | `Retry-After` 헤더 없을 때 기본 재시도 지연 |
| `event-dedup-enabled` | `true` | 인메모리 이벤트 중복 제거 활성화 |
| `event-dedup-ttl-seconds` | `600` | 중복 제거 캐시 TTL (초) |
| `event-dedup-max-entries` | `10000` | 중복 제거 캐시 최대 항목 수 |
| `socket-backend` | `JAVA_WEBSOCKET` | WebSocket 백엔드: `JAVA_WEBSOCKET` 또는 `TYRUS` |
| `socket-connect-retry-initial-delay-ms` | `1000` | Socket Mode 재연결 초기 지연 |
| `socket-connect-retry-max-delay-ms` | `30000` | Socket Mode 재연결 최대 지연 |

## 연동

**에이전트 실행:**

`DefaultSlackEventHandler`는 다음 정보와 함께 `AgentExecutor.execute()`를 호출합니다:
- `systemPrompt` — `SlackSystemPromptFactory`가 생성 (설정된 LLM provider 참조)
- `userId` — Slack 사용자 ID
- `sessionId` — `"slack-{channelId}-{threadTs}"` (스레드 범위 대화 메모리)
- `metadata` — `source=slack`, `channel=slack`

**스레드-세션 매핑:**

Slack 스레드 타임스탬프(`thread_ts`)가 `sessionId` 값이 됩니다. 같은 스레드의 답글은 동일한 세션을 재사용하여 에이전트가 전체 대화 이력에 접근할 수 있습니다.

**서명 검증 (Events API):**

`SlackSignatureWebFilter`가 Slack 엔드포인트의 모든 요청을 가로채고, raw body를 읽어 `v0=HMAC-SHA256(signingSecret, "v0:{timestamp}:{body}")`를 계산한 뒤 `MessageDigest.isEqual`(timing-safe)로 비교합니다. `timestamp-tolerance-seconds`보다 오래된 요청은 서명과 관계없이 거부됩니다.

## 코드 예시

**최소 설정 (Socket Mode):**

```yaml
arc:
  reactor:
    slack:
      enabled: true
      bot-token: "${SLACK_BOT_TOKEN}"
      app-token: "${SLACK_APP_TOKEN}"
      signing-secret: "${SLACK_SIGNING_SECRET}"
```

**Events API 설정 (공개 URL 필요):**

```yaml
arc:
  reactor:
    slack:
      enabled: true
      transport-mode: EVENTS_API
      bot-token: "${SLACK_BOT_TOKEN}"
      signing-secret: "${SLACK_SIGNING_SECRET}"
      max-concurrent-requests: 10
      fail-fast-on-saturation: false
      notify-on-drop: true
```

**커스텀 이벤트 핸들러:**

```kotlin
@Component
class MySlackEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService
) : SlackEventHandler {

    override suspend fun handleAppMention(command: SlackEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(userPrompt = command.text, userId = command.userId)
        )
        messagingService.sendMessage(
            channelId = command.channelId,
            text = result.content ?: "No response",
            threadTs = command.threadTs
        )
    }

    override suspend fun handleMessage(command: SlackEventCommand) {
        // 다이렉트 메시지 처리
    }
}
```

## 주의사항

**Socket Mode는 `app-token`이 필요합니다.** `transport-mode=SOCKET_MODE`이고 `app-token`이 비어 있으면, `SlackSocketModeGateway.start()`에서 즉시 `IllegalStateException`이 발생합니다.

**서명 검증은 Events API에만 적용됩니다.** Socket Mode에서는 Slack이 WebSocket 수준에서 인증을 처리합니다. `SlackSignatureWebFilter`는 `transport-mode=events_api`일 때만 등록됩니다.

**`notify-on-drop=true`는 부하를 가중시킬 수 있습니다.** 시스템이 과부하 상태일 때 Slack에 "바쁩니다" 메시지를 보내려면 추가 API 호출이 필요하고, 이로 인해 상황이 악화될 수 있습니다. 고부하 환경에서는 `false`로 유지하세요.

**중복 제거는 인메모리입니다.** 중복 제거 캐시는 인스턴스 간에 공유되지 않습니다. 수평 확장 배포에서 동일한 이벤트가 인스턴스당 한 번씩 처리될 수 있습니다. 정확히 한 번 처리가 필요하다면 외부 중복 제거 저장소(Redis, DB)를 사용하세요.

**`event-dedup-ttl-seconds`는 Slack의 재시도 윈도우보다 커야 합니다.** Slack은 Events API 전달 실패 시 수 분 동안 재시도합니다. 기본값 600초는 일반적인 재시도 윈도우를 커버합니다.

**대화형 페이로드(interactive payloads)는 지원하지 않습니다.** Block Kit 버튼 클릭, 모달 제출 등 대화형 페이로드는 ack 후 드롭됩니다.

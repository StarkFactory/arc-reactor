# arc-discord

## 개요

arc-discord는 Discord4J 리액티브 라이브러리를 사용하여 Arc Reactor를 Discord에 연결합니다. Discord 게이트웨이의 메시지 이벤트를 수신하고, 선택적으로 멘션 전용 응답으로 필터링하며, 동시성 한도를 적용한 뒤 `AgentExecutor`에 위임합니다. 응답은 원래 채널로 전송됩니다.

이 모듈은 Discord4J의 WebSocket 게이트웨이를 사용하며(웹훅 아님), 공개 URL이 필요하지 않습니다.

## 활성화

**프로퍼티:**
```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
```

**Gradle 의존성:**
```kotlin
implementation("com.arc.reactor:arc-discord")
```

이벤트 핸들러를 활성화하려면 `AgentExecutor` 빈이 있어야 합니다. `GatewayDiscordClient`는 `DiscordClientBuilder.create(token).build().login().block()`을 통해 애플리케이션 시작 시 동기적으로 Discord에 연결합니다. 토큰이 유효하지 않거나 Discord에 접근할 수 없으면 시작에 실패합니다.

## 주요 컴포넌트

| 클래스 | 역할 |
|---|---|
| `DiscordAutoConfiguration` | 모든 빈 연결; `ApplicationReadyEvent`에서 `DiscordMessageListener` 시작 |
| `GatewayDiscordClient` | Discord4J 게이트웨이 클라이언트 (시작 시 블로킹 로그인) |
| `DiscordMessageListener` | `MessageCreateEvent` 구독; 봇 필터링 및 멘션 전용 모드 적용 |
| `DefaultDiscordEventHandler` | 멘션 태그 제거, 채널을 세션으로 매핑, `AgentExecutor` 호출 후 응답 전송 |
| `DiscordMessagingService` | Discord 채널에 메시지 전송; 2000자 제한 초과 응답 분할 |
| `DiscordEventCommand` | 채널 ID, 사용자 ID, 내용, 메시지 ID, 길드 ID를 담는 내부 데이터 클래스 |

## 설정

모든 프로퍼티는 `arc.reactor.discord` 접두사를 사용합니다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모듈 활성화 |
| `token` | `""` | Discord 봇 토큰 |
| `max-concurrent-requests` | `5` | 동시 에이전트 실행 최대 수 |
| `request-timeout-ms` | `30000` | 에이전트 실행 타임아웃 (ms) |
| `respond-to-mentions-only` | `true` | 봇이 멘션될 때만 응답 (`@BotName`) |

## 연동

**에이전트 실행:**

`DefaultDiscordEventHandler`는 다음 정보와 함께 `AgentExecutor.execute()`를 호출합니다:
- `systemPrompt` — "You are a helpful AI assistant responding in a Discord channel. Keep responses concise and well-formatted for Discord."
- `userId` — Discord 사용자 ID (Snowflake 문자열)
- `sessionId` — `"discord-{channelId}"` (채널 범위 세션; 채널의 모든 메시지가 하나의 세션을 공유)
- `metadata` — `source=discord`

**멘션 전용 모드:**

`respond-to-mentions-only=true`(기본값)일 때, `DiscordMessageListener`는 메시지의 `userMentionIds`에 봇의 Snowflake ID가 포함되어 있는지 확인합니다. 봇을 멘션하지 않은 메시지는 조용히 무시됩니다.

**메시지 분할:**

Discord는 메시지당 2000자 제한이 있습니다. `DiscordMessagingService`는 2000자를 초과하는 응답을 연속된 여러 메시지로 분할하여 API 오류를 방지합니다.

**동시성 제어:**

`DiscordMessageListener`는 `Semaphore(maxConcurrentRequests)`와 `semaphore.withPermit {}`을 사용합니다. 모든 퍼밋이 사용 중일 때 들어오는 이벤트는 슬롯이 생길 때까지 대기합니다(드롭/거부 없음). 각 핸들러는 `SupervisorJob` 범위의 코루틴에서 실행되므로 하나의 실패가 다른 핸들러를 취소하지 않습니다.

**시작 순서:**

Auto-configuration이 `DiscordMessageListener`를 빈으로 등록한 뒤, `@EventListener(ApplicationReadyEvent::class)`가 Spring 컨텍스트가 완전히 초기화된 후 `listener.startListening()`을 호출합니다. 이를 통해 다른 빈이 준비되기 전에 리스너가 이벤트를 받는 경쟁 조건을 방지합니다.

## 코드 예시

**최소 설정:**

```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
```

**채널의 모든 메시지에 응답 (멘션 불필요):**

```yaml
arc:
  reactor:
    discord:
      enabled: true
      token: "${DISCORD_BOT_TOKEN}"
      respond-to-mentions-only: false
```

**커스텀 이벤트 핸들러:**

```kotlin
@Component
class MyDiscordEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: DiscordMessagingService
) : DiscordEventHandler {

    override suspend fun handleMessage(command: DiscordEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(
                userPrompt = command.content,
                userId = command.userId,
                metadata = mapOf("guildId" to (command.guildId ?: "dm"))
            )
        )
        messagingService.sendMessage(command.channelId, result.content ?: "No response")
    }
}
```

## 주의사항

**Discord 로그인은 시작 시 블로킹입니다.** `DiscordClientBuilder.create(token).build().login().block()`은 동기 방식입니다. Discord 게이트웨이에 접근할 수 없으면 연결 타임아웃까지 애플리케이션 시작이 중단됩니다.

**세션은 채널 범위입니다 (사용자 범위가 아님).** 같은 채널의 모든 사용자가 하나의 `sessionId`를 공유합니다. 즉, 여러 사용자의 대화 이력이 같은 세션에서 혼합됩니다. 격리가 필요하다면 사용자 범위 세션 키로 `DefaultDiscordEventHandler`를 오버라이드하세요.

**봇 메시지는 항상 무시됩니다.** `DiscordMessageListener`는 `author.isBot`을 확인하고 true이면 즉시 반환합니다. 봇이 자기 자신이나 다른 봇에게 응답하는 것을 방지합니다.

**2000자 제한은 분할된 각 메시지에 적용됩니다.** 응답이 매우 길면 `DiscordMessagingService`가 여러 연속 메시지로 전송합니다. Discord에는 네이티브 멀티파트 메시지 개념이 없어 빠른 연속 메시지는 rate limit에 걸릴 수 있습니다.

**`respond-to-mentions-only=false`는 바쁜 서버에서 주의가 필요합니다.** 멘션 전용 모드를 비활성화하면 봇이 읽을 수 있는 모든 채널의 모든 메시지에 응답하여 `maxConcurrentRequests`를 빠르게 소진할 수 있습니다.

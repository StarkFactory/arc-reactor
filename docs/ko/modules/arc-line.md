# arc-line

## 개요

arc-line은 Arc Reactor를 LINE Messaging API에 연결합니다. LINE으로부터 webhook 이벤트를 수신하고, HMAC-SHA256 서명을 검증하며, `AgentExecutor`에 메시지를 전달합니다. 응답은 LINE의 reply API를 먼저 시도하고, reply 토큰이 만료된 경우 push API로 폴백합니다.

이 모듈은 1:1 채팅, 그룹 채팅, 룸 채팅을 처리하며, 각 소스 타입에 따라 적절한 세션 ID와 응답 대상을 매핑합니다.

## 활성화

**프로퍼티:**
```yaml
arc:
  reactor:
    line:
      enabled: true
      channel-token: "${LINE_CHANNEL_TOKEN}"
      channel-secret: "${LINE_CHANNEL_SECRET}"
```

**Gradle 의존성:**
```kotlin
implementation("com.arc.reactor:arc-line")
```

이벤트 핸들러를 활성화하려면 `AgentExecutor` 빈이 있어야 합니다.

## 주요 컴포넌트

| 클래스 | 역할 |
|---|---|
| `LineAutoConfiguration` | 모든 빈 연결 |
| `LineWebhookController` | `POST /line/webhook`에서 webhook 페이로드 수신 |
| `LineSignatureVerifier` | `Base64(HMAC-SHA256(channelSecret, body))` 방식으로 `X-Line-Signature` 검증 |
| `LineSignatureWebFilter` | 잘못된 서명의 요청을 거부하는 WebFlux 필터 |
| `DefaultLineEventHandler` | 소스 타입을 세션/대상으로 매핑, `AgentExecutor` 호출, 응답 전송 |
| `LineMessagingService` | LINE Messaging API 래퍼: `replyMessage`와 `pushMessage` |
| `LineEventCommand` | 사용자 ID, 텍스트, reply 토큰, 소스 컨텍스트를 담는 내부 데이터 클래스 |

## 설정

모든 프로퍼티는 `arc.reactor.line` 접두사를 사용합니다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모듈 활성화 |
| `channel-token` | `""` | LINE Messaging API용 Channel Access Token |
| `channel-secret` | `""` | 서명 검증을 위한 LINE Channel Secret |
| `signature-verification-enabled` | `true` | HMAC-SHA256 webhook 서명 검증 활성화 |
| `max-concurrent-requests` | `5` | 동시 에이전트 실행 최대 수 |
| `request-timeout-ms` | `30000` | 에이전트 실행 타임아웃 (ms) |

## 연동

**에이전트 실행:**

`DefaultLineEventHandler`는 다음 정보와 함께 `AgentExecutor.execute()`를 호출합니다:
- `systemPrompt` — "You are a helpful AI assistant responding on LINE. Keep responses concise."
- `userId` — LINE 사용자 ID
- `sessionId` — 소스 타입에서 파생: 그룹은 `"line-{groupId}"`, 룸은 `"line-{roomId}"`, 1:1은 `"line-{userId}"`
- `metadata` — `source=line`

**Reply vs. push 폴백:**

LINE reply 토큰은 webhook 전달 후 짧은 시간 내에 만료됩니다. `DefaultLineEventHandler`는 먼저 `LineMessagingService.replyMessage(replyToken, text)`를 시도합니다. `false`를 반환하면(만료 또는 실패) 소스 타입에 따른 그룹 ID, 룸 ID, 또는 사용자 ID를 대상으로 `LineMessagingService.pushMessage(to, text)`로 폴백합니다.

**서명 검증:**

LINE은 `Base64(HMAC-SHA256(channelSecret, body))`를 사용합니다. Slack과 달리 서명에 타임스탬프가 포함되지 않습니다. `LineSignatureVerifier.verify()`는 예상 서명을 계산하고 `MessageDigest.isEqual`(timing-safe)로 비교합니다. `LineSignatureWebFilter`는 컨트롤러에 도달하기 전 모든 webhook 요청에 이 검사를 적용합니다.

**세션 범위:**

| 소스 타입 | 세션 키 | 응답 대상 |
|---|---|---|
| `user` (1:1) | `line-{userId}` | `userId` |
| `group` | `line-{groupId}` | `groupId` |
| `room` | `line-{roomId}` | `roomId` |

## 코드 예시

**최소 설정:**

```yaml
arc:
  reactor:
    line:
      enabled: true
      channel-token: "${LINE_CHANNEL_TOKEN}"
      channel-secret: "${LINE_CHANNEL_SECRET}"
```

**커스텀 이벤트 핸들러:**

```kotlin
@Component
class MyLineEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: LineMessagingService
) : LineEventHandler {

    override suspend fun handleMessage(command: LineEventCommand) {
        val result = agentExecutor.execute(
            AgentCommand(userPrompt = command.text, userId = command.userId)
        )
        val text = result.content ?: "No response"
        val replied = messagingService.replyMessage(command.replyToken, text)
        if (!replied) {
            messagingService.pushMessage(command.userId, text)
        }
    }
}
```

## 주의사항

**Reply 토큰은 빠르게 만료됩니다.** LINE reply 토큰은 webhook 전달 후 짧은 시간 동안만 유효합니다(일반적으로 60초 미만). 에이전트 실행이 이 시간보다 오래 걸리면 `replyMessage`가 실패하고 push 폴백이 사용됩니다. Push는 충분한 권한을 가진 채널 토큰이 필요하므로 올바르게 설정되어 있는지 확인하세요.

**서명 검증에는 타임스탬프가 없습니다.** Slack과 달리 LINE은 서명에 타임스탬프를 포함하지 않습니다. HTTPS 외에는 재생 공격 방지가 없습니다. LINE의 인프라가 이벤트를 한 번만 전송한다고 가정합니다.

**Push API는 할당량이 제한됩니다.** LINE Messaging API의 push 엔드포인트는 무료 플랜에서 월별 메시지 할당량이 있습니다. Push 폴백을 많이 사용하면 할당량이 소진될 수 있습니다. 처리량이 높은 배포에서는 에이전트 응답 시간이 reply 토큰 유효 기간 내에 유지되도록 해야 합니다.

**Webhook URL은 LINE Developer Console에 등록해야 합니다.** Webhook URL을 `https://your-domain/line/webhook`으로 설정하고, 채널 설정에서 webhook 전달을 활성화하세요.

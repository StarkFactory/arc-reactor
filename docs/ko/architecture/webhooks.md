# 웹훅

## 개요

Arc Reactor는 에이전트 실행이 완료될 때마다 외부 URL로 HTTP POST 알림을 전송할 수 있습니다. 모니터링 시스템, Slack, 커스텀 대시보드 또는 수신 웹훅을 지원하는 모든 서비스와 통합하는 데 유용합니다.

웹훅은 `AfterAgentCompleteHook`으로 구현되며 **fail-open** 방식으로 동작합니다 (order 200). 웹훅 실패는 에이전트 응답을 차단하거나 영향을 주지 않습니다.

```
요청 → Guard → Hook(BeforeStart) → [ReAct 루프] → Hook(AfterComplete) → 응답
                                                          │
                                                 WebhookNotificationHook
                                                          │
                                                   POST → 외부 URL
```

**기본값은 비활성화** — 설정으로 활성화합니다.

---

## 설정

```yaml
arc:
  reactor:
    webhook:
      enabled: true                        # 웹훅 알림 활성화 (기본값: false)
      url: https://example.com/webhook     # POST 대상 URL
      timeout-ms: 5000                     # HTTP 타임아웃 (밀리초, 기본값: 5000)
      include-conversation: false          # 전체 프롬프트/응답을 페이로드에 포함 (기본값: false)
```

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `enabled` | `false` | 마스터 스위치. `true`일 때만 Hook Bean이 등록됩니다. |
| `url` | `""` | 페이로드를 POST할 HTTP 엔드포인트. 빈 URL은 no-op입니다. |
| `timeout-ms` | `5000` | 웹훅 엔드포인트의 응답을 기다리는 최대 시간. |
| `include-conversation` | `false` | `true`이면 전체 `userPrompt`와 `fullResponse`가 페이로드에 포함됩니다. PII 유출 방지를 위해 프로덕션에서는 비활성화하세요. |

---

## 페이로드 구조

모든 웹훅 POST는 다음 필드를 포함하는 JSON 본문을 전송합니다.

### 성공 페이로드

```json
{
  "event": "AGENT_COMPLETE",
  "timestamp": "2026-03-17T08:30:00.123Z",
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "user-42",
  "success": true,
  "toolsUsed": ["calculator", "web-search"],
  "durationMs": 2340,
  "contentPreview": "The answer to your question is 42. Here is the detailed explanation..."
}
```

### 실패 페이로드

```json
{
  "event": "AGENT_COMPLETE",
  "timestamp": "2026-03-17T08:30:00.123Z",
  "runId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "userId": "user-42",
  "success": false,
  "toolsUsed": [],
  "durationMs": 150,
  "errorMessage": "Rate limit exceeded"
}
```

### 필드 참조

| 필드 | 타입 | 항상 존재 | 설명 |
|------|------|-----------|------|
| `event` | `String` | 예 | 항상 `"AGENT_COMPLETE"` |
| `timestamp` | `String` | 예 | ISO-8601 인스턴트 (UTC) |
| `runId` | `String` | 예 | 고유 실행 ID (UUID) |
| `userId` | `String` | 예 | 요청 사용자의 ID |
| `success` | `Boolean` | 예 | 에이전트 실행 성공 여부 |
| `toolsUsed` | `List<String>` | 예 | 실행 중 호출된 도구 이름 |
| `durationMs` | `Long` | 예 | 총 실행 시간 (밀리초) |
| `contentPreview` | `String` | 성공 시에만 | 응답의 처음 200자 |
| `errorMessage` | `String` | 실패 시에만 | 에러 설명 |
| `userPrompt` | `String` | `include-conversation=true`일 때만 | 사용자의 원래 프롬프트 |
| `fullResponse` | `String` | `include-conversation=true`일 때만 | 에이전트의 전체 응답 |

---

## Fail-Open 설계

웹훅 Hook은 에이전트 실행에 절대 간섭하지 않도록 설계되었습니다:

1. **`failOnError = false`** — Hook의 예외는 로그에 기록되고 무시됩니다
2. **`order = 200`** — 모든 중요 Hook(인증, 과금, 감사) 이후 늦게 실행됩니다
3. **HTTP 타임아웃** — 느리거나 응답 없는 엔드포인트에서 멈추는 것을 방지합니다
4. **리액티브 에러 처리** — `onErrorResume`으로 WebClient 에러가 전파되기 전에 처리합니다
5. **CancellationException 안전** — 구조적 동시성을 보존하기 위해 일반 예외 처리 전에 항상 다시 던집니다

웹훅 엔드포인트가 다운되거나, 에러를 반환하거나, 타임아웃되더라도 에이전트 응답은 호출자에게 변경 없이 전달됩니다.

---

## 아키텍처

### 클래스 계층

```
AfterAgentCompleteHook (인터페이스)
  └── WebhookNotificationHook
        ├── order = 200
        ├── failOnError = false
        └── 논블로킹 HTTP POST를 위해 WebClient 사용
```

### 자동 설정

`WebhookNotificationHook` Bean은 `ArcReactorHookAndMcpConfiguration`에서 다음과 같이 등록됩니다:

- `@ConditionalOnMissingBean` — 사용자가 자체 구현으로 교체 가능
- `@ConditionalOnProperty(prefix = "arc.reactor.webhook", name = ["enabled"], havingValue = "true")` — 명시적으로 활성화했을 때만 생성

속성은 `AgentProperties.webhook` (`WebhookConfigProperties` 데이터 클래스)에서 읽어 Hook이 사용하는 내부 `WebhookProperties`로 매핑됩니다.

---

## 통합 예시

### Slack 수신 웹훅

웹훅 URL을 Slack 수신 웹훅 엔드포인트로 설정합니다:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://your-webhook-endpoint.example.com/arc-reactor
      timeout-ms: 3000
```

Slack은 특정 페이로드 형식을 요구하므로, 메시지 포맷을 위한 커스텀 Hook이 필요합니다. 아래 [커스텀 웹훅 핸들러](#커스텀-웹훅-핸들러)를 참조하세요.

### 모니터링 대시보드

완료 이벤트를 메트릭 수집기로 전송합니다:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://monitoring.internal.example.com/api/agent-events
      timeout-ms: 5000
      include-conversation: false    # 프롬프트/응답을 모니터링에 절대 전송하지 않음
```

### PagerDuty / 알림 통합

`success=false`를 확인하여 알림 시스템으로 라우팅하는 미들웨어 엔드포인트를 사용합니다:

```yaml
arc:
  reactor:
    webhook:
      enabled: true
      url: https://events.pagerduty.com/generic/2010-04-15/create_event.json
      timeout-ms: 5000
```

---

## 커스텀 웹훅 핸들러

페이로드 형식을 커스텀하려면 (예: Slack Block Kit, Microsoft Teams Adaptive Card) 기본 Bean을 교체합니다:

```kotlin
@Component
class SlackWebhookNotificationHook(
    private val webhookProperties: WebhookProperties,
    private val webClient: WebClient = WebClient.create()
) : AfterAgentCompleteHook {

    override val order: Int = 200
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        val slackPayload = mapOf(
            "text" to buildSlackMessage(context, response)
        )

        try {
            webClient.post()
                .uri(webhookProperties.url)
                .bodyValue(slackPayload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(webhookProperties.timeoutMs))
                .onErrorResume { e ->
                    e.throwIfCancellation()
                    logger.warn { "Slack webhook failed: ${e.message}" }
                    Mono.empty()
                }
                .awaitSingleOrNull()
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Slack webhook error: ${e.message}" }
        }
    }

    private fun buildSlackMessage(context: HookContext, response: AgentResponse): String {
        val status = if (response.success) "Success" else "Failed"
        val tools = response.toolsUsed.joinToString(", ").ifEmpty { "none" }
        return ":robot_face: Agent $status | runId=${context.runId} | " +
            "tools=[$tools] | ${response.totalDurationMs}ms"
    }
}
```

기본 Hook에 `@ConditionalOnMissingBean`이 사용되므로 커스텀 `@Component`가 자동으로 우선합니다.

---

## 주의 사항

| 주의 사항 | 설명 |
|-----------|------|
| **빈 URL은 무시됨** | `url`이 빈 문자열이면 Hook은 경고를 로그에 기록하고 즉시 반환합니다. 예외는 발생하지 않습니다. |
| **페이로드의 PII** | `include-conversation=true`이면 전체 프롬프트와 응답이 네트워크로 전송됩니다. HTTPS를 사용하고, 엔드포인트를 신뢰할 수 없으면 프로덕션에서 비활성화하세요. |
| **느린 엔드포인트** | 느린 웹훅이 에이전트 응답을 차단하지는 않지만, 타임아웃 만료까지 코루틴을 점유합니다. `timeout-ms`를 낮게 유지하세요 (3000-5000ms). |
| **CancellationException** | 커스텀 웹훅 Hook에서 일반 `Exception` 처리 전에 항상 `CancellationException`을 다시 던져야 합니다. Arc Reactor의 support 패키지의 `throwIfCancellation()`을 사용하세요. |
| **재시도 없음** | 기본 구현은 실패한 웹훅 전송을 재시도하지 않습니다. 최소 1회 전달이 필요하면 큐를 사용하는 커스텀 Hook을 구현하거나 신뢰할 수 있는 메시지 브로커를 사용하세요. |

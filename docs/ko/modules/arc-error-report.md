# arc-error-report

## 개요

arc-error-report는 프로덕션 에러 보고서를 수신하고 자율 AI 기반 근본 원인 분석을 트리거하는 HTTP 엔드포인트를 제공합니다. 분석 에이전트는 런타임에 등록된 도구(MCP 서버 도구 및/또는 로컬 도구)를 사용하여 리포지토리, 이슈 트래커, 문서 시스템, 메시징 시스템에 걸쳐 에러를 조사한 뒤 Slack으로 형식화된 인시던트 보고서를 전송합니다.

엔드포인트는 보고서를 접수하고 즉시 반환합니다(비동기 처리 방식). 에이전트는 백그라운드에서 세마포어로 동시성을 제한하며 실행됩니다.

## 활성화

**프로퍼티:**
```yaml
arc:
  reactor:
    error-report:
      enabled: true
```

**Gradle 의존성:**
```kotlin
implementation("com.arc.reactor:arc-error-report")
```

핸들러를 활성화하려면 `AgentExecutor` 빈이 있어야 합니다.

## 주요 컴포넌트

| 클래스 | 역할 |
|---|---|
| `ErrorReportAutoConfiguration` | `ErrorReportHandler` 빈 연결 |
| `ErrorReportController` | `POST /api/error-report` — API 키 검증, 스택 트레이스 자름, 비동기 디스패치 |
| `DefaultErrorReportHandler` | 프롬프트 구성, 에러 분석 system prompt로 `AgentExecutor` 호출 |
| `ErrorReportHandler` | 커스텀 핸들러 구현을 위한 인터페이스 |
| `ErrorReportRequest` | 수신 페이로드: `stackTrace`, `serviceName`, `repoSlug`, `slackChannel`, 그 외 선택 필드 |
| `ErrorReportResponse` | 즉시 응답: `{ "accepted": true, "requestId": "..." }` |

## 설정

모든 프로퍼티는 `arc.reactor.error-report` 접두사를 사용합니다.

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `enabled` | `false` | 모듈 활성화 |
| `api-key` | `""` | `X-API-Key` 헤더에 필요한 API 키. 비어 있으면 인증 없음 |
| `max-concurrent-requests` | `3` | 동시 실행 가능한 최대 에러 분석 수 |
| `request-timeout-ms` | `120000` | 에러 분석 에이전트 실행 타임아웃 (ms) |
| `max-tool-calls` | `25` | 에러 분석 에이전트의 최대 도구 호출 수 |
| `max-stack-trace-length` | `30000` | 스택 트레이스 자름 한도 (문자 수) |

## 연동

**에이전트 실행:**

`DefaultErrorReportHandler`는 에이전트에게 다음 단계를 지시하는 고정 system prompt와 함께 `AgentExecutor.execute()`를 호출합니다:

1. 리포지토리 도구(일반적으로 MCP 기반)로 리포지토리 클론 또는 위치 파악
2. 리포지토리 로드 및 인덱싱 후 에러 분석 도구로 스택 트레이스 분석
3. 특정 파일 검사 및 에러 패턴 검색
4. Jira에서 관련 이슈 검색 및 담당 개발자 파악
5. Confluence에서 관련 런북 검색
6. 지정된 채널에 형식화된 Slack 메시지 작성 및 전송 (가능하면 내장 Slack LocalTool 우선 사용)

User prompt는 `ErrorReportRequest`의 필드(서비스명, 리포지토리 슬러그, Slack 채널, 환경, 타임스탬프, 메타데이터, 전체 스택 트레이스)로 구성됩니다.

**도구 의존성:**

분석 품질은 런타임에 사용 가능한 도구에 좌우됩니다. 리포지토리/Jira/Confluence/에러 분석 도구는 보통 MCP 서버로 등록합니다. Slack 전송은 `arc-slack`의 내장 LocalTool(`send_message`, `reply_to_thread`)로 처리할 수 있으며, 별도 외부 Slack 어댑터가 필수는 아닙니다. 도구 카테고리를 사용할 수 없으면 에이전트는 건너뛰고 접근 가능한 도구로 계속 진행합니다.

**비동기 처리:**

`ErrorReportController`는 `SupervisorJob` 범위의 코루틴에서 분석을 실행하고 `{ "accepted": true, "requestId": "..." }`로 즉시 응답합니다. 호출자는 분석 완료를 기다리지 않습니다. 동시성은 `Semaphore(maxConcurrentRequests)`로 제한됩니다.

## 코드 예시

**최소 설정:**

```yaml
arc:
  reactor:
    error-report:
      enabled: true
      api-key: "${ERROR_REPORT_API_KEY}"
      max-tool-calls: 30
      request-timeout-ms: 180000
```

**에러 보고서 제출:**

```bash
curl -X POST http://localhost:8080/api/error-report \
  -H "Content-Type: application/json" \
  -H "X-API-Key: secret-key" \
  -d '{
    "stackTrace": "java.lang.NullPointerException: Cannot read field \"id\"...\n\tat com.example.Service.process(Service.java:42)",
    "serviceName": "payment-service",
    "repoSlug": "payment-service",
    "slackChannel": "#incidents",
    "environment": "production",
    "timestamp": "2026-02-28T10:00:00Z"
  }'
```

**응답:**
```json
{
  "accepted": true,
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**커스텀 핸들러:**

```kotlin
@Component
class MyErrorReportHandler(
    private val agentExecutor: AgentExecutor,
    private val properties: ErrorReportProperties
) : ErrorReportHandler {

    override suspend fun handle(requestId: String, request: ErrorReportRequest) {
        val result = agentExecutor.execute(
            AgentCommand(
                systemPrompt = "Custom error analysis prompt",
                userPrompt = "Service: ${request.serviceName}\n${request.stackTrace}",
                maxToolCalls = properties.maxToolCalls
            )
        )
        // 원하는 채널로 결과 전송
    }
}
```

## 주의사항

**필요 도구는 런타임에 준비되어야 합니다.** 리포지토리/Jira/Confluence/에러 분석 도구는 보통 `POST /api/mcp/servers`로 등록합니다. Slack 전송은 `arc-slack` LocalTool을 사용합니다. 필요한 도구가 부족하면 분석은 부분적으로만 수행되거나 Slack 전송이 생략될 수 있습니다.

**엔드포인트는 분석 완료가 아닌 즉시 200을 반환합니다.** 호출자는 결과를 폴링하지 않아야 합니다. 결과 엔드포인트가 없습니다. 요청에 지정된 Slack 채널에서 분석 출력을 확인하세요.

**스택 트레이스는 잘립니다.** 제출된 스택 트레이스가 `max-stack-trace-length` 문자를 초과하면 해당 지점에서 자르고 `\n... [truncated]`가 추가됩니다. 중요한 프레임이 잘리는 경우 제출 측에서 출력 상세도를 줄이세요.

**`max-tool-calls`는 분석 깊이에 영향을 줍니다.** 깊은 리포지토리 분석(클론 → 인덱싱 → 분석 → 검색 → 교차 참조)은 10~20번의 도구 호출을 소비할 수 있습니다. `max-tool-calls`를 너무 낮게 설정하면 에이전트가 Slack 보고서를 보내기 전에 멈춥니다. 기본값 25는 적절한 최솟값입니다.

**`request-timeout-ms`는 전체 에이전트 루프를 포함합니다.** 120초 기본값에는 모든 도구 호출이 포함됩니다. 대규모 리포지토리나 느린 Jira/Confluence 인스턴스의 경우 분석이 잘리지 않도록 이 값을 늘리세요.

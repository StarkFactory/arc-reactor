# Tool Policy Admin (동적 Write Tool 정책)

Arc Reactor는 "write tool" 정책을 DB 기반으로 동적으로 관리할 수 있습니다. (배포 없이 관리자 UI/API에서 즉시 변경)

## 제어 대상

- `writeToolNames`: 상태 변경(생성/수정/삭제/머지/배포 등) 성격의 tool name 목록
- `denyWriteChannels`: write tool을 **차단**(fail-closed)할 채널 목록. 기본값: `["slack"]`.
- `allowWriteToolNamesInDenyChannels`: 전역 예외 목록 -- 차단된 채널에서도 허용되는 write tool name.
- `allowWriteToolNamesByChannel`: 채널별 예외 맵 -- 특정 차단 채널에서 특정 write tool을 허용.
- `denyWriteMessage`: 차단 시 사용자에게 반환할 메시지

## HITL(승인)과의 관계

- Slack: `denyWriteChannels`에 포함된 채널에서는 `BeforeToolCallHook`가 write tool 호출을 차단합니다.
- Web: HITL이 활성화되어 있으면, write tool은 자동으로 "승인 필요" 대상이 됩니다.

## 활성화

```yaml
arc:
  reactor:
    tool-policy:
      enabled: true
      dynamic:
        enabled: true
        refresh-ms: 10000

    approval:
      enabled: true   # web에서 write tool을 승인 대상으로 만들려면 필요

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/arcreactor
    username: arc
    password: arc
  flyway:
    enabled: true
```

## API (ADMIN)

> **활성화 조건**: `ToolPolicyController`는
> `@ConditionalOnProperty(prefix = "arc.reactor.tool-policy.dynamic", name = ["enabled"], havingValue = "true")`로
> 선언되어 있습니다. 아래 REST 엔드포인트는 `arc.reactor.tool-policy.dynamic.enabled=true`일 때**만 사용 가능**합니다.
> 동적 정책이 활성화되지 않으면 이 엔드포인트들은 404를 반환합니다.

- `GET /api/tool-policy` : effective/stored 정책 조회
- `PUT /api/tool-policy` : stored 정책 업데이트
- `DELETE /api/tool-policy` : stored 정책 삭제(설정 기본값으로 리셋)

### GET 응답

GET 엔드포인트는 네 개의 필드를 가진 `ToolPolicyStateResponse`를 반환합니다:

| 필드 | 타입 | 설명 |
|------|------|------|
| `configEnabled` | boolean | application 설정에서 `arc.reactor.tool-policy.enabled`가 true인지 여부 |
| `dynamicEnabled` | boolean | `arc.reactor.tool-policy.dynamic.enabled`가 true인지 여부 |
| `effective` | object | 현재 활성 정책 (설정 + DB 병합) |
| `stored` | object 또는 null | DB에 저장된 정책, 또는 오버라이드가 저장되지 않은 경우 null |

### PUT 예시

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "allowWriteToolNamesInDenyChannels": ["jira_create_issue"],
    "allowWriteToolNamesByChannel": {
      "slack": ["confluence_update_page"]
    },
    "denyWriteMessage": "Slack에서는 write tool을 사용할 수 없습니다"
  }'
```

### 요청 검증 제한

| 필드 | 최대값 |
|------|-------|
| `writeToolNames` | 500개 |
| `denyWriteChannels` | 50개 |
| `allowWriteToolNamesInDenyChannels` | 500개 |
| `allowWriteToolNamesByChannel` | 200개 채널 |
| `denyWriteMessage` | 500자 |

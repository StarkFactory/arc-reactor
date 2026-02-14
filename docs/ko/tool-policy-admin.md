# Tool Policy Admin (동적 Write Tool 정책)

Arc Reactor는 "write tool" 정책을 DB 기반으로 동적으로 관리할 수 있습니다. (배포 없이 관리자 UI/API에서 즉시 변경)

## 제어 대상

- `writeToolNames`: 상태 변경(생성/수정/삭제/머지/배포 등) 성격의 tool name 목록
- `denyWriteChannels`: write tool을 **차단**(fail-closed)할 채널 목록. 예: `slack`
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

- `GET /api/tool-policy` : effective/stored 정책 조회
- `PUT /api/tool-policy` : stored 정책 업데이트
- `DELETE /api/tool-policy` : stored 정책 삭제(설정 기본값으로 리셋)

업데이트 예시:

```bash
curl -X PUT http://localhost:8080/api/tool-policy \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "writeToolNames": ["jira_create_issue", "bitbucket_merge_pr"],
    "denyWriteChannels": ["slack"],
    "denyWriteMessage": "Slack에서는 write tool을 사용할 수 없습니다"
  }'
```


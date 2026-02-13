# Write Tool Policy (Read-Only vs Write)

Arc Reactor는 부작용이 있는 "쓰기(write)" 도구에 대해 간단한 안전 정책을 제공합니다.

## 왜 필요한가

Jira/Confluence/Bitbucket 같은 연동은 상태를 변경하는 도구(생성/수정/삭제/머지 등)가 포함될 수 있습니다.
직원별 권한 차등이 없더라도, 조직 공통 정책으로 다음이 자주 필요합니다.

- Web: 승인(HITL) 기반으로만 write tool 허용
- Slack: write tool 차단 (채팅 UX 특성 + 사고 위험)

## 동작 방식

1. 요청에 `metadata.channel` 값이 포함됩니다(예: `web`, `slack`).
2. Agent는 이를 `HookContext.channel`로 전달합니다.
3. `BeforeToolCallHook`가 설정된 write tool을 특정 채널에서 차단합니다.
4. HITL이 활성화되어 있으면 write tool이 자동으로 승인 대상(tool list)에 포함됩니다.

## 설정

```yaml
arc:
  reactor:
    approval:
      enabled: true

    tool-policy:
      enabled: true
      write-tool-names:
        - jira_create_issue
        - confluence_update_page
      deny-write-channels:
        - slack
```


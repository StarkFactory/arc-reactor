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
3. `BeforeToolCallHook` (`WriteToolBlockHook`)가 설정된 write tool을 특정 채널에서 차단합니다.
4. HITL이 활성화되어 있으면 write tool이 자동으로 승인 대상(tool list)에 포함됩니다.

### 핵심 클래스

**`ToolExecutionPolicyEngine`**은 중앙 의사결정 엔진입니다. 두 가지 메서드를 노출합니다:

- `isWriteTool(toolName, arguments)` -- 현재 정책에서 해당 도구가 write tool로 간주되면 true를 반환합니다. 정책이 비활성화되었거나 도구 호출이 읽기 전용 미리보기인 경우(아래 참조) false를 반환합니다.
- `evaluate(channel, toolName, arguments)` -- `Allow` 또는 `Deny(reason)`를 반환합니다. 평가 순서:
  1. 정책이 비활성화되었거나 `writeToolNames`가 비어있으면 허용.
  2. 채널이 null/공백이거나 `denyWriteChannels`에 없으면 허용.
  3. 도구가 write tool이 아니면 허용.
  4. 도구가 `allowWriteToolNamesInDenyChannels` (전역 예외 목록)에 있으면 허용.
  5. 도구가 `allowWriteToolNamesByChannel[channel]` (채널별 예외)에 있으면 허용.
  6. 그 외에는 `denyWriteMessage`로 거부.

**읽기 전용 미리보기**: 특정 도구는 `dryRun` 또는 미리보기 모드를 지원합니다. 인수가 dry run을 나타내는 경우(예: `dryRun=true`), 도구 이름이 `writeToolNames`에 있어도 `isWriteTool()`는 false를 반환합니다. 이를 통해 write tool의 읽기 전용 미리보기가 차단되는 것을 방지합니다.

**`DynamicToolApprovalPolicy`**는 도구 정책과 HITL 승인을 연결합니다. `ToolApprovalPolicy`를 구현하며 다음을 결합합니다:

- `arc.reactor.approval.tool-names`의 정적 도구 이름 (항상 승인 필요)
- `ToolExecutionPolicyEngine.isWriteTool()`의 동적 write tool 이름 (도구 정책이 write tool로 판단할 때 승인 필요)

이를 통해 HITL 승인 목록은 설정 또는 admin API를 통한 write tool 정책 변경과 자동으로 동기화됩니다.

## 설정

```yaml
arc:
  reactor:
    approval:
      enabled: true

    tool-policy:
      enabled: true
      # 선택: DB 기반 동적 정책 (admin 관리)
      # dynamic:
      #   enabled: true
      #   refresh-ms: 10000
      write-tool-names:
        - jira_create_issue
        - confluence_update_page
        - bitbucket_merge_pr
      deny-write-channels:          # 기본값: ["slack"]
        - slack
      allow-write-tool-names-in-deny-channels:    # 전역 예외 목록
        - jira_create_issue
      allow-write-tool-names-by-channel:           # 채널별 예외 맵
        slack:
          - confluence_update_page
      deny-write-message: "Error: This tool is not allowed in this channel"
```

### 설정 필드 참조

| 필드 | 기본값 | 설명 |
|------|-------|------|
| `enabled` | `false` | 도구 정책 적용의 마스터 스위치 |
| `dynamic.enabled` | `false` | DB 기반 동적 정책 활성화 (admin API) |
| `dynamic.refresh-ms` | `10000` | 동적 정책의 캐시 새로고침 간격 (ms) |
| `write-tool-names` | `[]` | 부작용이 있는 것으로 간주되는 도구 이름 |
| `deny-write-channels` | `["slack"]` | write tool이 차단되는 채널 |
| `allow-write-tool-names-in-deny-channels` | `[]` | 차단된 채널에서도 허용되는 write tool (전역) |
| `allow-write-tool-names-by-channel` | `{}` | 차단된 채널에서 특정 write tool을 허용하는 채널별 허용 목록 |
| `deny-write-message` | `"Error: This tool is not allowed in this channel"` | 도구 호출이 거부될 때 반환되는 오류 메시지 |

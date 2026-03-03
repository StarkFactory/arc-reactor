# 챗봇 질문 시나리오 검증 보고서 (2026-03-03)

## 1) 요청 반영 사항 요약

- 검증 시나리오/질문 목록 정리
- 실제 검증 결과(리포트 파일) 첨부
- 백엔드 LLM 모델을 무료 tier 회피 목적의 유료 저비용 모델로 적용 및 재검증

## 2) 모델 설정 (유료 저비용)

### 현재 적용

- Provider: `gemini`
- Chat model env override: `SPRING_AI_GOOGLE_GENAI_CHAT_OPTIONS_MODEL=gemini-2.5-flash-lite`
- 런타임 확인:
  - 8080 프로세스 환경변수에 `SPRING_AI_GOOGLE_GENAI_CHAT_OPTIONS_MODEL=gemini-2.5-flash-lite` 존재
  - `/api/chat` 200/`success=true` 확인

### 왜 이 모델인가

- Google 공식 가격 페이지 기준으로 `Gemini 2.5 Flash-Lite`는 Flash 계열 중 가장 비용 최적화된 라인으로 안내됨.
- 참조: https://ai.google.dev/gemini-api/docs/pricing

> 주의: "무료/유료"는 모델 이름만으로 강제되지 않고, API 키가 연결된 결제/요금제 상태에 따라 결정됩니다.
> 즉, 코드에서는 저비용 모델을 선택했고, 실제 유료 호출 여부는 해당 키의 billing 상태가 결정합니다.

## 3) 검증 시나리오 구조

시나리오 파일:

- `scripts/dev/scenarios/user-activity-matrix.json`

그룹:

- `contract`: 인증/권한/입력검증
- `stable`: 안정적으로 도구 호출이 기대되는 케이스
- `known-limited`: 인자 부족/환경 제약을 질문으로 되묻는 케이스

전체 확장 규모:

- `contract` 6건
- `stable` 234건 (13 tools x 3 channels x 6 activities)
- `known-limited` 126건 (7 tools x 3 channels x 6 activities)
- 합계 366건

## 4) /챗봇 질문 목록

### A. Contract 질문

1. `auth-chat-unauth-blocked`
- 질문: `auth check {{run_id}}`
- 기대: 인증 없이 `/api/chat` 호출 시 401

2. `validation-chat-blank-message`
- 질문: 공백 문자열(`" "`)
- 기대: 400 + validation 메시지

3. `tool-unavailable-guidance`
- 질문: `You must call bitbucket_list_repositories exactly once and summarize.`
- 기대: 200 + 성공 응답이지만 해당 툴은 미노출이므로 `toolsUsed`에 없어야 함

### B. Stable 매트릭스 질문 템플릿

- 공통 질문 템플릿:
- `You must call the exact tool {{tool_name}} once. If required args are unknown, infer safe defaults and continue. Return one-sentence summary.`

실행 샘플(모델 변경 후 실제 실행된 10건):

1. `[slack/incident] bitbucket_review_sla_alerts`
2. `[web/handoff] jira_transition_issue`
3. `[web/daily_ops] bitbucket_add_pr_comment`
4. `[web/incident] jira_transition_issue`
5. `[slack/incident] confluence_search`
6. `[api/release] work_set_briefing_profile`
7. `[web/first_contact] work_set_briefing_profile`
8. `[api/first_contact] jira_link_issues`
9. `[slack/follow_up] work_delete_briefing_profile`
10. `[api/handoff] confluence_create_page`

### C. Known-limited 매트릭스 질문 템플릿

- 공통 질문 템플릿:
- `You must call the exact tool {{tool_name}} once. If required args are unknown, ask me for missing required inputs in one sentence.`

실행 샘플(모델 변경 후 실제 실행된 6건):

1. `[web/incident] confluence_create_weekly_auto_summary_page`
2. `[web/daily_ops] confluence_create_weekly_status_report`
3. `[web/daily_ops] confluence_create_runbook`
4. `[web/first_contact] confluence_get_page_content`
5. `[api/first_contact] confluence_create_runbook`
6. `[api/follow_up] confluence_get_page_content`

## 5) 검증 결과 (모델 변경 후)

1. Contract 검증
- Report: `/tmp/arc-scenario-contract-after-model.json`
- 결과: `total=6, passed=6, failed=0, skipped=0, rateLimited=0`

2. Stable 샘플 검증
- Report: `/tmp/arc-scenario-stable-after-model.json`
- 결과: `total=10, passed=10, failed=0, skipped=0, rateLimited=0`

3. Known-limited 샘플 검증
- Report: `/tmp/arc-scenario-known-limited-after-model.json`
- 결과: `total=6, passed=6, failed=0, skipped=0, rateLimited=0`

## 6) 실행 커맨드(재현용)

```bash
ADMIN_TOKEN="<admin-jwt>"

# contract
python3 scripts/dev/validate-scenario-matrix.py \
  --base-url http://localhost:8080 \
  --tenant-id default \
  --scenario-file scripts/dev/scenarios/user-activity-matrix.json \
  --admin-token "$ADMIN_TOKEN" \
  --include-tags contract \
  --report-file /tmp/arc-scenario-contract-after-model.json

# stable sample
python3 scripts/dev/validate-scenario-matrix.py \
  --base-url http://localhost:8080 \
  --tenant-id default \
  --scenario-file scripts/dev/scenarios/user-activity-matrix.json \
  --admin-token "$ADMIN_TOKEN" \
  --include-tags stable \
  --max-cases 10 \
  --seed 17 \
  --case-delay-ms 100 \
  --report-file /tmp/arc-scenario-stable-after-model.json

# known-limited sample
python3 scripts/dev/validate-scenario-matrix.py \
  --base-url http://localhost:8080 \
  --tenant-id default \
  --scenario-file scripts/dev/scenarios/user-activity-matrix.json \
  --admin-token "$ADMIN_TOKEN" \
  --include-tags known-limited \
  --max-cases 6 \
  --seed 23 \
  --report-file /tmp/arc-scenario-known-limited-after-model.json
```

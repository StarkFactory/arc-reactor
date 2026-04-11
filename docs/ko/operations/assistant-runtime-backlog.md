# Assistant Runtime Backlog

> 목적: 실사용 로그, QA 로그, 운영 관찰에서 나온 문제를 다음 Round 입력으로 정리하는 backlog
> 기본 범위: Atlassian assistant 중심

---

## 1. 이 문서의 역할

이 문서는 `qa-verification-loop.md`가 읽는 **실패/기회 backlog**다.

상용급 개선 루프에서 중요한 것은 "무엇을 고칠지"를 실제 사용자 실패 기준으로 정하는 것이다.
이 문서는 그 기준점을 제공한다.

여기에는 아래만 넣는다.

- top missing query
- blocked but should answer
- wrong tool family
- cross-source synthesis failure
- permission / ACL mismatch
- safe action workflow friction

환경 문제만 있는 항목은 분리해서 적고, 제품 문제와 섞지 않는다.
실제 Atlassian 데이터에서 관찰한 증상을 backlog에 올릴 수는 있지만, raw 제목/본문/URL/식별자는 적지 않는다.

---

## 2. 항목 스키마

각 항목은 아래 형식을 따른다.

```markdown
### ATL-BG-001
- lane: `top_missing_query | blocked_false_positive | wrong_tool_family | synthesis_gap | permission_gap | safe_action_gap | env_only`
- symptom: (사용자에게 보이는 문제 1문장)
- representative_query: `실제 또는 대표 질문`
- expected_behavior: (어떻게 동작해야 하는지)
- observed_behavior: (실제로 어떻게 실패하는지)
- source: `runtime_log | qa_round | human_feedback | support`
- impact: `high | medium | low`
- suspected_layer: `routing | prompt | mcp | policy | retrieval | synthesis | approval | unknown`
- status: `open | in_progress | mitigated | closed | env_only`
- evidence:
  - `path:line` 또는 익명화된 내부 근거
- next_action: (다음 Round에서 가장 작은 수정 1개)
```

---

## 3. 현재 우선 backlog

### ATL-BG-001

- lane: `top_missing_query`
- symptom: 개인화 Jira 질의가 requester identity에 따라 비결정적으로 빈 결과를 반환할 수 있다
- representative_query: `내 Jira 티켓 보여줘`
- expected_behavior: requester-aware Jira 결과 또는 명시적 빈 결과
- observed_behavior: accountId/currentUser fallback 차이로 빈 결과가 나올 수 있음
- source: `qa_round`
- impact: `high`
- suspected_layer: `mcp`
- status: `open`
- evidence:
  - `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md:4577`
- next_action: requester identity resolution과 tool param injection 경로를 고정 검증한다

### ATL-BG-002

- lane: `synthesis_gap`
- symptom: Jira와 Confluence를 함께 물어봐도 한 source만 답하거나 연결 설명이 약하다
- representative_query: `JAR-123 이슈와 관련 문서를 같이 찾아줘`
- expected_behavior: 이슈 + 문서 + 관계 설명
- observed_behavior: single-source 응답 또는 병렬 나열
- source: `qa_round`
- impact: `high`
- suspected_layer: `synthesis`
- status: `open`
- evidence:
  - `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md:945`
- next_action: cross-source synthesis 케이스를 고정 eval로 묶고 tool family correctness를 분리 측정한다

### ATL-BG-003

- lane: `wrong_tool_family`
- symptom: Bitbucket authored/review queue 의도가 혼동될 수 있다
- representative_query: `내가 작성한 PR 현황 알려줘`
- expected_behavior: authored PR
- observed_behavior: review queue 또는 다른 PR 도구로 흐를 수 있음
- source: `qa_round`
- impact: `medium`
- suspected_layer: `routing`
- status: `mitigated`
- evidence:
  - `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md:4189`
- next_action: regression만 유지하고, 새 표현 변형을 추가한다

### ATL-BG-004

- lane: `blocked_false_positive`
- symptom: 안전한 업무 질의가 Guard나 source policy 때문에 불필요하게 막힐 수 있다
- representative_query: `프롬프트 엔지니어링 관련 Jira 이슈를 찾아줘`
- expected_behavior: Jira 검색 허용
- observed_behavior: false positive 가능성 추적 필요
- source: `qa_round`
- impact: `medium`
- suspected_layer: `policy`
- status: `open`
- evidence:
  - `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md:3504`
- next_action: blocked false positive 전용 eval을 분리한다

### ATL-BG-005

- lane: `safe_action_gap`
- symptom: write action에서 preview, approval, execute 구분이 사용자에게 명확히 드러나야 한다
- representative_query: `이 PR 바로 머지해줘`
- expected_behavior: preview/approval 후 execute
- observed_behavior: 현재는 회귀 테스트 축적이 더 필요함
- source: `human_feedback`
- impact: `high`
- suspected_layer: `approval`
- status: `open`
- evidence:
  - `docs/ko/governance/write-tool-policy.md:25`
- next_action: safe action 케이스 4개를 고정 eval로 연결한다

---

## 4. 운영 규칙

1. 매 Round는 이 문서의 `open` 또는 `in_progress` 항목 중 하나와 연결돼야 한다.
2. backlog와 연결되지 않는 작업은 원칙적으로 하지 않는다.
3. `env_only`는 제품 품질 개선으로 계산하지 않는다.
4. 항목을 닫으려면 최소 1개 eval case PASS + evidence가 필요하다.
5. 닫힌 항목도 바로 삭제하지 말고 `closed`로 남긴다.
6. 실제 데이터에서 온 관찰이라도 tracked backlog에는 익명화된 증상과 메타데이터만 남긴다.

---

## 5. 향후 자동화

이 문서는 지금은 수동 업데이트 문서지만, 이후에는 아래에서 자동 입력을 받을 수 있다.

- admin eval dashboard
- blocked query 집계
- toolsUsed drift 분석
- approval timeout / rejection 로그
- top missing query frequency 집계

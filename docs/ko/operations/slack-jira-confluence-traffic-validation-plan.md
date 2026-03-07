# Slack / Jira / Confluence 실제 트래픽 검증 계획

작성일: 2026-03-07

## 1. 목적

이번 검증의 목적은 기능 수를 늘리는 것이 아니라, 실제 직원 질문과 예약 실행이 들어왔을 때 Arc Reactor가 아래 4가지를 동시에 만족하는지 확인하는 것이다.

1. 직원에게 실제 시간을 절약해 주는가
2. 출처가 없는 답변을 막는가
3. 운영자가 문제를 비식별 상태로 빠르게 진단할 수 있는가
4. Slack / Jira / Confluence 흐름이 반복 사용에 견디는가

## 2. 참여 역할

### PO 1. Enterprise Admin

- 운영자 관점에서 dashboard, readiness, scheduler, trust signal이 액션으로 이어지는지 본다.
- 판단 기준은 `지금 안전한가`, `어디가 막히는가`, `무엇을 먼저 고칠까`가 한 화면에서 읽히는지다.

### PO 2. Knowledge / Safety

- Confluence 질문이 grounded answer로 끝나는지 본다.
- `no source, no answer`가 실제로 지켜지는지 확인한다.
- 원문 질문이나 user identifier가 운영 화면에 새지 않는지도 같이 확인한다.

### PO 3. Workflow Automation

- 예약 템플릿이 실제로 반복 가치가 있는지 본다.
- 직원이 자주 묻는 질문이 interactive가 좋은지 scheduled가 좋은지 구분한다.

### PO 4. Reliability / Scale

- Slack 중심 사용량, scheduler burst, MCP readiness, 429, backlog를 본다.
- 기능 성공보다 운영 신호의 신뢰성을 먼저 본다.

### QA 1. Functional QA

- Slack, web/admin inspector, scheduler dry-run, Jira, Confluence 질의가 계약대로 동작하는지 검증한다.
- 주요 시나리오의 pass/fail과 회귀 여부를 기록한다.

### QA 2. Security / Regression QA

- 운영 화면과 API가 userId, runId, raw query를 노출하지 않는지 검증한다.
- 이미지/첨부/텍스트형 이미지 토큰이 읽기 경로에 남지 않는지 회귀 검증한다.

## 3. 검증 원칙

- 실제 질문을 사용하되, 운영 콘솔에는 원문 질문을 남기지 않는다.
- 운영 지표는 `개인 추적`이 아니라 `비식별 클러스터` 기준으로만 본다.
- 테스트 질문은 기능 확인용과 실제 업무형을 분리한다.
- 실패 질문은 단순 카운트로 끝내지 않고 `문서 문제`, `라우팅 문제`, `allowlist 문제`, `source normalization 문제`로 분류한다.

## 4. 검증 대상 시나리오

### A. Slack knowledge lane

- 회사 정책 질문
- 팀 위키 / 개발팀 Home / 운영 가이드 질문
- 서비스 설명 질문
- 온콜 / 배포 / 보안 규정 질문

성공 기준:

- grounded answer
- source link 존재
- Confluence page freshness 존재
- dashboard에는 비식별 trust signal만 남음

### B. Slack operational lane

- Jira 프로젝트 목록
- 특정 이슈 상태 / 담당자 / due date
- Bitbucket 저장소 목록 / PR queue
- release risk / morning briefing

성공 기준:

- Jira / Bitbucket / work tool 결과에 source link 존재
- allowlist 밖 질문은 fail-close
- blocked response가 dashboard trust signal에 잡힘

### C. Hybrid lane

- 정책 + 현재 상태 결합 질문
- 예: “온콜 정책상 이번 릴리즈 blocker 대응 기준이 뭐야?”

성공 기준:

- policy source와 operational source가 분리되어 보임
- 원문 없이도 blocked cluster를 기준으로 재현 가능

### D. Scheduler lane

- morning briefing
- standup prep
- weekly policy digest
- release readiness dry-run

성공 기준:

- dry-run 성공
- 실패 시 dashboard에서 원인 파악 가능
- scheduled traffic이 interactive보다 가치가 있는지 판단 가능

## 5. 실제 운영 샘플 목표

### 최소 1주 파일럿

- 참여자: 5~10명
- Slack 질문: 하루 30건 이상
- Confluence knowledge 질문: 최소 20건
- Jira/Bitbucket operational 질문: 최소 20건
- hybrid 질문: 최소 10건
- scheduler dry-run + 실제 예약 실행: 최소 10건

### 채널 비율 목표

- Slack: 주 채널
- Admin inspector: 운영자 재현 채널
- Web chat: 보조 채널

판단 포인트:

- Slack 비율이 충분히 높지 않으면 제품 핵심 채널이 아직 검증되지 않은 것이다.
- Admin 트래픽이 너무 높으면 아직 엔지니어 테스트 도구에 머물러 있다는 신호다.

## 6. 일일 운영 루프

### 오전

1. QA 1이 전날 scheduler 실행과 Slack 주요 질문을 점검한다.
2. PO 2가 blocked cluster 상위 3개를 본다.
3. PO 1이 readiness / backlog / trust signal을 본다.

### 오후

1. blocked cluster를 원인별로 분류한다.
2. 문서 문제면 Confluence 운영 개선 항목으로 넣는다.
3. source normalization 문제면 tool backlog로 넣는다.
4. 라우팅 문제면 prompt / selector backlog로 넣는다.

### 하루 마감

아래 6개를 기록한다.

- grounded coverage
- blocked responses
- top missing clusters
- top channels
- top answer modes
- scheduler success / fail / backlog

## 7. 보고 지표

### 제품 가치 지표

- observedResponses
- groundedResponses
- groundedRatePercent
- blockedResponses
- interactiveResponses
- scheduledResponses
- lane health
- top channels
- top tool families

### 운영 안정성 지표

- MCP readiness
- preflight warnings / fails
- scheduler attention backlog
- recent scheduler failures
- unverified response count
- output guard rejected / modified
- boundary failures

### 보안 / 프라이버시 지표

- dashboard에 raw userId 노출 0건
- dashboard에 raw runId 노출 0건
- dashboard에 raw query 노출 0건
- inspector deep link에 raw message 포함 0건

## 8. 합격 기준

### 제품 기준

- knowledge lane grounded coverage 70% 이상
- operational lane grounded coverage 70% 이상
- hybrid lane에서 source 구분 실패 0건
- scheduler dry-run 성공률 80% 이상

### 운영 기준

- blocked cluster 상위 3개에 대해 하루 안에 원인 분류 가능
- dashboard만 보고도 backlog / readiness / trust 상태 판단 가능
- chat inspector는 raw 질문 없이도 진단 metadata로 재현 시작 가능

### 보안 기준

- 운영 화면에서 특정 사용자 재식별 가능성 없음
- raw 식별자 노출 회귀 0건
- source 없는 답변 허용 0건

## 9. 실패 시 바로 볼 체크리스트

### Confluence 질문이 막히면

- authoritative space가 맞는가
- title / label / page structure가 맞는가
- source link가 붙는가
- query cluster가 특정 하나로 몰리는가

### Jira / Bitbucket 질문이 막히면

- allowlist가 맞는가
- source link normalization이 붙는가
- MCP readiness와 rate budget이 정상인가

### Scheduler가 막히면

- 템플릿 자체가 과한가
- MCP 연결 실패인가
- source 없는 결과라 fail-close 된 것인가

## 10. 이번 라운드에서 만들지 말아야 할 것

- 자유형 write automation 확대
- manager용 제어 기능 확대
- raw query replay 기능
- 사용자별 heatmap / trace dashboard
- 원문 질문 보관형 diagnostics

이번 단계는 `누가 무엇을 물었는지 추적`하는 것이 아니라, `어떤 종류의 가치와 실패가 반복되는지`를 읽는 단계다.

# Arc Reactor 300명 직원 롤아웃 가치 검토

작성일: 2026-03-07

## 1. 제품을 무엇으로 볼 것인가

Arc Reactor는 `무엇이든 대신 실행하는 범용 에이전트`보다 `사내 지식과 업무 상태를 안전하게 읽어 주는 read-first 업무 레이어`로 보는 편이 맞다.

현재 코드와 실제 검증 기준으로 강한 축은 다음 4가지다.

1. `Confluence-native knowledge Q&A`
2. `Jira / Bitbucket operational read`
3. `Scheduler-based recurring briefings`
4. `Admin-controlled trust and integration governance`

이 4가지가 300명 직원에게 직접 주는 가치는 다음과 같다.

- 직원은 Slack/Web에서 정책, 위키, 서비스 설명, Jira 상태, PR 상태를 한 곳에서 물어볼 수 있다.
- 팀 리더와 IC는 morning briefing, focus plan 같은 반복 조회를 예약으로 자동화할 수 있다.
- 운영자는 출처 없는 답변, 연결 불량, rate budget, 스케줄 실패를 빠르게 볼 수 있다.
- 회사는 OpenClaw류 개인형 도구 대신 중앙 통제와 감사가 가능한 read-safe assistant를 가질 수 있다.

## 2. PO 4인 관점 평가

### PO 1. Enterprise Admin

강점:

- MCP 중앙 등록, allowlist, readonly, preflight, readiness, trust dashboard가 이미 있다.
- 운영자가 `왜 막혔는지`를 보기 시작했다.

부족:

- 아직도 admin은 설정 CRUD와 운영 관찰이 섞여 있다.
- 대규모 롤아웃에서는 `가치가 어디서 나오고 있는지`와 `무엇이 반복적으로 실패하는지`가 더 중요하다.

이번 개선 판단:

- `직원 가치 신호(Employee Value Signals)`를 대시보드에 올린 것은 맞는 방향이다.
- 이제 운영자는 단순 장애가 아니라 `채택/신뢰/실패 질문`을 같이 본다.

### PO 2. Knowledge / Safety

강점:

- `no source, no answer`가 실제 동작한다.
- Confluence 정책/위키 질문은 grounded answer와 source link가 붙는다.

부족:

- 정책성 답변 품질을 올리는 것보다 더 중요한 것은 `반복 실패 질문`을 문서 운영과 연결하는 루프다.
- “문서가 없어서 막혔는지”, “검색어가 안 맞아서 막혔는지”, “툴 source 표현이 약해서 막혔는지”를 빨리 구분해야 한다.

이번 개선 판단:

- top missing queries는 300명 롤아웃에서 매우 중요한 기능이다.
- 이 데이터가 있어야 Confluence 구조 개선이나 tool source 개선 우선순위를 정할 수 있다.

### PO 3. Workflow Automation

강점:

- scheduler는 이미 차별점이다.
- 질문을 반복하는 조직일수록 예약과 dry-run은 실제 비용 절감 효과가 크다.

부족:

- 자유형 예약을 더 넓히는 것은 지금 단계에서 가치보다 리스크가 크다.
- 템플릿 기반 사용 패턴과 실제 채택량을 먼저 봐야 한다.

이번 개선 판단:

- scheduled vs interactive 응답 비율을 분리해 본 것은 맞다.
- 이 비율을 보면 예약 기능이 진짜 가치인지, 과설계인지 판단할 수 있다.

### PO 4. Reliability / Scale

강점:

- MCP readiness, trust metrics, scheduler failure, recent events까지 운영 관측 기초가 갖춰졌다.

부족:

- 아직 300명 기준 성능 한계보다 중요한 것은 `관측 신호의 제품적 의미`다.
- 단순 요청 수보다 grounded coverage, blocked response, top missing query가 더 중요하다.

이번 개선 판단:

- `observed / grounded / blocked / interactive / scheduled` 분리는 롤아웃 판단에 바로 쓰인다.
- 성능 최적화는 이 데이터를 본 뒤에 집중해도 늦지 않다.

## 3. 지금 더 붙이면 안 되는 것

다음은 복잡도는 늘지만 현재 단계에서 가치가 약한 영역이다.

1. 자유형 write automation 확대
2. 별도 문서 업로드/RAG surface 확대
3. manager 전용 복잡한 제어 권한
4. 과도한 개인화 memory 기능 확대
5. 요란한 추천/에이전트 orchestration 기능 추가

이 기능들은 “있어 보이는” 제품에는 도움이 될 수 있지만, 300명 직원이 매일 쓰는 read assistant의 핵심 가치를 높이지는 않는다.

## 4. 지금 더 해야 하는 것

다음은 실제 가치 개선에 직접 연결된다.

### 우선순위 A. 반복 실패 질문 운영 루프

- top missing queries를 운영자가 보고
- 문서 문제인지
- allowlist 문제인지
- source normalization 문제인지
- prompt routing 문제인지

를 바로 분류할 수 있어야 한다.

### 우선순위 B. 직원 가치 계층 추적

- knowledge 답변이 얼마나 쓰이는지
- operational 답변이 얼마나 쓰이는지
- work lane이 얼마나 가치 있는지
- scheduler가 실제로 쓰이는지

를 봐야 한다.

### 우선순위 C. high-value template usage

- morning briefing
- personal focus plan
- release risk digest
- weekly policy digest

처럼 반복 가치가 높은 템플릿만 더 강화해야 한다.

### 우선순위 D. Confluence 운영 위생

- authoritative space
- label discipline
- 오래된 문서 정리
- title / page structure 통일

이 없으면 knowledge lane 품질은 계속 흔들린다.

## 5. 현재 단계 판정

현재 Arc Reactor는 다음 단계에 있다.

- 제품 정체성: `명확해짐`
- 운영 관측: `실사용 가능한 수준으로 상승 중`
- 직원 가치 증명: `초기 신호 수집 단계`
- 추가 기능 확장: `지금은 자제`

즉 지금은 “무엇을 더 만들까”보다 “현재 가치가 어디서 나오고 어디서 실패하는가”를 더 정확히 읽는 단계다.

## 6. 이번 브랜치에서 한 일

이번 브랜치 `feat/employee-value-insights`는 다음 목표를 가진다.

1. 운영 대시보드에 직원 가치 신호를 추가
2. grounded coverage / answer mode / tool family / scheduled usage를 분리
3. 반복 차단 질문을 집계해서 제품 개선 루프를 만들기

이 변화는 기능 수를 늘리기 위한 것이 아니라, 300명 롤아웃 전에 제품 방향을 잃지 않기 위한 장치다.

## 7. 운영자가 바로 취해야 할 액션

직원 가치 신호는 숫자만 있으면 반쪽짜리다. 운영자가 바로 액션으로 이어져야 한다.

그래서 이번 작업은 다음 흐름을 기준으로 한다.

1. dashboard에서 반복 차단 질문을 본다.
2. 해당 질문을 chat inspector로 바로 연다.
3. source 부족, allowlist 문제, prompt routing 문제를 재현한다.
4. 문서 정리나 tool source 개선 우선순위를 정한다.

즉 이번 브랜치의 목표는 “더 많은 기능”이 아니라 “반복 실패를 더 빨리 고치는 운영 루프”다.

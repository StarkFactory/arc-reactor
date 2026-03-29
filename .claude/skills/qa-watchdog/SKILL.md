---
name: qa-watchdog
description: >
  Arc Reactor 프로덕션 준비도를 검증하는 3-에이전트 병렬 QA 루프.
  사용자가 "QA 돌려줘", "검증 루프", "라운드 시작", "코드 개선 + 테스트 + 성능 검증",
  "cron QA", "qa-watchdog", "프로덕션 준비도 체크" 등을 언급하면 이 스킬을 사용한다.
  단일 라운드도, 반복 실행도 모두 이 스킬로 처리한다.
argument-hint: "[round count: e.g. '3' for 3 rounds, or blank for 1 round]"
---

# QA Watchdog: 3-Agent Parallel Verification Loop

당신은 Arc Reactor(Spring AI 기반 AI Agent 프레임워크)의 **시니어 QA 개발팀**이다.
매 라운드마다 **3개 병렬 에이전트**를 동시에 디스패치하여 코드 개선, 테스트 보강, 성능 검증을 수행한다.

**핵심 원칙: 매 Round 반드시 코드를 수정하거나 테스트를 추가한다. "이상 없음"으로 끝내지 않는다.**

---

## Phase 0: 라운드 번호 확인

`docs/production-readiness-report.md`에서 마지막 Round 번호를 확인하고 +1.
파일이 없으면 Round 1부터 시작한다.

---

## Phase 1: 3-Agent 동시 디스패치

**반드시 하나의 메시지에 3개 Agent 호출을 동시에 보낸다. 순차 실행은 시간 낭비다.**

### Agent 1: Code Improver (Opus)

코드베이스를 스캔하여 실제로 수정할 이슈를 찾고 고친다.

```
Agent(subagent_type: "general-purpose", model: "opus", prompt: "
  /Users/jinan/ai/arc-reactor 코드베이스를 스캔하여 개선할 코드를 찾고 수정하라.

  **우선순위 (높은 순):**
  1. 코틀린 안티패턴: 불필요한 nullable(?), 과도한 let/also 체인, 의미 없는 확장함수
  2. 메서드 추출 (≤20줄): 긴 메서드 → 단일 책임 메서드로
  3. 책임 분리: God class/method 해소
  4. 네이밍: data→parsedResponse, result→guardVerdict 등 구체적으로
  5. 중복 코드: 동일 로직 3회 반복 → 공통 유틸리티
  6. CLAUDE.md 규칙 위반: !! 사용, .forEach in suspend, catch without throwIfCancellation

  **AI 코딩 실수 Top 10 체크 (필수):**
  - catch(e: Exception)에서 CancellationException 삼킴 → throwIfCancellation() 첫 줄
  - !! 사용 → .orEmpty(), ?: default
  - .forEach {} in suspend → for (item in list)
  - Regex() 함수 내 생성 → companion object/top-level
  - @ConditionalOnMissingBean 누락
  - 선택 의존성 직접 주입 → ObjectProvider<T>
  - @RequestBody에 @Valid 누락
  - e.message HTTP 응답 노출 → 서버 로그에만
  - 존재하지 않는 API/패키지 호출 → import 경로 실제 확인
  - >= vs > off-by-one → 경계값 테스트

  **수정 규칙:**
  - 가장 영향력 있는 1개 이상을 실제 수정. Read로 먼저 읽을 것
  - ./gradlew compileKotlin compileTestKotlin으로 컴파일 확인
  - CLAUDE.md 규칙 준수

  보고: 발견 이슈 목록 + 수정 파일:라인 + 컴파일 결과
")
```

### Agent 2: Test Writer (Sonnet)

테스트 커버리지 gap을 찾고 새 테스트를 작성한다.

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  /Users/jinan/ai/arc-reactor에서 테스트 커버리지 gap을 찾고 새 테스트를 작성하라.

  **우선순위:**
  1. 통합 테스트 (@SpringBootTest): Guard 파이프라인, 캐시, MCP
  2. Hardening 시나리오: Guard 패턴 23개에 대한 hardening 케이스
  3. 엣지 케이스: null, empty, boundary, 동시성
  4. src/main에 있으나 src/test에 대응 없는 클래스
  5. 최근 수정 코드의 회귀 테스트

  **작성 규칙:**
  - runTest, coEvery/coVerify, 모든 assertion에 실패 메시지 필수
  - @Nested로 그룹화
  - 1개 테스트 파일 작성 또는 기존 파일에 추가
  - ./gradlew :모듈:test --tests '*.클래스명' 실행하여 통과 확인

  보고: gap 목록 + 작성 테스트 파일:라인 + 결과
")
```

### Agent 3: Verifier (Sonnet)

빌드, 테스트, 라이브 서버, MCP 연동을 검증한다.

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  Arc Reactor(http://localhost:18081) + MCP 서버를 검증하라. 파일 수정 금지.

  **필수 검증:**
  1. 빌드: ./gradlew compileKotlin compileTestKotlin
  2. 테스트: ./gradlew test
  3. Health: curl -s http://localhost:18081/actuator/health

  **MCP 연동 (라운드마다 다른 시나리오 1개 이상):**
  - Jira: 'JAR 프로젝트 이슈' → toolsUsed에 jira_*?
  - Confluence: 'MFS 스페이스 문서 검색' → confluence_*?
  - Bitbucket: 'jarvis 리포 PR 현황' → bitbucket_*?
  - Work briefing: '오늘 업무 브리핑' → work_*?
  - 멀티 도구: 'JAR 이슈와 Confluence 문서 같이 찾아줘' → 2개 이상 도구?
  - RAG grounding: 'Guard 파이프라인 설명' → grounded=true?
  - 캐시 히트: 동일 질문 2회 → 2번째 durationMs ≤ 10ms?

  각 검증: toolsUsed, grounded, blockReason, durationMs, 응답 품질

  **성능:** 채팅 3회 응답 시간 + Dashboard 총 응답 수/차단 수

  보고: BUILD, TEST, HEALTH, MCP 결과, 성능 수치
")
```

---

## Phase 2: 결과 종합 + 추가 수정

3개 에이전트 결과를 종합하여:
- Agent 1 코드 수정 확인 (컴파일 통과?)
- Agent 2 테스트 확인 (테스트 통과?)
- Agent 3 검증 결과 확인 (빌드, 서버, MCP)
- 추가 수정이 필요하면 즉시 수행

---

## Phase 3: 커밋 + 보고서 + Push

```bash
# 1. 변경 파일 확인
git status && git diff --stat

# 2. 소스/테스트 커밋 (접두사: feat:/fix:/refactor:/test:/sec:/perf:)
git add [수정된 파일]
git commit -m "{접두사}: {변경 요약}"

# 3. 보고서 업데이트
# docs/production-readiness-report.md에 Round 결과 추가 (핵심만)

git add docs/production-readiness-report.md
git commit -m "docs: Round N — {요약}"

# 4. Push
git push origin main
```

**추적 파일(tracking, progress 등)은 커밋하지 않는다.**

---

## Phase 4: 반복 (선택)

`$ARGUMENTS`에 숫자가 있으면 해당 횟수만큼 Phase 0-3을 반복한다.
없으면 1회 실행 후 종료.

---

## 검증 기준 레퍼런스

상세 기준은 `references/quality-criteria.md`를 참조한다. 핵심만 아래에:

### MCP 도구 호출 정확도

| 지표 | PASS | WARN | FAIL |
|------|------|------|------|
| 도구 선택 정확도 | ≥90% | 70-90% | <70% |
| grounding 비율 | ≥70% | 50-70% | <50% |
| 캐시 히트 (동일 쿼리) | ≤10ms | ≤100ms | >100ms |

### OWASP Agentic AI Top 10 (2026)

| ID | 위험 | 점검 |
|----|------|------|
| ASI01 | Excessive Agency | maxToolCalls + budgetTracker |
| ASI02 | Information Disclosure | e.message 0건, PII 마스킹 |
| ASI03 | Prompt Injection | Guard 7단계 + Hardening |
| ASI04 | Supply Chain (MCP) | 허용 서버 목록 + SSRF 차단 |
| ASI06 | Memory Poisoning | RAG 삽입 정책 + blockedPatterns |
| ASI08 | Cascading Failures | CircuitBreaker + failOnError |

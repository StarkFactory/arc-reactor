# Arc Reactor QA 검증 루프 (20분 주기)

당신은 AI Agent 제품의 **시니어 QA 개발팀**이다. 20분마다 **3개 병렬 에이전트**를 동시에 실행하여
코드 개선, 테스트 보강, 성능 검증을 수행한다.

**핵심: 매 Round 반드시 코드를 수정하거나 테스트를 추가한다. "이상 없음"으로 끝내지 않는다.**

---

## 실행 원칙

1. **3 에이전트 병렬 필수** — 매 Round 아래 3개 에이전트를 **하나의 메시지에 동시 디스패치**:
   - **Agent 1: 코드 개선** — codebase-scanner 또는 general-purpose로 코드 이슈 탐색 + 수정
   - **Agent 2: 테스트 보강** — 테스트 커버리지 gap 찾기 + 새 테스트 코드 작성
   - **Agent 3: 성능/기능 검증** — 빌드+테스트+라이브 서버 검증
2. **코드 수정 우선** — 보고서만 쓰지 말고 실제 코드를 고쳐라. 매 Round 최소 1개 코드 변경
3. **push = 완료** — 커밋 후 반드시 push
4. **추적 파일은 커밋하지 않는다**
5. **실측 근거 필수**
6. **보고서는 간결하게** — `docs/production-readiness-report.md`에 Round 결과 추가하되 핵심만

---

## Phase 0: Round 번호 확인

`docs/production-readiness-report.md`에서 마지막 Round 번호를 확인하고 +1.

---

## Phase 1: 3 에이전트 동시 디스패치

**반드시 하나의 메시지에 3개 Agent 호출을 동시에 보낸다. 순차 실행 절대 금지.**

### Agent 1: 코드 개선 에이전트

```
Agent(subagent_type: "codebase-scanner" 또는 "general-purpose", model: "opus", prompt: "
  /Users/jinan/ai/arc-reactor 코드베이스를 스캔하여 개선할 수 있는 코드를 찾고 수정하라.

  **찾아야 할 것 (우선순위순):**
  1. **코틀린 안티패턴 제거**: 불필요한 nullable(?), 과도한 let/also 체인, 의미 없는 확장함수, 불명확한 변수/메서드명
  2. **메서드 추출 리팩터링** (≤20줄): 긴 메서드에서 단일 책임 메서드로 추출. 메서드명은 '무엇을 하는지' 명확히
  3. **책임 분리**: 하나의 클래스가 여러 역할을 하면 분리. God class/method 해소
  4. **변수/메서드 네이밍**: 흐름에 맞는 이름. `data`→`parsedResponse`, `result`→`guardVerdict` 등 구체적으로
  5. **중복 코드 추출**: 동일 로직 3회 이상 반복 → 공통 유틸리티로
  6. CLAUDE.md 규칙 위반 (!! 사용, .forEach in suspend, catch without throwIfCancellation, 120자 초과)

  **수정 규칙:**
  - 발견한 이슈 중 가장 영향력 있는 1개를 실제로 수정
  - 수정 전 반드시 파일을 Read로 읽을 것
  - 수정 후 ./gradlew compileKotlin compileTestKotlin 실행하여 컴파일 확인
  - 테스트가 필요하면 테스트도 추가/수정
  - CLAUDE.md 규칙을 반드시 따를 것

  보고: 발견한 이슈 목록 + 수정한 파일:라인 + 컴파일 결과
")
```

### Agent 2: 테스트 보강 에이전트

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  /Users/jinan/ai/arc-reactor에서 테스트 커버리지 gap을 찾고 새 테스트를 작성하라.

  **찾아야 할 것 (우선순위순):**
  1. **통합 테스트** (@SpringBootTest): Guard 파이프라인, 캐시, MCP 연결 등 Spring 컨텍스트 필요한 테스트
  2. **Hardening 시나리오 추가**: 새로 추가된 Guard 패턴 23개에 대한 hardening 테스트 케이스
  3. **엣지 케이스**: null, empty, boundary, 동시성 시나리오
  4. src/main에 있으나 src/test에 대응 테스트가 없는 클래스
  5. 최근 수정된 코드(R77-114)의 회귀 테스트

  **작성 규칙:**
  - CLAUDE.md 테스트 규칙 준수: runTest, coEvery/coVerify, assertion 메시지 필수
  - @Nested로 그룹화
  - 1개 테스트 파일 작성 또는 기존 파일에 테스트 추가
  - 작성 후 ./gradlew :모듈:test --tests '*.클래스명' 실행하여 통과 확인

  보고: gap 목록 + 작성한 테스트 파일:라인 + 테스트 결과
")
```

### Agent 3: MCP 연동 + 성능 검증 에이전트

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  Arc Reactor 서버(http://localhost:18081) + MCP 서버를 검증하라. 파일 수정 금지.

  **필수 검증:**
  1. 빌드: ./gradlew compileKotlin compileTestKotlin
  2. 테스트: ./gradlew test
  3. Health: curl -s http://localhost:18081/actuator/health

  **MCP 연동 정확도 검증 (매 Round 1개 이상):**
  아래에서 라운드마다 다른 시나리오를 선택하여 실제 도구 호출 + 응답 품질을 검증:

  - Jira: 'JAR 프로젝트 이슈 현황 알려줘' → toolsUsed에 jira_* 포함? 응답에 이슈 정보?
  - Confluence: 'MFS 스페이스 문서 검색해줘' → toolsUsed에 confluence_* 포함?
  - Bitbucket: 'jarvis 리포 PR 현황' → toolsUsed에 bitbucket_* 포함?
  - Work briefing: '오늘 업무 브리핑' → toolsUsed에 work_* 포함?
  - Swagger: 'API 스펙 조회해줘' → swagger 도구 사용?
  - 멀티 도구: 'JAR 이슈와 관련 Confluence 문서 같이 찾아줘' → 2개 이상 도구 사용?
  - RAG grounding: 'Guard 파이프라인 설명해줘' → grounded=true?
  - 캐시 히트: 동일 질문 2회 → 2번째 durationMs=0?

  각 검증에서 기록할 것:
  - toolsUsed (정확한 도구명)
  - grounded (true/false)
  - blockReason (있으면)
  - durationMs
  - 응답 품질 (질문에 실제로 답했는지, 도구 결과를 반영했는지)

  **성능:**
  - 채팅 3회 응답 시간
  - Dashboard: 총 응답 수 + 차단 수

  보고: BUILD, TEST, HEALTH, MCP 도구 호출 결과, 성능 수치
")
```

---

## Phase 2: 결과 종합 + 추가 수정

3개 에이전트 결과를 종합하여:
- Agent 1이 수정한 코드 확인
- Agent 2가 작성한 테스트 확인
- Agent 3의 검증 결과 확인
- 추가 수정이 필요하면 즉시 수행

---

## Phase 3: 커밋 + 보고서 + Push

```bash
# 변경된 파일 확인
git status
git diff --stat

# 커밋 (코드 수정과 보고서를 별도 커밋)
git add [수정된 소스/테스트 파일]
git commit -m "{접두사}: {변경 요약}"

# 보고서 업데이트
docs/production-readiness-report.md에 간결한 Round 결과 추가

git add docs/production-readiness-report.md
git commit -m "docs: Round N — {요약}"

git push origin main
```

---

## 검증 기준 및 레퍼런스

### MCP 도구 호출 정확도 기준

| 지표 | PASS | WARN | FAIL |
|------|------|------|------|
| 도구 선택 정확도 | ≥90% | 70-90% | <70% |
| 응답에 도구 결과 반영 | 모든 호출 | 일부 누락 | 미반영 |
| grounding 비율 | ≥70% | 50-70% | <50% |
| 캐시 히트 (동일 쿼리) | durationMs ≤ 10ms | ≤ 100ms | > 100ms |

### 코틀린 코드 품질 기준 (Detekt 기반)

Agent 1이 따라야 할 리팩터링 체크리스트:

1. **메서드 길이**: ≤20줄 (CLAUDE.md). 초과 시 단일 책임 메서드로 추출
2. **순환 복잡도**: ≤10 (Detekt 기본값). when/if 중첩이 깊으면 early return 또는 전략 패턴
3. **네이밍**: 동사+목적어 (`processGuardResult` not `doStuff`). Boolean은 `is/has/should` 접두사
4. **코루틴 안전**: `suspend fun` 내 `catch(e: Exception)` → `throwIfCancellation()` 첫 줄
5. **Null 안전**: `!!` 금지 → `?.let`, `?: default`, `requireNotNull`
6. **컬렉션**: `.forEach` in suspend → `for` 루프. `count` when `any` suffices → `any`
7. **불변성**: `var` 최소화. `val` + `copy()` 우선. `MutableList` 반환 → `List` 반환
8. **확장함수 남용 금지**: 해당 타입의 모든 사용자에게 유의미한 경우만 확장함수 사용

### OWASP Agentic AI Top 10 (2026) 점검 항목

| OWASP ID | 위험 | 점검 방법 |
|----------|------|----------|
| ASI01 | Excessive Agency | maxToolCalls + budgetTracker 검증 |
| ASI02 | Information Disclosure | e.message 0건, PII 마스킹 |
| ASI03 | Prompt Injection | Guard 파이프라인 + Hardening 테스트 |
| ASI04 | Supply Chain (MCP) | 허용 서버 목록 + SSRF 차단 |
| ASI06 | Memory Poisoning | RAG 삽입 정책 + blockedPatterns |
| ASI08 | Cascading Failures | CircuitBreaker + failOnError 정책 |

### AI 코딩 실수 패턴 Top 10 (2026 최신)

**Agent 1이 코드 수정 시 반드시 확인할 것 — AI가 자주 만드는 실수:**

| # | 실수 | 확인 방법 |
|---|------|----------|
| 1 | `catch(e: Exception)`에서 CancellationException 삼킴 | `throwIfCancellation()` 첫 줄 |
| 2 | `!!` 사용 | `.orEmpty()`, `?: default` 대체 |
| 3 | `.forEach {}` in suspend fun | `for (item in list)` 사용 |
| 4 | `Regex()` 함수 내 생성 | companion object/top-level |
| 5 | `@ConditionalOnMissingBean` 누락 | auto-config 빈에 필수 |
| 6 | 선택 의존성 직접 주입 | `ObjectProvider<T>` 사용 |
| 7 | `@RequestBody`에 `@Valid` 누락 | 모든 @RequestBody에 필수 |
| 8 | `e.message` HTTP 응답 노출 | 서버 로그에만, 한글 메시지 반환 |
| 9 | 존재하지 않는 API/패키지 호출 | import 경로 실제 확인 |
| 10 | `>=` vs `>` off-by-one 오류 | 경계값 테스트 필수 |

> AI PR은 인간 대비 **75% 더 많은 로직 오류** 발생 (IEEE Spectrum 2026)
> 참조: [IEEE Spectrum](https://spectrum.ieee.org/ai-coding-degrades), [Sonar LLM Research](https://www.sonarsource.com/resources/library/llm-code-generation/)

**레퍼런스:**
- [OWASP Top 10 for Agentic AI 2026](https://www.aikido.dev/blog/owasp-top-10-agentic-applications)
- [Confident AI Agent Evaluation Guide](https://www.confident-ai.com/blog/definitive-ai-agent-evaluation-guide)
- [Detekt Kotlin Static Analysis](https://detekt.dev/)
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/)
- [MCP Best Practices](https://modelcontextprotocol.info/docs/best-practices/)
- [IEEE Spectrum — AI Coding Degrades](https://spectrum.ieee.org/ai-coding-degrades)
- [Sonar — LLM Code Quality](https://www.sonarsource.com/resources/library/llm-code-generation/)
- [Stack Overflow — Bugs with AI Agents](https://stackoverflow.blog/2026/01/28/are-bugs-and-incidents-inevitable-with-ai-coding-agents/)

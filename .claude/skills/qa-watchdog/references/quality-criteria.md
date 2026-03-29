# QA Watchdog Quality Criteria

## Table of Contents
1. [Kotlin Code Quality (Detekt 기반)](#kotlin-code-quality)
2. [AI Coding Mistakes Top 10](#ai-coding-mistakes-top-10)
3. [OWASP Agentic AI Top 10 (2026)](#owasp-agentic-ai-top-10)
4. [MCP 도구 호출 정확도](#mcp-tool-accuracy)
5. [References](#references)

---

## Kotlin Code Quality

Agent 1이 따라야 할 리팩터링 체크리스트:

1. **메서드 길이**: ≤20줄 (CLAUDE.md). 초과 시 단일 책임 메서드로 추출
2. **순환 복잡도**: ≤10 (Detekt 기본값). when/if 중첩이 깊으면 early return 또는 전략 패턴
3. **네이밍**: 동사+목적어 (`processGuardResult` not `doStuff`). Boolean은 `is/has/should` 접두사
4. **코루틴 안전**: `suspend fun` 내 `catch(e: Exception)` → `throwIfCancellation()` 첫 줄
5. **Null 안전**: `!!` 금지 → `?.let`, `?: default`, `requireNotNull`
6. **컬렉션**: `.forEach` in suspend → `for` 루프. `count` when `any` suffices → `any`
7. **불변성**: `var` 최소화. `val` + `copy()` 우선. `MutableList` 반환 → `List` 반환
8. **확장함수 남용 금지**: 해당 타입의 모든 사용자에게 유의미한 경우만 확장함수 사용

---

## AI Coding Mistakes Top 10

AI가 자주 만드는 실수 — Agent 1이 코드 수정 시 반드시 확인:

| # | 실수 | 확인 방법 |
|---|------|------------|
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

---

## OWASP Agentic AI Top 10

| OWASP ID | 위험 | 점검 방법 |
|----------|------|----------|
| ASI01 | Excessive Agency | maxToolCalls + budgetTracker 검증 |
| ASI02 | Information Disclosure | e.message 0건, PII 마스킹 |
| ASI03 | Prompt Injection | Guard 파이프라인 + Hardening 테스트 |
| ASI04 | Supply Chain (MCP) | 허용 서버 목록 + SSRF 차단 |
| ASI06 | Memory Poisoning | RAG 삽입 정책 + blockedPatterns |
| ASI08 | Cascading Failures | CircuitBreaker + failOnError 정책 |

---

## MCP Tool Accuracy

| 지표 | PASS | WARN | FAIL |
|------|------|------|------|
| 도구 선택 정확도 | ≥90% | 70-90% | <70% |
| 응답에 도구 결과 반영 | 모든 호출 | 일부 누락 | 미반영 |
| grounding 비율 | ≥70% | 50-70% | <50% |
| 캐시 히트 (동일 쿼리) | durationMs ≤ 10ms | ≤ 100ms | > 100ms |

### MCP 검증 시나리오 메뉴

라운드마다 다른 시나리오를 선택하여 커버리지를 넓힌다:

1. **Jira**: 'JAR 프로젝트 이슈 현황 알려줘' → toolsUsed에 jira_* 포함?
2. **Confluence**: 'MFS 스페이스 문서 검색해줘' → toolsUsed에 confluence_* 포함?
3. **Bitbucket**: 'jarvis 리포 PR 현황' → toolsUsed에 bitbucket_* 포함?
4. **Work briefing**: '오늘 업무 브리핑' → toolsUsed에 work_* 포함?
5. **Swagger**: 'API 스펙 조회해줘' → swagger 도구 사용?
6. **멀티 도구**: 'JAR 이슈와 관련 Confluence 문서 같이 찾아줘' → 2개 이상 도구?
7. **RAG grounding**: 'Guard 파이프라인 설명해줘' → grounded=true?
8. **캐시 히트**: 동일 질문 2회 → 2번째 durationMs=0?

---

## References

- [OWASP Top 10 for Agentic AI 2026](https://www.aikido.dev/blog/owasp-top-10-agentic-applications)
- [Confident AI Agent Evaluation Guide](https://www.confident-ai.com/blog/definitive-ai-agent-evaluation-guide)
- [Detekt Kotlin Static Analysis](https://detekt.dev/)
- [OpenTelemetry GenAI Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/)
- [MCP Best Practices](https://modelcontextprotocol.info/docs/best-practices/)
- [IEEE Spectrum — AI Coding Degrades](https://spectrum.ieee.org/ai-coding-degrades)
- [Sonar — LLM Code Quality](https://www.sonarsource.com/resources/library/llm-code-generation/)
- [Stack Overflow — Bugs with AI Agents](https://stackoverflow.blog/2026/01/28/are-bugs-and-incidents-inevitable-with-ai-coding-agents/)

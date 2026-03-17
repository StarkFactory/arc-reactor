# Arc Reactor — Agent Instructions

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot).
이 파일은 모든 AI 코딩 에이전트(Claude, Gemini, Cursor 등)를 위한 도메인 지식이다.
Claude Code 사용자는 동일 내용이 CLAUDE.md에 통합되어 있으므로 이 파일을 별도로 읽을 필요 없다.

> **CLAUDE.md와 이 파일의 관계**: 핵심 내용(가치, 아키텍처, Gotchas, 규칙)은 동일.
> CLAUDE.md에는 Claude Code 전용 워크플로우(명령어, 환경변수, PR 정책)가 추가로 포함.
> 이 파일은 Gemini, Cursor 등 CLAUDE.md를 자동 로드하지 않는 도구용.

---

## AI Agent 개발 핵심 가치

| 가치 | 정의 | Arc Reactor 적용 |
|------|------|-----------------|
| **안전 우선** | 에이전트 행동은 결정론적 코드로 제한. LLM 지시에 의존하지 않는다 | Guard = fail-close. `ToolApprovalPolicy`로 위험 도구 실행 전 인간 승인 |
| **단순성 지향** | 가장 단순한 해법 우선. 측정 가능한 개선 시에만 복잡도 증가 | `AgentMode.STANDARD` vs `REACT` 분리 |
| **투명한 추론** | 에이전트 판단 과정이 사후 추적 가능해야 한다 | `AgentMetrics` + `ArcReactorTracer`로 매 단계 span 기록 |
| **결정론적 제어** | 정책·제한·종료 조건은 프롬프트가 아닌 코드로 강제 | `maxToolCalls` → `activeTools = emptyList()`. Rate limit → Guard |
| **확장 가능한 기본값** | 합리적 기본 동작 + 모든 구성 요소 교체 가능 | 전체 빈 `@ConditionalOnMissingBean` |
| **비용 인식** | 모든 설계에서 정확도-비용 트레이드오프 명시 | `ResponseCache`, `CircuitBreaker`, `TokenEstimator` |
| **실패 격리** | 하나의 실패가 전체 파이프라인을 붕괴시키지 않는다 | Guard = fail-close, Hook = fail-open |

## Architecture

요청 흐름: `Guard → Hook(BeforeStart) → ReAct Loop(LLM ↔ Tool) → Hook(AfterComplete) → Response`

**Guard = fail-close** (차단). **Hook = fail-open** (계속). 보안 로직은 Guard에만.

| Module | Role |
|--------|------|
| `arc-core` | 에이전트 엔진, Guard/Hook, Tool, RAG, Memory, MCP, Scheduler |
| `arc-web` | REST/SSE 컨트롤러, 인증, 멀티모달, OpenAPI |
| `arc-slack` | Slack 연동 (Events API + Socket Mode + Slash Command) |
| `arc-admin` | 운영 제어: 메트릭, 테넌트, 알림, 비용/SLO |
| `arc-app` | 실행 조립 (bootRun/bootJar 진입점) |

## Critical Gotchas

1. **CancellationException**: `suspend fun`에서 generic `Exception` 전에 먼저 catch & rethrow
2. **ReAct 무한 루프**: `maxToolCalls` 도달 시 `activeTools = emptyList()` 필수
3. **코루틴 .forEach 금지**: `for (item in list)` 사용
4. **메시지 쌍 무결성**: AssistantMessage + ToolResponseMessage 항상 쌍
5. **Context trimming**: Phase 2 조건 `>` (not `>=`)
6. **AssistantMessage 생성자**: protected → builder 사용
7. **API key**: `application.yml` 빈 기본값 금지
8. **MCP**: REST API 등록만
9. **Guard null userId**: `"anonymous"` 폴백 필수
10. **Spring AI mock**: `.options(any<ChatOptions>())` 명시적 mock
11. **toolsUsed**: 어댑터 확인 후 추가

## 설계 원칙

1. **도구는 API** — LLM 호출 전제로 설명·형식·경계·차이 명시
2. **루프 탈출 조건** — `maxToolCalls` + `withTimeout`
3. **Guard/Hook 분리** — 보안 → Guard, 확장 → Hook
4. **메시지 쌍** — 항상 쌍 단위 처리
5. **환경 근거** — 도구 결과를 컨텍스트에 포함
6. **컨텍스트 관리** — `ConversationManager` + `TokenEstimator`
7. **도구 출력 불신** — `ToolOutputSanitizer` + Output Guard
8. **동시성 존중** — `CancellationException` 먼저 rethrow
9. **명시적 전파** — Tool → 에러 문자열, Guard → 예외
10. **관측 가능성** — `AgentMetrics` + `ArcReactorTracer`

## Anti-Patterns

| 패턴 | 대안 |
|------|------|
| 무한 루프 (종료 조건 없음) | `maxToolCalls` + `withTimeout` |
| 프롬프트 의존 보안 | Guard fail-close + `ToolApprovalPolicy` |
| 전지적 컨텍스트 | `TokenEstimator` + rerank + 트리밍 |
| 조용한 실패 | `"Error: {원인}"` + 메트릭 |
| 환각 도구 | `toolsUsed` 어댑터 확인 |
| 폴링 세금 | 이벤트 기반 + 지수 백오프 |

## 강화 테스트 (Hardening Tests)

일반 테스트는 "구현이 맞는지", 강화 테스트는 **"구현이 충분한지"** 확인한다. 새 기능 후 반드시 작성.

- `@Tag("hardening")` 태그. `hardening/` 디렉토리에 배치
- 적대적 입력 + 안전한 입력(false positive 방지)을 쌍으로 테스트
- 카테고리: 프롬프트 인젝션, 입력 경계값, 도구 출력 정제, 출력 가드(PII/카나리)

## 코드 규칙

- `suspend fun` 기반. 메서드 ≤20줄, 줄 ≤120자. 한글 KDoc
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단
- 컨트롤러: `@Tag` + `@Operation(summary = "...")`
- 403: `ErrorResponse` 본문 필수

## 확장 포인트

| Component | 규칙 | 실패 정책 |
|-----------|------|----------|
| ToolCallback | `"Error: ..."` 반환, throw 금지 | LLM 대안 탐색 |
| GuardStage | 내장 1–5, 커스텀 10+ | fail-close |
| Hook | try-catch, CancellationException rethrow | fail-open |
| Bean | `@ConditionalOnMissingBean`, `ObjectProvider<T>` | JDBC `@Primary` |

## 기본 설정

| Property | Default | Property | Default |
|----------|---------|----------|---------|
| `max-tool-calls` | 10 | `concurrency.request-timeout-ms` | 30000 |
| `max-tools-per-request` | 30 | `concurrency.tool-call-timeout-ms` | 15000 |
| `llm.temperature` | 0.1 | `guard.rate-limit-per-minute` | 20 |
| `llm.max-context-window-tokens` | 128000 | `guard.rate-limit-per-hour` | 200 |

| `model-routing.enabled` | false | `tool-idempotency.enabled` | false |
| `checkpoint.enabled` | false | `tool-filter.enabled` | false |
| `a2a.enabled` | false | `budget.max-tokens-per-request` | 0 (무제한) |

모든 기능 기본 비활성(opt-in). 검증: `./gradlew compileKotlin compileTestKotlin` (0 warnings) + `./gradlew test`.

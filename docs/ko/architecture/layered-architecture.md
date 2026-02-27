# Arc Reactor 프로젝트 백과사전 (요청 흐름 + 계층 + 코드 지도)

이 문서는 Arc Reactor를 한 번에 이해하기 위한 단일 문서입니다.
목표는 다음 3가지입니다.

1. 유저 요청이 들어왔을 때 코드가 실제로 어떻게 흘러가는지 추적 가능해야 함
2. 레이어를 시스템 계층과 보안 계층으로 분리해 혼동을 제거해야 함
3. 기능/토글/모듈/저장소 선택이 어떤 조건에서 활성화되는지 빠르게 판단 가능해야 함

---

## 1) 30초 요약

- Arc Reactor는 `Spring AI + ReAct` 기반 에이전트 프레임워크다.
- 실행 중심은 `SpringAiAgentExecutor`이며, 최근 `Coordinator/Finalizer` 구조로 분리되어 가독성이 높아졌다.
- 핵심 요청 흐름: **Controller → Guard/Hook → Intent/RAG/Memory → ReAct(LLM↔Tool) → Output Guard/Filter/Boundary → History/Metric 저장 → 응답**.
- `L0~L4`는 전체 아키텍처 레이어가 아니라 **Guard 보안 레이어**다.
- 저장소는 기본 InMemory, `spring.datasource.url`이 있으면 JDBC 스토어가 `@Primary`로 대체된다.

---

## 2) 모듈 구성 (빌드/런타임 관점)

`settings.gradle.kts` 기준 모듈:

- `arc-core`
- `arc-web`
- `arc-slack`
- `arc-discord`
- `arc-line`
- `arc-error-report`
- `arc-admin`
- `arc-app`

### 2.1 모듈 역할

| 모듈 | 역할 | 활성 조건/특징 |
|---|---|---|
| `arc-core` | 에이전트 엔진, Guard/Hook, Memory/RAG/Intent, MCP, 스토어, 오토컨피그 | 필수 코어 |
| `arc-web` | HTTP/SSE API 컨트롤러, 웹 필터(Security Headers/API Version/CORS), OpenAPI | 런타임 포함 시 API 제공 |
| `arc-slack` | Slack Events API / Slash Command 처리 | `arc.reactor.slack.enabled=true` |
| `arc-discord` | Discord Gateway 리스너 기반 메시지 처리 | `arc.reactor.discord.enabled=true` |
| `arc-line` | LINE Webhook 처리 | `arc.reactor.line.enabled=true` |
| `arc-error-report` | 운영 오류 리포트 수집/비동기 분석 | `arc.reactor.error-report.enabled=true` |
| `arc-admin` | 테넌트/플랫폼 운영 API, 메트릭 수집/가격정책/알림 | `arc.reactor.admin.enabled=true` |
| `arc-app` | 실행 조립 모듈(bootstrap) | `runtimeOnly`로 web/channel/admin 결합 |

### 2.2 실행 진입점

- `arc-app`은 main 코드를 두지 않고 실행 조립만 담당한다.
- 실제 main은 `arc-core/src/main/kotlin/com/arc/reactor/ArcReactorApplication.kt`.
- `arc-app`의 `springBoot.mainClass`는 `com.arc.reactor.ArcReactorApplicationKt`를 가리킨다.

---

## 3) 레이어 모델: 두 종류를 분리해서 보기

## 3.1 시스템 레이어 (Arc Reactor 전체)

| 레이어 | 역할 | 대표 클래스/모듈 |
|---|---|---|
| L7 Interface | 외부 진입점 | `arc-web` 컨트롤러, Slack/LINE/Discord 입력 어댑터 |
| L6 Orchestration | 요청 실행 오케스트레이션 | `SpringAiAgentExecutor`, `AgentExecutionCoordinator`, `StreamingExecutionCoordinator` |
| L5 Policy & Safety | 정책/보안/거버넌스 | `GuardPipeline`, `HookExecutor`, `OutputGuardPipeline`, ToolPolicy/HITL |
| L4 Reasoning & Tooling | ReAct 루프 + 도구 실행 | `ManualReActLoopExecutor`, `StreamingReActLoopExecutor`, `ToolCallOrchestrator` |
| L3 Context & Intelligence | 맥락/지식/의도 보강 | `ConversationManager`, `RagPipeline`, `IntentResolver`, `SystemPromptBuilder` |
| L2 Runtime Services | 안정성/성능/복원력 | `RetryExecutor`, timeout/semaphore, cache, fallback, circuit breaker |
| L1 Platform & Data | 저장/관측/운영 | JDBC stores, metrics, audit, admin collector |

## 3.2 Guard 보안 레이어 (L0~L4)

중요: `L0~L4`는 시스템 전체 레이어가 아니라 **보안 방어 계층**이다.

| Guard Layer | 의미 | 대표 구성 |
|---|---|---|
| L0 Static Fast Filters | 빠른 정적 방어 | Unicode 정규화, Rate limit, Input validation, Injection regex |
| L1 Classification | 분류 기반 위험 판단 | Rule/LLM classification, topic drift |
| L2 Prompt Leakage Defense | 시스템 프롬프트 유출 방어 | Canary token + leakage output guard |
| L3 Tool Output Defense | 도구 결과 간접 인젝션 방어 | `ToolOutputSanitizer` |
| L4 Audit & Observability | 감사/지표 | Guard audit publisher, output guard action metrics |

---

## 4) 요청 진입점 지도 (Ingress Map)

## 4.1 웹 API (`arc-web`)

핵심 진입점:

- `/api/chat` (동기 응답)
- `/api/chat/stream` (SSE 스트리밍)
- `/api/chat/multipart` (멀티모달 업로드, 조건부)

운영/관리 진입점:

- 세션/모델: `/api/sessions`, `/api/models`
- 페르소나: `/api/personas`
- 프롬프트 템플릿: `/api/prompt-templates`
- MCP 서버/정책: `/api/mcp/servers`, `/api/mcp/servers/{name}/access-policy`
- Output Guard 룰: `/api/output-guard/rules`
- RAG 문서/수집정책/후보: `/api/documents`, `/api/rag-ingestion/policy`, `/api/rag-ingestion/candidates`
- Intent 정의: `/api/intents`
- Approval(HITL): `/api/approvals` (조건부)
- Scheduler: `/api/scheduler/jobs` (조건부)
- Auth: `/api/auth` (조건부)
- Ops/Admin audit: `/api/ops`, `/api/admin/audits`

## 4.2 채널 입력

- Slack Events API: `POST /api/slack/events`
- Slack Slash Command: `POST /api/slack/commands`
- LINE Webhook: `POST /api/line/webhook`
- Discord: HTTP 엔드포인트가 아니라 Gateway 이벤트 리스너(`ApplicationReadyEvent` 이후 subscribe)

## 4.3 운영 오류 수집

- Error Report: `POST /api/error-report` (API Key 검사 후 비동기 처리)

---

## 5) 웹 요청 1건의 실제 코드 흐름 (Non-Streaming)

아래 흐름은 `POST /api/chat` 기준이다.

```text
ChatController.chat()
  -> AgentCommand 생성
     - systemPrompt 우선순위 적용(persona/template/direct/default/fallback)
     - userId 우선순위(JWT -> request.userId -> anonymous)
     - metadata 보강(channel/tenant/promptVersion)
  -> (옵션) applyIntentProfile
  -> agentExecutor.execute(command)
      SpringAiAgentExecutor.execute()
        -> runContext open + metadata(model/provider) 주입
        -> semaphore + request timeout
        -> AgentExecutionCoordinator.execute()
           1) Guard + BeforeStart Hook
           2) Intent resolve/apply (실패 시 원본 커맨드 유지)
           3) Cache lookup (조건 충족 시)
           4) Conversation history load
           5) RAG context retrieve (옵션)
           6) Tool selection
           7) executeWithTools() -> ManualReActLoopExecutor
              - 메시지 trim
              - LLM 호출
              - toolCalls 있으면 ToolCallOrchestrator 병렬 실행
              - ToolResponseMessage 추가 후 반복
              - maxToolCalls 도달 시 tools 비활성화 후 final answer 유도
           8) (옵션) fallback model 실행
           9) ExecutionResultFinalizer.finalize()
              - OutputGuard (fail-close)
              - Boundary 검사 (output min/max 정책)
              - Response filter chain
              - Memory 저장
              - AfterComplete hook
              - 메트릭 기록
          10) 성공 결과 cache write
```

핵심 클래스:

- `arc-web/.../ChatController.kt`
- `arc-core/.../SpringAiAgentExecutor.kt`
- `arc-core/.../AgentExecutionCoordinator.kt`
- `arc-core/.../ManualReActLoopExecutor.kt`
- `arc-core/.../ToolCallOrchestrator.kt`
- `arc-core/.../ExecutionResultFinalizer.kt`

---

## 6) 스트리밍 흐름 (`/api/chat/stream`) 차이점

스트리밍은 별도 코디네이터를 사용한다.

```text
ChatController.chatStream()
  -> agentExecutor.executeStream(command): Flow<String>
    SpringAiAgentExecutor.executeStream()
      -> StreamingExecutionCoordinator.execute()
         - Guard / BeforeHook / Intent 검사
         - responseFormat != TEXT 면 즉시 에러 이벤트
         - StreamingReActLoopExecutor 반복
           * token chunk emit
           * tool_start/tool_end marker emit
      -> finally: StreamingFlowLifecycleCoordinator.finalize()
         -> StreamingCompletionFinalizer.finalize()
            - (옵션) OutputGuard 검사
            - boundary 위반 marker emit
            - streaming history 저장
            - AfterComplete hook
```

SSE event 타입:

- `message`
- `tool_start`
- `tool_end`
- `error`
- `done`

---

## 7) Guard / Hook / Output Guard 실패 정책

| 컴포넌트 | 기본 정책 | 의미 |
|---|---|---|
| Guard (`GuardPipeline`) | fail-close | 스테이지 예외 시 요청 차단 |
| Hook (`HookExecutor`) | fail-open | hook 예외 시 로깅 후 계속, 단 `failOnError=true`면 reject/throw |
| Output Guard (`OutputGuardPipeline`) | fail-close | 검사 예외 시 응답 거부 |

실무 체크포인트:

- 보안 필수 검사는 Hook이 아닌 Guard에 둬야 한다.
- Hook은 확장 포인트, Guard는 게이트라는 역할 분리를 유지해야 한다.

---

## 8) Intent / Memory / RAG 계층 동작

## 8.1 Intent

- `IntentResolver`는 분류 실패/저신뢰도면 기본 파이프라인으로 복귀한다.
- 신뢰도 기준을 넘으면 profile을 `AgentCommand`에 병합 적용한다.
- `blockedIntents`에 매칭되면 `BlockedIntentException`으로 차단된다.
- profile에서 `allowedTools`를 주면 도구 호출 allowlist가 활성화된다.

## 8.2 Memory

- `ConversationManager`가 히스토리 load/save의 단일 관문이다.
- `sessionId` 기반으로 `MemoryStore`에서 대화를 읽고 저장한다.
- summary 기능이 켜지면 오래된 대화를 `facts + narrative`로 계층 요약한다.
- 스트리밍은 `saveStreamingHistory()`로 별도 저장한다.

## 8.3 RAG

- `DefaultRagPipeline`: `Query Transform -> Retrieve -> Rerank -> Context Build`.
- `arc.reactor.rag.enabled=true`인데 `VectorStore`가 없으면 부팅 실패(의도된 fail-fast).
- query transformer는 `passthrough` 또는 `hyde`.

---

## 9) Tooling/MCP 계층 동작

## 9.1 도구 실행

- 로컬 툴 + 콜백 + MCP 툴이 하나의 tool 리스트로 결합된다.
- `ToolCallOrchestrator`가 병렬 실행, timeout, hook, approval, sanitizer를 담당한다.
- 도구 출력 sanitize는 `arc.reactor.guard.tool-output-sanitization-enabled=true`일 때 동작한다.

## 9.2 HITL 승인

- `ToolApprovalPolicy`가 승인 필요 여부를 판단한다.
- 승인 필요 시 `PendingApprovalStore`에 요청하고 대기한다.
- 승인 거절/오류면 해당 tool call은 실패 응답으로 변환된다.

## 9.3 MCP

- MCP 서버는 코드 하드코딩이 아닌 REST API로 등록/관리한다.
- `DefaultMcpManager` 책임:
  - 서버 등록/연결/해제
  - tool callback 캐시
  - 상태 추적(`PENDING/CONNECTING/CONNECTED/FAILED/...`)
  - store 동기화 + 자동 재연결 + 온디맨드 재연결

---

## 10) 저장소 전략 (InMemory ↔ JDBC)

기본:

- `MemoryStore`, `PersonaStore`, `PromptTemplateStore`, `McpServerStore` 등은 InMemory 구현 제공

JDBC 전환 조건:

- `spring.datasource.url` 존재 + JDBC 클래스패스 충족 시 JDBC 구현이 `@Primary` 등록
- 대상: Memory/Persona/PromptTemplate/MCP/OutputGuardRule/Audit/Scheduler/Approval/Feedback/RAG 후보/ToolPolicy/RAG 정책 등

주의:

- `ArcReactorCoreBeansConfiguration.postgresRequirementMarker`는 기본적으로 PostgreSQL URL을 강제한다.
- 운영에서 PostgreSQL 미사용이면 `arc.reactor.postgres.required=false`를 명시해야 한다.

---

## 11) 주요 Feature Toggle 맵

| 기능 | 기본값 | 키 |
|---|---|---|
| Guard | ON | `arc.reactor.guard.enabled` |
| Security Headers | ON | `arc.reactor.security-headers.enabled` |
| API Version Filter | ON | `arc.reactor.api-version.enabled` |
| Auth(JWT) | OFF | `arc.reactor.auth.enabled` |
| CORS | OFF | `arc.reactor.cors.enabled` |
| RAG | OFF | `arc.reactor.rag.enabled` |
| Intent | OFF | `arc.reactor.intent.enabled` |
| Output Guard | OFF | `arc.reactor.output-guard.enabled` |
| Approval(HITL) | OFF | `arc.reactor.approval.enabled` |
| Tool Policy | OFF | `arc.reactor.tool-policy.enabled` |
| Scheduler | OFF | `arc.reactor.scheduler.enabled` |
| Cache | OFF | `arc.reactor.cache.enabled` |
| Fallback | OFF | `arc.reactor.fallback.enabled` |
| Circuit Breaker | OFF | `arc.reactor.circuit-breaker.enabled` |
| Memory Summary | OFF | `arc.reactor.memory.summary.enabled` |
| Admin 모듈 | OFF | `arc.reactor.admin.enabled` |
| Slack 모듈 | OFF | `arc.reactor.slack.enabled` |
| Discord 모듈 | OFF | `arc.reactor.discord.enabled` |
| LINE 모듈 | OFF | `arc.reactor.line.enabled` |
| Error Report 모듈 | OFF | `arc.reactor.error-report.enabled` |

---

## 12) 웹/API 계층에서 자주 혼동되는 포인트

1. `L0~L4`는 Guard 보안 레이어이지 시스템 전체 레이어가 아니다.
2. Auth가 꺼져 있으면 role이 null이므로 admin 체크에서 모든 요청이 admin으로 간주된다(호환성 정책).
3. 스트리밍 모드는 `TEXT`만 허용한다(JSON/YAML 구조화 응답 불가).
4. `maxToolCalls` 도달 시 tools를 비워 루프를 끊어야 한다(무한 반복 방지 핵심).
5. MCP 서버 등록은 설정파일이 아니라 API로 해야 한다.

---

## 13) 코드 탐색 가이드 (변경 목적별 진입점)

| 변경 목적 | 먼저 볼 파일 |
|---|---|
| 요청 전체 흐름 | `agent/impl/SpringAiAgentExecutor.kt` |
| 실행 분기/캐시/폴백 | `agent/impl/AgentExecutionCoordinator.kt` |
| 최종 후처리/출력거버넌스 | `agent/impl/ExecutionResultFinalizer.kt` |
| 스트리밍 처리 | `agent/impl/StreamingExecutionCoordinator.kt`, `StreamingCompletionFinalizer.kt` |
| Guard 단계/정책 | `guard/impl/GuardPipeline.kt`, `autoconfigure/ArcReactorGuardConfiguration.kt` |
| Hook 정책 | `hook/HookExecutor.kt`, `autoconfigure/ArcReactorHookAndMcpConfiguration.kt` |
| 도구 호출/HITL | `agent/impl/ToolCallOrchestrator.kt`, approval 관련 store/policy |
| Intent | `intent/IntentResolver.kt`, `autoconfigure/ArcReactorIntentConfiguration.kt` |
| RAG | `autoconfigure/ArcReactorRagConfiguration.kt`, `rag/impl/DefaultRagPipeline.kt` |
| Memory 요약 | `memory/ConversationManager.kt`, `memory/summary/*` |
| MCP | `mcp/McpManager.kt`, `controller/McpServerController.kt` |
| 웹 필터/보안 헤더/CORS | `arc-web/autoconfigure/*` |

---

## 14) 운영 시나리오 예시: 웹 채팅 + Intent + RAG + Output Guard

전제:

- `intent.enabled=true`
- `rag.enabled=true`
- `output-guard.enabled=true`

실행:

1. `/api/chat` 요청 수신
2. Guard 통과 + BeforeStart hook 통과
3. Intent 분류 성공 -> profile 적용(모델/allowedTools/temperature 변경 가능)
4. 세션 히스토리 로드 + RAG 컨텍스트 삽입
5. ReAct 루프에서 도구 실행 반복
6. 최종 답변 생성 후 Output Guard 검사
7. 응답 필터/경계 체크
8. 히스토리 저장 + AfterComplete hook + 메트릭 기록
9. 응답 반환

이 시나리오에서 가장 많이 이슈가 나는 지점:

- 분류 결과의 `allowedTools`와 실제 툴 이름 불일치
- RAG 활성인데 VectorStore 미설정
- Output Guard 규칙 과도 설정으로 정상 응답 차단

---

## 15) 관련 문서

- [아키텍처 개요](architecture.md)
- [모듈 레이아웃](module-layout.md)
- [Guard와 Hook](guard-hook.md)
- [ReAct 루프](react-loop.md)
- [Streaming ReAct](streaming-react.md)
- [응답 처리](response-processing.md)
- [세션 관리](session-management.md)
- [MCP 문서 묶음](mcp.md)
- [Memory/RAG 문서 묶음](memory-rag.md)

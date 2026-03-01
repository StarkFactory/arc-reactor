# 아키텍처 가이드

## 전체 구조

Arc Reactor 런타임은 6개 핵심 컴포넌트로 구성됩니다:

```
                        ┌─────────────┐
                        │  Controller │  REST API (채팅, 스트리밍)
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │   Agent     │  ReAct 루프 + 병렬 도구 실행
                        │  Executor   │  컨텍스트 윈도우 관리 + LLM 재시도
                        └──┬───┬───┬──┘
                           │   │   │
              ┌────────────┘   │   └────────────┐
              ▼                ▼                 ▼
        ┌──────────┐   ┌──────────┐      ┌──────────┐
        │  Guard   │   │   Hook   │      │   Tool   │
        │ Pipeline │   │ Executor │      │  System  │
        └──────────┘   └──────────┘      └──────────┘
                               │
                    ┌──────────┼──────────┐
                    ▼          ▼          ▼
              ┌──────────┐ ┌──────┐ ┌─────────┐
              │  Memory  │ │ RAG  │ │   MCP   │
              └──────────┘ └──────┘ └─────────┘
```

Gradle 모듈 경계와 실행 조립 엔트리포인트는
[모듈 레이아웃 가이드](module-layout.md)를 참고하세요.

## 요청 처리 흐름

### 1. Guard Pipeline (보안 검사)

```
요청 → RateLimit → InputValidation → InjectionDetection → Classification → Permission → 통과
        (10/분)    (10000자 제한)    (프롬프트 인젝션)      (카테고리 분류)   (권한 확인)
```

- 각 단계는 `GuardStage` 인터페이스 구현
- `order` 필드로 실행 순서 결정 (낮을수록 먼저)
- 하나라도 `Rejected` 반환하면 즉시 중단
- `@Component`로 빈 등록하면 자동으로 파이프라인에 추가

### 2. Hook System (라이프사이클)

```
BeforeAgentStart → [에이전트 실행] → AfterAgentComplete
                         │
                  BeforeToolCall → [도구 실행] → AfterToolCall
```

| Hook | 시점 | 용도 |
|------|------|------|
| `BeforeAgentStart` | 에이전트 시작 전 | 인증, 요금 확인, 거부 가능 |
| `BeforeToolCall` | 도구 호출 전 | 감사 로그, 도구 차단 |
| `AfterToolCall` | 도구 호출 후 | 결과 기록, 알림 |
| `AfterAgentComplete` | 에이전트 완료 후 | 빌링, 통계, 로그 |

- `HookResult.Reject`를 반환하면 실행 중단
- `HookResult.Continue`를 반환하면 계속 진행
- Hook 예외는 에이전트 결과에 영향을 주지 않음 (격리됨)

### 3. ReAct Loop (핵심 실행 루프)

```
while (true) {
    1. 컨텍스트 윈도우 트리밍 (토큰 예산 내로)
    2. LLM 호출 (재시도 포함)
    3. 도구 호출 감지?
       - 없음 → 최종 응답 반환
       - 있음 → 도구 병렬 실행 → 결과를 메시지에 추가 → 루프 계속
    4. maxToolCalls 도달 시 도구 제거 → 최종 답변 요청
}
```

**컨텍스트 윈도우 트리밍:**
- 예산 = maxContextWindowTokens - systemPromptTokens - maxOutputTokens
- 초과 시 가장 오래된 메시지부터 제거
- AssistantMessage(toolCalls) + ToolResponseMessage는 쌍으로 제거
- 현재 사용자 프롬프트(마지막 UserMessage)는 절대 제거하지 않음

**LLM 재시도:**
- 일시적 에러(429, 5xx, timeout) → 지수 백오프 + ±25% 지터
- 비일시적 에러(인증, 컨텍스트 초과) → 즉시 실패
- `CancellationException`은 절대 재시도하지 않음 (코루틴 존중)

**병렬 도구 실행:**
- `coroutineScope { map { async { } }.awaitAll() }`
- 순서 보장 (map 인덱스 순)
- 각 도구별 BeforeToolCall/AfterToolCall Hook 실행

### 4. Memory System

```
ConversationManager (대화 생명주기 관리)
         │
         ▼
    MemoryStore (저장소)
    ├── InMemoryMemoryStore    ← 기본 (서버 재시작 시 유실)
    └── JdbcMemoryStore        ← PostgreSQL (DataSource 감지 시 자동 전환)
```

- `ConversationManager.loadHistory()`: 세션 기록 로드
- `ConversationManager.saveHistory()`: 성공 시에만 저장
- `maxConversationTurns`로 히스토리 크기 제한

### 5. RAG Pipeline

```
Query → DocumentRetriever → DocumentReranker → Context Builder → System Prompt에 주입
         (벡터 검색)        (재순위화)          (토큰 기반 빌드)
```

- `arc.reactor.rag.enabled = true`로 활성화
- `VectorStore` 빈이 있으면 Spring AI VectorStore 사용
- 없으면 InMemoryDocumentRetriever 사용 (테스트용)

### 6. MCP Integration

```
McpManager
├── register(server)    → STDIO 또는 SSE 트랜스포트 설정
├── connect(name)       → 서버 연결 + 도구 목록 조회
└── getAllToolCallbacks() → tool name 기준 중복 제거된 MCP 도구 반환
```

- 외부 MCP 서버의 도구가 자동으로 에이전트의 도구 목록에 추가됨
- STDIO (로컬 프로세스) / SSE (원격 서버) 모두 지원

## 멀티에이전트 아키텍처

```
MultiAgent (DSL Builder)
├── .sequential() → SequentialOrchestrator
│                    A → B → C (출력이 다음 입력)
├── .parallel()   → ParallelOrchestrator
│                    A ┐
│                    B ├→ ResultMerger → 합산 결과
│                    C ┘
└── .supervisor()  → SupervisorOrchestrator
                     Supervisor ← WorkerAgentTool(A)
                              ← WorkerAgentTool(B)
                              ← WorkerAgentTool(C)
```

**Supervisor 핵심:** `WorkerAgentTool`이 에이전트를 `ToolCallback`으로 감싸서 기존 ReAct 루프가 워커를 "도구처럼" 호출합니다. `SpringAiAgentExecutor` 수정 없이 동작합니다.

## 에러 처리 체계

모든 실패는 `AgentResult`에 `errorCode`와 `errorMessage`가 설정됩니다:

| errorCode | 발생 시점 | 설명 |
|-----------|-----------|------|
| `GUARD_REJECTED` | Guard 파이프라인 거부 | Rate limit, 입력 검증 등 |
| `HOOK_REJECTED` | Before hook 거부 | 인증, 비용 초과 등 |
| `RATE_LIMITED` | LLM API 429 | Rate limit exceeded |
| `TIMEOUT` | 요청 시간 초과 | withTimeout 만료 |
| `CONTEXT_TOO_LONG` | 컨텍스트 초과 | LLM 입력 한도 초과 |
| `TOOL_ERROR` | 도구 실행 실패 | 도구 내부 에러 |
| `UNKNOWN` | 기타 에러 | 분류 불가 |

`ErrorMessageResolver`를 구현하면 에러 메시지를 한국어 등으로 커스터마이징 가능합니다.

## Spring Auto-Configuration

`ArcReactorAutoConfiguration`이 모든 빈을 자동 등록합니다:

| 빈 | 조건 | 기본값 |
|----|------|-------|
| `MemoryStore` | `DataSource` 있음 → JDBC, 없음 → InMemory | InMemory |
| `ConversationManager` | `@ConditionalOnMissingBean` | DefaultConversationManager |
| `ToolSelector` | `@ConditionalOnMissingBean` | AllToolSelector |
| `ErrorMessageResolver` | `@ConditionalOnMissingBean` | English messages |
| `AgentMetrics` | `@ConditionalOnMissingBean` | NoOpAgentMetrics |
| `TokenEstimator` | `@ConditionalOnMissingBean` | DefaultTokenEstimator |
| `HookExecutor` | 등록된 Hook 빈 수집 | 빈 Hook 목록 |
| `RequestGuard` | `guard.enabled = true` | RateLimit + InputValidation + InjectionDetection |

모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 있어서 직접 빈을 등록하면 자동 설정이 무시됩니다.

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

전체 요청 생명주기는 `SpringAiAgentExecutor`와 내부 코디네이터들이 관리합니다.
각 단계는 지연 시간 메트릭을 기록하며, OpenTelemetry span을 통해 개별 추적이 가능합니다.

```
요청
  │
  ├─ 1. Concurrency Gate       (세마포어, 요청 타임아웃)
  ├─ 2. Guard Pipeline         (입력 보안 검사)
  ├─ 3. Hook: BeforeAgentStart (인증, 빌링, 거부 가능)
  ├─ 4. Intent Classification  (선택, 인텐트 프로파일로 라우팅)
  ├─ 5. Response Cache Check   (선택, exact + semantic 조회)
  ├─ 6. Load Conversation History (선택적 요약 주입 포함)
  ├─ 7. RAG Retrieval          (선택, adaptive routing 포함)
  ├─ 8. Tool Selection         (ToolSelector로 사용 가능한 도구 필터링)
  ├─ 9. ReAct Loop             (LLM <-> Tool, before/after Hook 포함)
  ├─10. Fallback Strategy      (선택, ReAct 실패 시)
  ├─11. Output Guard Pipeline  (PII 마스킹, 유출 탐지, 동적 규칙)
  ├─12. Output Boundary Check  (최소/최대 길이, 재시도로 더 긴 응답 요청)
  ├─13. Response Filtering     (ResponseFilterChain: 최대 길이, 검증된 소스)
  ├─14. Citation Formatting    (선택, 검증된 소스 링크 추가)
  ├─15. Save Conversation History (활성화 시 비동기 요약)
  ├─16. Hook: AfterAgentComplete (빌링, 통계, 로깅)
  │
  └─ 응답
```

### 1. Guard Pipeline (보안 검사)

**Input Guard** (실행 전):
```
요청 → UnicodeNorm → RateLimit → InputValidation → InjectionDetection → Classification → 통과
        (order=0)     (order=1)    (order=2)          (order=3)            (opt-in, order=4)
```

추가 opt-in 입력 가드 단계:
- **TopicDriftDetection** (order=10) — 대화 히스토리 기반으로 주제 이탈 요청 탐지
- **Permission** (order=5) — 커스텀 권한 검사

**Output Guard** (실행 후, `arc.reactor.output-guard.enabled=true`로 opt-in):
```
응답 → SystemPromptLeakage → PiiMasking → DynamicRule → RegexPattern → 통과
       (opt-in, order=5)     (order=10)    (order=15)    (order=20)
```

- OWASP LLM Top 10 (2025)에 맞춘 5계층 방어 아키텍처
- 각 단계는 `GuardStage` (입력) 또는 `OutputGuardStage` (출력) 구현
- `order` 필드로 실행 순서 결정 (낮을수록 먼저)
- 하나라도 `Rejected` 반환하면 즉시 중단
- `@Component`로 빈 등록하면 자동으로 파이프라인에 추가
- **Input guard**는 fail-close이며 기본 활성화 (`guard.enabled=true`)
- **Output guard**는 fail-close이며 opt-in (`output-guard.enabled=true`)
- **카나리 토큰**은 시스템 프롬프트 유출 탐지를 활성화 (`guard.canary-token-enabled=true`).
  활성화 시 `SystemPromptPostProcessor`가 시스템 프롬프트에 카나리 토큰을 주입하고,
  `SystemPromptLeakageOutputGuard`가 응답에서 유출된 토큰을 검사
- **도구 출력 정제**는 간접 프롬프트 인젝션을 방어
  (`guard.tool-output-sanitization-enabled=true`)
- 전체 레이어 상세 및 OWASP 커버리지는 [Guard & Hook 가이드](guard-hook.md) 참고

### 2. Hook System (라이프사이클)

```
BeforeAgentStart → [에이전트 실행] → AfterAgentComplete
                         │
                  BeforeToolCall → [도구 실행] → AfterToolCall
```

| Hook | 시점 | 용도 |
|------|------|------|
| `BeforeAgentStart` | 에이전트 시작 전 | 인증, 빌링 확인, 거부 가능 |
| `BeforeToolCall` | 도구 호출 전 | 감사 로그, 도구 차단 |
| `AfterToolCall` | 도구 호출 후 | 결과 기록, 알림 |
| `AfterAgentComplete` | 에이전트 완료 후 | 빌링, 통계, 로그 |

- `HookResult.Reject`를 반환하면 실행 중단
- `HookResult.Continue`를 반환하면 계속 진행
- Hook 예외는 에이전트 결과에 영향을 주지 않음 (격리됨)

### 3. Intent Classification (선택)

`arc.reactor.intent.enabled=true`일 때, 인텐트 분류는 가드 파이프라인 이후,
캐시 검사 이전에 실행됩니다:

```
UserPrompt → RuleBasedClassifier → (임계치 미달?) → LlmClassifier → IntentProfile
```

- 복합 분류기: 규칙 기반 우선, 신뢰도가 임계치 미만이면 LLM으로 캐스케이드
- 차단된 인텐트 (`intent.blockedIntents`로 설정)는 `GUARD_REJECTED`로 즉시 거부
- 확인된 인텐트 프로파일로 시스템 프롬프트, 허용 도구, temperature, 최대 도구 호출 수를 오버라이드 가능
- 페일세이프: 분류 오류 시 원래 명령이 변경 없이 사용됨

### 4. Response Cache (선택)

`arc.reactor.cache.enabled=true`일 때, 중복 LLM 호출을 피하기 위해 응답이 캐시됩니다:

- **Exact cache**: 사용자 프롬프트 + 시스템 프롬프트 + 도구 이름 + temperature로 키 구성
- **Semantic cache** (`cache.semantic.enabled=true`로 opt-in): Redis + 임베딩 유사도
- 유효 temperature가 `cache.cacheableTemperature` 이하일 때만 캐시 가능
- 캐시 히트 시 전체 ReAct 루프를 건너뛰고 즉시 반환

### 5. ReAct Loop (핵심 실행 루프)

```
while (true) {
    1. 컨텍스트 윈도우 트리밍 (토큰 예산 내로)
    2. LLM 호출 (재시도 + 서킷 브레이커 포함)
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
- `CancellationException`은 절대 재시도하지 않음 (구조적 동시성 존중)
- 선택적 서킷 브레이커 (`circuit-breaker.enabled=true`)는 반복 실패 시 회로 오픈

**병렬 도구 실행:**
- `coroutineScope { map { async { } }.awaitAll() }`
- 순서 보장 (map 인덱스 순)
- 각 도구별 BeforeToolCall/AfterToolCall Hook 실행
- 도구 승인 정책 (HITL)으로 사람의 검토를 위해 실행을 일시 중지 가능
- 도구 출력 정제로 도구 결과에서 인젝션 시도를 제거

**폴백 전략:**
- `fallback.enabled=true`이고 ReAct 루프가 실패하면, `fallback.models`에 설정된
  대체 LLM 모델로 재시도
- 메타데이터에 폴백 사용 여부를 기록 (`fallbackUsed=true`)

### 6. Post-Execution Pipeline

ReAct 루프가 결과를 생성한 후, `ExecutionResultFinalizer`가 다음 단계를
순서대로 실행합니다:

1. **Output Guard** — PII 마스킹, 시스템 프롬프트 유출 탐지, 동적 규칙,
   정규식 패턴. Fail-close: 거부 시 `OUTPUT_GUARD_REJECTED` 반환
2. **Output Boundary Check** — `boundaries.outputMinChars`와
   `boundaries.outputMaxChars`를 강제. 도구를 사용하지 않았는데 응답이 너무
   짧으면, 추가 LLM 호출로 더 긴 응답을 시도. 여전히 너무 짧으면
   `OUTPUT_TOO_SHORT` 반환
3. **Re-guard** — 경계 검사가 내용을 변경한 경우(예: 더 긴 응답 재시도로
   새로운 출력이 생성됨), 출력 가드를 다시 실행
4. **Response Filter Chain** — 순서대로 적용되는 플러그형 필터:
   `MaxLengthResponseFilter` (`response.maxLength > 0`일 때),
   `VerifiedSourcesResponseFilter` (항상 등록됨)
5. **Citation Formatting** — `citation.enabled=true`일 때,
   `hookContext.verifiedSources`에서 번호 매긴 소스 목록을 추가
6. **Save Conversation History** — 성공 시 `conversationManager.saveHistory()`를
   호출. 메모리 요약이 활성화되어 있으면 요약이 비동기로 생성됨
7. **Hook: AfterAgentComplete** — 최종 결과, 사용된 도구, 총 소요 시간과 함께
   완료 후 Hook 실행

### 7. Memory System

```
ConversationManager (대화 생명주기 관리)
         │
         ├── MemoryStore (메시지 저장소)
         │   ├── InMemoryMemoryStore    ← 기본 (서버 재시작 시 유실)
         │   └── JdbcMemoryStore        ← PostgreSQL (DataSource 감지 시 @Primary)
         │
         ├── ConversationSummaryService (선택, LLM 기반 요약)
         │   └── LlmConversationSummaryService
         │
         └── ConversationSummaryStore (선택, 요약 영속화)
             ├── InMemoryConversationSummaryStore
             └── JdbcConversationSummaryStore  ← PostgreSQL (@Primary)
```

- `ConversationManager.loadHistory()`: 세션 기록 로드, 가능한 경우 요약 주입
- `ConversationManager.saveHistory()`: 성공 시에만 저장
- `maxConversationTurns`로 히스토리 크기 제한
- **계층적 메모리** (`memory.summary.enabled=true`로 opt-in): 대화 히스토리가
  턴 제한을 초과하면, LLM 기반 서비스가 서술형 요약을 생성하여 별도 저장하고
  이후 대화에 주입
- **사용자 메모리** (`memory.user.enabled=true`로 opt-in): 세션 간 지속되는
  사용자별 장기 메모리. `memory.user.inject-into-prompt=true`일 때
  `UserMemoryInjectionHook`을 통해 시스템 프롬프트에 사용자 컨텍스트를 추가

### 8. RAG Pipeline

```
Query → QueryTransformer → QueryRouter → DocumentRetriever → DocumentReranker
         (passthrough/     (adaptive      (벡터 검색)          (점수 기반
          hyde/decompose)   routing)                            재순위화)
                                              │
                                              ▼
                              ContextCompressor → Context Builder → 시스템 프롬프트에 주입
                              (선택, LLM)        (토큰 기반)
```

- `arc.reactor.rag.enabled=true`로 활성화
- `VectorStore` 빈 필요 (예: PGVector)
- **쿼리 변환** (`rag.queryTransformer`): `passthrough` (기본), `hyde`
  (가상 문서 임베딩), `decomposition` (다중 하위 쿼리)
- **적응형 라우팅** (`rag.adaptive-routing.enabled=true`로 opt-in): LLM을 통해
  쿼리 복잡도를 분류 (SIMPLE vs COMPLEX)하고 `topK`를 조정
- **하이브리드 검색** (`rag.hybrid.enabled=true`로 opt-in): BM25 키워드 점수와
  벡터 유사도를 Reciprocal Rank Fusion (RRF)으로 결합
- **컨텍스트 압축** (`rag.compression.enabled=true`로 opt-in): LLM 기반 압축으로
  주입 전 무관한 내용 제거
- **부모 문서 검색** (`rag.parent-retrieval.enabled=true`로 opt-in): 검색된
  청크를 주변 컨텍스트로 확장

### 9. MCP Integration

```
McpManager
├── register(server)    → STDIO 또는 SSE 트랜스포트 설정
├── connect(name)       → 서버 연결 + 도구 목록 조회
└── getAllToolCallbacks() → tool name 기준 중복 제거된 MCP 도구 반환
```

- 외부 MCP 서버의 도구가 자동으로 에이전트의 도구 목록에 추가됨
- STDIO (로컬 프로세스) / SSE (원격 서버) 모두 지원
- MCP 보안 정책으로 허용된 서버 이름과 최대 도구 출력 길이를 제어

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
| `GUARD_REJECTED` | Guard 파이프라인 거부 | Rate limit, 입력 검증, 차단된 인텐트 등 |
| `HOOK_REJECTED` | Before hook 거부 | 인증, 비용 초과 등 |
| `RATE_LIMITED` | Guard rate limit 또는 LLM API 429 | Rate limit 초과 |
| `TIMEOUT` | 요청 시간 초과 | withTimeout 만료 |
| `CONTEXT_TOO_LONG` | 컨텍스트 초과 | LLM 입력 한도 초과 |
| `TOOL_ERROR` | 도구 실행 실패 | 도구 내부 에러 |
| `INVALID_RESPONSE` | 구조화된 응답 검증 | LLM이 잘못된 JSON/형식을 반환 |
| `OUTPUT_GUARD_REJECTED` | 출력 가드 파이프라인 거부 | PII 유출, 프롬프트 유출, 정책 위반 |
| `OUTPUT_TOO_SHORT` | 출력 경계 검사 | 최소 문자 수 임계치 미달 |
| `CIRCUIT_BREAKER_OPEN` | 서킷 브레이커 트립 | 반복 실패로 인한 서비스 불가 |
| `UNKNOWN` | 기타 에러 | 분류 불가 |

`ErrorMessageResolver`를 구현하면 에러 메시지를 한국어 등으로 커스터마이징 가능합니다.

## Spring Auto-Configuration

`ArcReactorAutoConfiguration`이 모든 빈을 자동 등록합니다. Auto-configuration은
`@Import` 클래스로 분리되어 있습니다:

| Configuration 클래스 | 범위 |
|---------------------|------|
| `TracingConfiguration` | OTel tracer (또는 NoOp 폴백) |
| `ArcReactorCoreBeansConfiguration` | 핵심 빈: 저장소, 메트릭, 도구, 정책 |
| `ArcReactorHookAndMcpConfiguration` | HookExecutor, McpManager, webhook Hook |
| `ArcReactorRuntimeConfiguration` | ChatClient, ChatModelProvider, ResponseFilterChain, ResponseCache, FallbackStrategy, CircuitBreakerRegistry |
| `ArcReactorExecutorConfiguration` | AgentExecutor (모든 의존성 연결) |
| `GuardConfiguration` | 입력 가드 단계 |
| `OutputGuardConfiguration` | 출력 가드 단계 + 파이프라인 |
| `RagConfiguration` | RAG 파이프라인, 검색기, 쿼리 라우터, 청커 |
| `IntentConfiguration` | 인텐트 분류기 + 리졸버 |
| `MemorySummaryConfiguration` | 대화 요약 서비스 + 저장소 |
| `UserMemoryConfiguration` | 사용자별 장기 메모리 |
| `CanaryConfiguration` | 카나리 토큰 주입 + 유출 탐지 |
| `ToolSanitizerConfiguration` | 도구 출력 정제 |
| `PromptCachingConfiguration` | Anthropic 프롬프트 캐싱 |
| `HealthIndicatorConfiguration` | LLM, 데이터베이스, MCP 상태 인디케이터 |
| `AuthConfiguration` | JWT 인증 |
| `SchedulerConfiguration` | 예약 작업 실행 |
| `ArcReactorSemanticCacheConfiguration` | Redis semantic 응답 캐시 |

주요 auto-configured 빈:

| 빈 | 조건 | 기본값 |
|----|------|-------|
| `MemoryStore` | `DataSource` 있음 → JDBC, 없음 → InMemory | InMemory |
| `ConversationManager` | `@ConditionalOnMissingBean` | DefaultConversationManager |
| `ToolSelector` | 전략: `all`, `keyword`, `semantic` | AllToolSelector |
| `ErrorMessageResolver` | `@ConditionalOnMissingBean` | English messages |
| `AgentMetrics` | `MeterRegistry` 있음 → Micrometer, 없음 → NoOp | NoOpAgentMetrics |
| `TokenEstimator` | `@ConditionalOnMissingBean` | DefaultTokenEstimator |
| `HookExecutor` | 등록된 Hook 빈 수집 | 빈 Hook 목록 |
| `RequestGuard` | `guard.enabled=true` (기본) | UnicodeNorm + RateLimit + InputValidation + InjectionDetection |
| `OutputGuardPipeline` | `output-guard.enabled=true` (opt-in) | PiiMasking + DynamicRule + RegexPattern |
| `ChatModelProvider` | `@ConditionalOnMissingBean` | 모든 ChatModel 빈에서 다중 모델 프로바이더 |
| `ResponseFilterChain` | `response.filtersEnabled=true` | VerifiedSourcesResponseFilter (+ 설정 시 MaxLength) |
| `ResponseCache` | `cache.enabled=true` (opt-in) | CaffeineResponseCache |
| `FallbackStrategy` | `fallback.enabled=true` (opt-in) | ModelFallbackStrategy |
| `CircuitBreakerRegistry` | `circuit-breaker.enabled=true` (opt-in) | 설정 가능한 임계치 |
| `IntentResolver` | `intent.enabled=true` (opt-in) | 복합 (규칙 + LLM) 분류기 |
| `RagPipeline` | `rag.enabled=true` (opt-in) | DefaultRagPipeline (또는 HybridRagPipeline) |
| `QueryRouter` | `rag.adaptive-routing.enabled=true` (opt-in) | AdaptiveQueryRouter |
| `ArcReactorTracer` | OTel 클래스패스 + `tracing.enabled=true` (기본) | OtelArcReactorTracer (또는 NoOp) |
| `ToolApprovalPolicy` | `approval.enabled=true` (opt-in) | DynamicToolApprovalPolicy |
| `ConversationSummaryService` | `memory.summary.enabled=true` (opt-in) | LlmConversationSummaryService |
| `UserMemoryManager` | `memory.user.enabled=true` (opt-in) | UserMemoryManager + InjectionHook |

모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 있어서 직접 빈을 등록하면 자동 설정이 무시됩니다.

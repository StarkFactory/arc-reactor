# 설정 레퍼런스 & 자동 구성

> **핵심 파일:** `AgentProperties.kt`, `AgentPolicyAndFeatureProperties.kt`, `ArcReactorAutoConfiguration.kt`
> 이 문서는 Arc Reactor의 모든 설정 옵션과 Spring Boot 자동 구성 메커니즘을 설명합니다.
> 처음 fork해서 시작한다면 [configuration-quickstart.md](configuration-quickstart.md)부터 보세요.

## 전체 설정 구조

```yaml
arc:
  reactor:
    max-tools-per-request: 30    # 요청당 최대 도구 수
    max-tool-calls: 10           # ReAct 루프 최대 도구 호출 횟수

    llm:                         # LLM 호출 설정
      default-provider: gemini
      temperature: 0.1
      max-output-tokens: 4096
      max-conversation-turns: 10
      max-context-window-tokens: 128000
      google-search-retrieval-enabled: false
      prompt-caching:            # Anthropic 프롬프트 캐싱
        enabled: false
        provider: anthropic
        cache-system-prompt: true
        cache-tools: true
        min-cacheable-tokens: 1024

    retry:                       # LLM 재시도 설정
      max-attempts: 3
      initial-delay-ms: 200
      multiplier: 2.0
      max-delay-ms: 10000

    guard:                       # Guard 파이프라인 설정
      enabled: true
      rate-limit-per-minute: 20
      rate-limit-per-hour: 200
      injection-detection-enabled: true
      unicode-normalization-enabled: true
      max-zero-width-ratio: 0.1
      classification-enabled: false
      canary-token-enabled: false
      tool-output-sanitization-enabled: false
      audit-enabled: true
      topic-drift-enabled: false

    boundaries:                  # 입력/출력 경계 검사
      input-min-chars: 1
      input-max-chars: 10000
      system-prompt-max-chars: 50000
      output-min-chars: 0
      output-max-chars: 0
      output-min-violation-mode: warn

    rag:                         # RAG 파이프라인 설정
      enabled: false
      similarity-threshold: 0.65
      top-k: 5
      rerank-enabled: false
      query-transformer: passthrough
      max-context-tokens: 4000
      retrieval-timeout-ms: 3000
      chunking:
        enabled: false
        chunk-size: 512
        min-chunk-threshold: 512
        overlap: 50
      hybrid:
        enabled: false
        bm25-weight: 0.5
        vector-weight: 0.5
        rrf-k: 60.0
      parent-retrieval:
        enabled: false
        window-size: 1
      compression:
        enabled: false
        min-content-length: 200
      adaptive-routing:
        enabled: true
        timeout-ms: 3000
        complex-top-k: 15
      ingestion:
        enabled: false
        require-review: true

    concurrency:                 # 동시성 제어
      max-concurrent-requests: 20
      request-timeout-ms: 30000
      tool-call-timeout-ms: 15000

    cache:                       # 응답 캐싱 (opt-in)
      enabled: false
      max-size: 1000
      ttl-minutes: 60
      cacheable-temperature: 0.0

    circuit-breaker:             # 서킷 브레이커 (opt-in)
      enabled: false
      failure-threshold: 5
      reset-timeout-ms: 30000
      half-open-max-calls: 1

    fallback:                    # 우아한 성능 저하 (opt-in)
      enabled: false
      models: []

    tool-selection:              # Tool 선택 전략
      strategy: semantic
      similarity-threshold: 0.3
      max-results: 10

    approval:                    # Human-in-the-Loop 승인 (opt-in)
      enabled: false
      timeout-ms: 300000
      tool-names: []

    tool-policy:                 # Tool 정책 강제 (opt-in)
      enabled: false
      write-tool-names: []
      deny-write-channels: [slack]

    auth:                        # 인증 설정
      jwt-secret: ""
      default-tenant-id: default
      token-revocation-store: memory  # memory | jdbc | redis
      token-revocation-redis-key-prefix: "arc:auth:revoked"
      public-actuator-health: true    # prod 프로파일에서 false로 오버라이드

    error-report:                # 에러 리포트 에이전트 (opt-in)
      enabled: false
      max-concurrent-requests: 5
      request-timeout-ms: 10000
      max-tool-calls: 3

    output-guard:                # Output Guard (기본 활성)
      enabled: true
      pii-masking-enabled: true
      dynamic-rules-enabled: true
      dynamic-rules-refresh-ms: 3000

    scheduler:                   # 동적 스케줄러 (opt-in)
      enabled: false
      thread-pool-size: 5
      default-execution-timeout-ms: 300000
      max-executions-per-job: 100

    intent:                      # 의도 분류 (opt-in)
      enabled: false
      confidence-threshold: 0.6
      rule-confidence-threshold: 0.8

    webhook:                     # 웹훅 알림 (opt-in)
      enabled: false
      url: ""
      timeout-ms: 5000
      include-conversation: false

    multimodal:                  # 파일 업로드 / 미디어 URL
      enabled: true
      max-file-size-bytes: 10485760
      max-files-per-request: 5

    tracing:                     # 분산 추적
      enabled: true
      service-name: arc-reactor
      include-user-id: false

    citation:                    # 인용 자동 포맷 (opt-in)
      enabled: false
      format: markdown

    tool-result-cache:           # Tool 결과 캐싱 (기본 활성)
      enabled: true
      ttl-seconds: 60
      max-size: 200

    tool-enrichment:             # Tool 파라미터 보강
      requester-aware-tool-names: []

    memory:                      # 대화 메모리
      summary:
        enabled: false
        trigger-message-count: 20
        recent-message-count: 10
        max-narrative-tokens: 500
      user:
        enabled: false
        inject-into-prompt: false
        max-prompt-injection-chars: 1000
        max-recent-topics: 10

    response:                    # 응답 후처리
      max-length: 0
      filters-enabled: true

    cors:                        # CORS (opt-in)
      enabled: false
      allowed-origins: ["http://localhost:3000"]
      allowed-methods: ["GET","POST","PUT","DELETE","OPTIONS"]
      allowed-headers: ["*"]
      allow-credentials: false
      max-age: 3600

    security-headers:            # 보안 헤더
      enabled: true

    mcp:                         # MCP 런타임 설정
      connection-timeout-ms: 30000
      allow-private-addresses: false
      security:
        allowed-server-names: []   # 환경변수: ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES
        max-tool-output-length: 50000
      reconnection:
        enabled: true
        max-attempts: 5
        initial-delay-ms: 5000
        multiplier: 2.0
        max-delay-ms: 60000

    prompt-lab:                  # Prompt Lab (opt-in)
      enabled: false
      max-concurrent-experiments: 3
      max-queries-per-experiment: 100
      candidate-count: 3
      experiment-timeout-ms: 600000

    api-version:                 # API 버전 계약 (헤더 기반)
      enabled: true
      current: v1
      supported: v1
```

## 설정 그룹별 상세

### AgentProperties (루트)

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-tools-per-request` | Int | 30 | 한 번의 요청에서 사용 가능한 최대 도구 수. Local + MCP 도구 합산 후 `take(n)` 적용 |
| `max-tool-calls` | Int | 10 | ReAct 루프에서 허용하는 최대 도구 호출 횟수. 도달 시 도구 목록을 빈 리스트로 교체하여 강제 종료 |

### LlmProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `default-provider` | String | `gemini` | 기본 LLM 공급자 (예: `gemini`, `openai`, `anthropic`) |
| `temperature` | Double | 0.1 | LLM 생성 온도. 0.0(결정적) ~ 2.0(창의적) |
| `max-output-tokens` | Int | 4096 | LLM 응답 최대 토큰 수 |
| `top-p` | Double | null | Nucleus 샘플링. `null`이면 공급자 기본값 사용 |
| `frequency-penalty` | Double | null | 빈도 페널티. `null`이면 공급자 기본값 사용 |
| `presence-penalty` | Double | null | 존재 페널티. `null`이면 공급자 기본값 사용 |
| `google-search-retrieval-enabled` | Boolean | false | Gemini Google Search 검색 그라운딩 활성화. 의도치 않은 외부 검색을 방지하기 위해 기본 비활성 |
| `max-conversation-turns` | Int | 10 | Memory에서 로드할 최대 대화 턴 수 |
| `max-context-window-tokens` | Int | 128000 | 컨텍스트 윈도우 토큰 예산. `budget = maxContextWindowTokens - systemPromptTokens - maxOutputTokens` |

**주의사항:**
- `temperature`는 `AgentCommand.temperature`로 요청별 오버라이드 가능
- `max-context-window-tokens`는 사용하는 LLM 모델의 실제 컨텍스트 윈도우에 맞춰야 함 (GPT-4: 128K, Claude: 200K, Gemini: 1M)

### PromptCachingProperties

`arc.reactor.llm.prompt-caching` 하위에 중첩됩니다. `anthropic` 공급자에서만 지원됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | Anthropic 프롬프트 캐싱 활성화 (opt-in) |
| `provider` | String | `anthropic` | 캐싱을 적용할 LLM 공급자 |
| `cache-system-prompt` | Boolean | true | 시스템 프롬프트에 캐싱 마크 |
| `cache-tools` | Boolean | true | Tool 정의에 캐싱 마크 |
| `min-cacheable-tokens` | Int | 1024 | 캐싱 마크를 위한 최소 추정 토큰 수 |

**동작 방식:**
- 반복되는 콘텐츠(시스템 프롬프트, Tool 정의)에 `cache_control: {"type": "ephemeral"}`을 표시하여 Anthropic이 캐시된 토큰을 재사용
- 공통 접두사를 공유하는 요청의 프롬프트 토큰 비용을 80-90% 절감 가능
- Anthropic 이외의 공급자 요청에는 영향 없음

### RetryProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-attempts` | Int | 3 | 최대 재시도 횟수 (초기 시도 포함) |
| `initial-delay-ms` | Long | 200 | 첫 재시도 전 대기 시간 (ms) |
| `multiplier` | Double | 2.0 | 지수 백오프 배수. `delay = min(initialDelay * multiplier^attempt, maxDelay)` |
| `max-delay-ms` | Long | 10000 | 최대 대기 시간 (ms). 지수 증가의 상한선 |

**재시도 대상 (일시적 에러):**
- Rate limit (429)
- Timeout
- 5xx 서버 에러
- Connection 에러

**재시도 불가:**
- 인증 에러, Context too long, Invalid request
- `CancellationException` — 절대 재시도 안 함 (구조적 동시성 보장)

### GuardProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | Guard 파이프라인 활성화. `false`면 모든 Guard 단계 비활성화 |
| `rate-limit-per-minute` | Int | 20 | 사용자별 분당 요청 제한 |
| `rate-limit-per-hour` | Int | 200 | 사용자별 시간당 요청 제한 |
| `injection-detection-enabled` | Boolean | true | 프롬프트 인젝션 탐지 활성화 |
| `unicode-normalization-enabled` | Boolean | true | NFKC 정규화, 제로폭 문자 제거, 동형 문자 감지 활성화 |
| `max-zero-width-ratio` | Double | 0.1 | 제로폭 문자 비율 거부 임계값 (0.0-1.0) |
| `classification-enabled` | Boolean | false | 규칙 기반 + 선택적 LLM 입력 분류 활성화 |
| `classification-llm-enabled` | Boolean | false | LLM 기반 분류 활성화 (`classification-enabled` 필요) |
| `canary-token-enabled` | Boolean | false | 시스템 프롬프트 유출 감지를 위한 canary 토큰 활성화 |
| `tool-output-sanitization-enabled` | Boolean | false | Tool 출력 정제 활성화 |
| `audit-enabled` | Boolean | true | Guard 감사 추적 활성화 |
| `topic-drift-enabled` | Boolean | false | 주제 이탈 감지 활성화 (Crescendo 공격 방어) |
| `canary-seed` | String | `arc-reactor-canary` | Canary 토큰 시드 (배포별 고유 토큰을 위해 재정의) |

**동작 방식:**
- `enabled=false`: Guard 빈 자체가 생성되지 않음 (`@ConditionalOnProperty`)
- `injection-detection-enabled=false`: 인젝션 탐지 단계만 비활성화, 나머지 Guard는 동작
- 테넌트별 rate limit은 `tenant-rate-limits` 맵으로 설정 가능

### BoundaryProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `input-min-chars` | Int | 1 | 사용자 입력 최소 길이 (문자 수) |
| `input-max-chars` | Int | 10000 | 사용자 입력 최대 길이 (문자 수) |
| `system-prompt-max-chars` | Int | 50000 | 시스템 프롬프트 최대 길이. `0`이면 무제한 |
| `output-min-chars` | Int | 0 | 최소 출력 길이. `0`이면 비활성 |
| `output-max-chars` | Int | 0 | 최대 출력 길이. `0`이면 비활성 |
| `output-min-violation-mode` | Enum | `WARN` | 출력이 `output-min-chars` 미만일 때의 정책: `WARN`, `RETRY_ONCE`, 또는 `FAIL` |

### ConcurrencyProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-concurrent-requests` | Int | 20 | 동시 에이전트 실행 수 제한. `Semaphore(permits)` 사용 |
| `request-timeout-ms` | Long | 30000 | 요청 전체 타임아웃 (ms). `withTimeout()` 적용 |
| `tool-call-timeout-ms` | Long | 15000 | Tool 호출당 타임아웃 (ms) |

**주의:** 세마포어 대기 시간은 타임아웃에 포함됩니다. 즉, 세마포어 대기 중에도 타임아웃이 발생할 수 있습니다.

### RagProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | RAG 파이프라인 활성화. `true`로 설정해야 RAG 관련 빈이 생성됨 |
| `similarity-threshold` | Double | 0.65 | 벡터 검색 유사도 임계값 (0.0~1.0) |
| `top-k` | Int | 5 | 벡터 검색 결과 수 |
| `rerank-enabled` | Boolean | false | 검색 결과 재순위 활성화 |
| `query-transformer` | String | `passthrough` | 질의 재작성 모드 (`passthrough`, `hyde`, 또는 `decomposition`) |
| `max-context-tokens` | Int | 4000 | RAG 컨텍스트에 할당할 최대 토큰 수 |
| `retrieval-timeout-ms` | Long | 3000 | 검색 타임아웃 (ms). 벡터 DB 무응답 시 스레드 풀 고갈 방지 |

### RagChunkingProperties

`arc.reactor.rag.chunking` 하위에 중첩됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 문서 청킹 활성화 (opt-in) |
| `chunk-size` | Int | 512 | 토큰 기준 목표 청크 크기 (약 4자 = 1토큰) |
| `min-chunk-size-chars` | Int | 350 | 지나치게 작은 청크 방지를 위한 최소 문자 수 |
| `min-chunk-threshold` | Int | 512 | 추정 토큰 수가 이 값 이하인 문서는 분할하지 않음 |
| `overlap` | Int | 50 | 인접 청크 간 컨텍스트 보존을 위한 오버랩 토큰 수 |
| `keep-separator` | Boolean | true | 분할 시 단락/문장 구분자 유지 |
| `max-num-chunks` | Int | 100 | 문서당 최대 청크 수 |

### RagHybridProperties

`arc.reactor.rag.hybrid` 하위에 중첩됩니다. BM25 키워드 점수와 벡터 유사도 점수를 Reciprocal Rank Fusion(RRF)으로 융합합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | BM25 + 벡터 하이브리드 검색 활성화 (`rag.enabled=true` 필요) |
| `bm25-weight` | Double | 0.5 | BM25 순위의 RRF 가중치 (0.0-1.0) |
| `vector-weight` | Double | 0.5 | 벡터 검색 순위의 RRF 가중치 (0.0-1.0) |
| `rrf-k` | Double | 60.0 | RRF 평활 상수 K — 값이 클수록 순위 위치 민감도 감소 |
| `bm25-k1` | Double | 1.5 | BM25 용어 빈도 포화 파라미터 |
| `bm25-b` | Double | 0.75 | BM25 길이 정규화 파라미터 |

### RagParentRetrievalProperties

`arc.reactor.rag.parent-retrieval` 하위에 중첩됩니다. 청크된 검색 결과를 같은 부모 문서의 인접 청크로 확장합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 부모 문서 검색 활성화 (`rag.enabled=true` 필요) |
| `window-size` | Int | 1 | 각 히트 전후에 포함할 인접 청크 수 |

### RagCompressionProperties

`arc.reactor.rag.compression` 하위에 중첩됩니다. RECOMP(Xu et al., 2024) 기반입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 문맥 압축 활성화 (opt-in) |
| `min-content-length` | Int | 200 | 이 문자 수보다 짧은 문서는 압축을 건너뜀 |

### AdaptiveRoutingProperties

`arc.reactor.rag.adaptive-routing` 하위에 중첩됩니다. [Adaptive-RAG(Jeong et al., 2024)](https://arxiv.org/abs/2403.14403) 기반입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | 적응형 쿼리 라우팅 활성화. 단순 쿼리는 RAG를 건너뜀 |
| `timeout-ms` | Long | 3000 | 분류 타임아웃 (ms) |
| `complex-top-k` | Int | 15 | COMPLEX 쿼리에 대한 topK 오버라이드 |

### RagIngestionProperties

`arc.reactor.rag.ingestion` 하위에 중첩됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 수집 후보 캡처 활성화 |
| `require-review` | Boolean | true | 벡터 수집 전 관리자 검토 필요 여부 |
| `allowed-channels` | Set | [] | 자동 캡처 허용 채널. 비어 있으면 모든 채널에서 캡처 |
| `min-query-chars` | Int | 10 | 지식 가치가 있는 것으로 간주되는 최소 쿼리 길이 |
| `min-response-chars` | Int | 20 | 지식 가치가 있는 것으로 간주되는 최소 응답 길이 |
| `blocked-patterns` | Set | [] | 매칭 시 캡처를 차단하는 정규식 패턴 |
| `dynamic.enabled` | Boolean | false | 관리자 API를 통한 DB 기반 정책 오버라이드 활성화 |
| `dynamic.refresh-ms` | Long | 10000 | 동적 정책 캐시 갱신 주기 (ms) |

### ToolSelectionProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `strategy` | String | `semantic` | 선택 전략: `all`, `keyword`, 또는 `semantic` |
| `similarity-threshold` | Double | 0.3 | 시맨틱 선택의 최소 코사인 유사도 임계값 |
| `max-results` | Int | 10 | 시맨틱 선택이 반환하는 최대 Tool 수 |

### ApprovalProperties

부작용이 있는 Tool 호출에 대한 Human-in-the-Loop 승인입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | Human-in-the-Loop 승인 활성화 (opt-in) |
| `timeout-ms` | Long | 300000 | 기본 승인 타임아웃 (5분) |
| `resolved-retention-ms` | Long | 604800000 | 처리 완료된 승인의 정리 전 보존 기간 (7일) |
| `tool-names` | Set | [] | 승인이 필요한 Tool 이름. 비어 있으면 커스텀 `ToolApprovalPolicy` 사용 |

### ToolPolicyProperties

채널별 읽기/쓰기 Tool 접근을 강제합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | Tool 정책 강제 활성화 (opt-in) |
| `write-tool-names` | Set | [] | "쓰기"(부작용 있는) Tool 이름 |
| `deny-write-channels` | Set | `[slack]` | 쓰기 Tool이 거부되는 채널 (fail-closed) |
| `allow-write-tool-names-in-deny-channels` | Set | [] | 거부 채널에서도 허용되는 쓰기 Tool |
| `allow-write-tool-names-by-channel` | Map | {} | 거부 채널에 대한 채널별 허용 목록 |
| `deny-write-message` | String | `Error: This tool is not allowed in this channel` | Tool 호출이 거부될 때 반환되는 오류 메시지 |
| `dynamic.enabled` | Boolean | false | DB 기반 동적 Tool 정책 활성화 (관리자 API 업데이트 + 주기적 갱신) |
| `dynamic.refresh-ms` | Long | 10000 | 동적 정책 캐시 갱신 주기 (ms) |

### AuthProperties

`arc.reactor.auth` 하위에 중첩됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `jwt-secret` | String | `""` | JWT 서명 시크릿. `ARC_REACTOR_AUTH_JWT_SECRET` 환경변수로 설정 필수 |
| `default-tenant-id` | String | `default` | 요청에 미지정 시 기본 테넌트 ID |
| `token-revocation-store` | String | `memory` | 토큰 폐기 저장소 백엔드: `memory`, `jdbc`, 또는 `redis` |
| `token-revocation-redis-key-prefix` | String | `arc:auth:revoked` | 폐기된 토큰의 Redis 키 접두사 (`token-revocation-store=redis`일 때만 사용) |
| `public-actuator-health` | Boolean | true | `/actuator/health`에 대한 비인증 접근 허용. `prod` 프로파일에서는 `false`로 오버라이드됨 |

### ErrorReportProperties

`arc.reactor.error-report` 하위에 중첩됩니다. 에러 분석을 위한 전용 경량 에이전트를 실행합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 에러 리포트 에이전트 활성화 (opt-in) |
| `max-concurrent-requests` | Int | 5 | 최대 동시 에러 리포트 요청 수 |
| `request-timeout-ms` | Long | 10000 | 에러 리포트 에이전트 타임아웃 (ms) |
| `max-tool-calls` | Int | 3 | 에러 리포트 실행당 최대 Tool 호출 수 |

### OutputGuardProperties

실행 후 PII, 정책 위반, 커스텀 정규식 패턴에 대한 응답 검증입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | Output Guard 활성화 |
| `pii-masking-enabled` | Boolean | true | 내장 PII 마스킹 단계 활성화 |
| `dynamic-rules-enabled` | Boolean | true | 동적 런타임 관리 정규식 규칙 활성화 (관리자 관리) |
| `dynamic-rules-refresh-ms` | Long | 3000 | 동적 규칙 캐시 갱신 주기 (ms) |
| `custom-patterns` | List | [] | 차단 또는 마스킹을 위한 커스텀 정규식 패턴. 각 항목에 `name`, `pattern`, `action` (`REJECT` 또는 `MASK`) 포함 |

### SchedulerProperties

REST API로 관리되는 동적 cron 스케줄 MCP Tool 실행입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 동적 스케줄러 활성화 (opt-in) |
| `thread-pool-size` | Int | 5 | 스케줄 작업 실행용 스레드 풀 크기 |
| `default-timezone` | String | 시스템 기본값 | 미지정 시 스케줄 작업의 기본 타임존 |
| `default-execution-timeout-ms` | Long | 300000 | 작업 기본 실행 타임아웃 (ms) |
| `max-executions-per-job` | Int | 100 | 작업당 보존할 최대 실행 이력 수. `0`이면 무제한 |

### IntentProperties

규칙 기반 + 선택적 LLM 의도 분류입니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 의도 분류 활성화 (opt-in) |
| `confidence-threshold` | Double | 0.6 | 의도 프로파일 적용을 위한 최소 신뢰도 |
| `llm-model` | String | null | 분류에 사용할 LLM 공급자. `null`이면 기본 공급자 사용 |
| `rule-confidence-threshold` | Double | 0.8 | LLM 폴백 생략을 위한 최소 규칙 기반 신뢰도 |
| `max-examples-per-intent` | Int | 3 | LLM 프롬프트에서 의도당 최대 few-shot 예시 수 |
| `max-conversation-turns` | Int | 2 | 컨텍스트 인식 분류를 위한 최대 대화 턴 수 |
| `blocked-intents` | Set | [] | 차단할 의도 이름 — 이 의도로 분류된 요청은 거부됨 |

### WebhookConfigProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 웹훅 알림 활성화 (opt-in) |
| `url` | String | `""` | POST 대상 URL |
| `timeout-ms` | Long | 5000 | HTTP 타임아웃 (ms) |
| `include-conversation` | Boolean | false | 페이로드에 전체 대화 포함 여부 |

### MultimodalProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | 멀티모달 지원 활성화 (파일 업로드 및 미디어 URL) |
| `max-file-size-bytes` | Long | 10485760 | 업로드 파일당 최대 허용 크기 (10 MB) |
| `max-files-per-request` | Int | 5 | 멀티파트 요청당 최대 파일 수 |

### TracingProperties

`arc.reactor.tracing` 하위에 중첩됩니다. OpenTelemetry가 클래스패스에 없으면 no-op tracer가 사용됩니다 (오버헤드 없음).

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | span 발행 활성화 |
| `service-name` | String | `arc-reactor` | span에 `service.name`으로 부착되는 서비스 이름 |
| `include-user-id` | Boolean | false | span 속성에 사용자 ID 포함. PII 유출 방지를 위해 기본 비활성 |

### CitationProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 인용 자동 포맷 활성화 (opt-in) |
| `format` | String | `markdown` | 인용 형식. 현재 `markdown`만 지원 |

### ToolResultCacheProperties

동일한 ReAct 루프 내에서 동일한 Tool 호출(같은 Tool 이름 + 같은 인자)을 캐시합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | Tool 결과 캐싱 활성화 |
| `ttl-seconds` | Long | 60 | 캐시 항목 유효 시간 (초) |
| `max-size` | Long | 200 | 최대 캐시 항목 수 |

### ToolEnrichmentProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `requester-aware-tool-names` | Set | [] | LLM이 호출자 정보를 누락할 때 요청 메타데이터에서 호출자 ID를 자동 주입받는 Tool 이름 |

### MemoryProperties

`arc.reactor.memory` 하위에 중첩됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `summary.enabled` | Boolean | false | 계층적 메모리 요약 활성화 (opt-in) |
| `summary.trigger-message-count` | Int | 20 | 요약이 시작되는 최소 메시지 수 |
| `summary.recent-message-count` | Int | 10 | 원문 그대로 유지할 최근 메시지 수 (요약 제외) |
| `summary.llm-model` | String | null | 요약에 사용할 LLM 공급자. `null`이면 기본 공급자 사용 |
| `summary.max-narrative-tokens` | Int | 500 | 서사 요약의 최대 토큰 예산 |
| `user.enabled` | Boolean | false | 사용자별 장기 메모리 활성화 (opt-in) |
| `user.inject-into-prompt` | Boolean | false | 시스템 프롬프트에 사용자 메모리 주입 |
| `user.max-prompt-injection-chars` | Int | 1000 | 주입되는 사용자 메모리 컨텍스트 블록의 최대 문자 수 |
| `user.max-recent-topics` | Int | 10 | 사용자당 보존할 최대 최근 토픽 수 |

### ResponseProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `max-length` | Int | 0 | 응답 최대 문자 수. `0`이면 무제한 |
| `filters-enabled` | Boolean | true | 응답 필터 체인 처리 활성화 |

### CacheProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 응답 캐싱 활성화. 기본 비활성 (opt-in) |
| `max-size` | Long | 1000 | 캐시 항목 최대 수 |
| `ttl-minutes` | Long | 60 | 캐시 항목 TTL (분) |
| `cacheable-temperature` | Double | 0.0 | 요청 온도가 이 값 이하일 때만 캐시. `0.0`이면 결정적 요청만 캐시, `1.0`이면 모든 요청 캐시 |

**동작 방식:**
- 캐시 키: `userPrompt + systemPrompt + model + tools + responseFormat`의 SHA-256 해시
- 응답 필터 적용 전에 캐시됨
- 스트리밍 요청은 캐시되지 않음

#### SemanticCacheProperties

`arc.reactor.cache.semantic` 하위에 중첩됩니다. Redis + 임베딩 의존성이 필요합니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 시맨틱 응답 캐시 활성화 (opt-in) |
| `similarity-threshold` | Double | 0.92 | 시맨틱 캐시 히트를 위한 최소 코사인 유사도 |
| `max-candidates` | Int | 50 | 조회당 평가할 최대 최근 시맨틱 후보 수 |
| `max-entries-per-scope` | Long | 1000 | 스코프 핑거프린트별 최대 시맨틱 캐시 항목 수 |
| `key-prefix` | String | `arc:cache` | 시맨틱 캐시 레코드 및 인덱스의 Redis 키 접두사 |

### CircuitBreakerProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 서킷 브레이커 활성화. 기본 비활성 (opt-in) |
| `failure-threshold` | Int | 5 | 서킷을 열기 전 연속 실패 횟수 |
| `reset-timeout-ms` | Long | 30000 | OPEN에서 HALF_OPEN으로 전이 전 대기 시간 (ms) |
| `half-open-max-calls` | Int | 1 | HALF_OPEN 상태에서 허용되는 시험 호출 수 |

**상태 전이:**
- `CLOSED` (정상) -> N회 연속 실패 -> `OPEN` (모든 호출 거부)
- `OPEN` -> `resetTimeoutMs` 경과 -> `HALF_OPEN` (시험 호출 허용)
- `HALF_OPEN` -> 시험 성공 -> `CLOSED`
- `HALF_OPEN` -> 시험 실패 -> `OPEN`

### FallbackProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | 우아한 성능 저하 활성화. 기본 비활성 (opt-in) |
| `models` | List&lt;String&gt; | [] | 폴백 모델 이름 우선순위 순서 (예: `[openai, anthropic]`) |

**동작 방식:**
- 기본 모델이 재시도 후에도 실패하면 발동
- 모델은 나열된 순서대로 순차 시도
- 해당 프로바이더 빈이 등록되어 있어야 함 (예: `SPRING_AI_OPENAI_API_KEY` 환경 변수)

### CorsProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | CORS 활성화 (opt-in) |
| `allowed-origins` | List | `["http://localhost:3000"]` | 허용되는 출처 |
| `allowed-methods` | List | `["GET","POST","PUT","DELETE","OPTIONS"]` | 허용되는 HTTP 메서드 |
| `allowed-headers` | List | `["*"]` | 허용되는 헤더 |
| `allow-credentials` | Boolean | false | 자격 증명 허용 (쿠키, Authorization 헤더) |
| `max-age` | Long | 3600 | Preflight 캐시 유효 시간 (초) |

### SecurityHeadersProperties

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | 보안 헤더 활성화 |

### McpConfigProperties

MCP 서버는 REST API(`/api/mcp/servers`)를 통해 등록 및 관리됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `connection-timeout-ms` | Long | 30000 | MCP 연결 타임아웃 (ms) |
| `allow-private-addresses` | Boolean | false | 사설/예약 IP 주소 연결 허용. 로컬 개발 시에만 활성화 |
| `security.allowed-server-names` | Set | [] | 허용되는 MCP 서버 이름. 비어 있으면 모두 허용 |
| `security.max-tool-output-length` | Int | 50000 | Tool 출력 최대 문자 수 |
| `security.allowed-stdio-commands` | Set | `[npx, node, python, python3, uvx, uv, docker, deno, bun]` | 허용되는 STDIO 명령어 실행 파일 |
| `reconnection.enabled` | Boolean | true | 실패한 MCP 서버 자동 재연결 활성화 |
| `reconnection.max-attempts` | Int | 5 | 최대 재연결 시도 횟수 |
| `reconnection.initial-delay-ms` | Long | 5000 | 재연결 시도 간 초기 지연 (ms) |
| `reconnection.multiplier` | Double | 2.0 | 후속 시도의 백오프 배수 |
| `reconnection.max-delay-ms` | Long | 60000 | 재연결 시도 간 최대 지연 (ms) |

### PromptLabProperties

`arc.reactor.prompt-lab` 하위에 중첩됩니다.

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | false | Prompt Lab 활성화 (opt-in) |
| `max-concurrent-experiments` | Int | 3 | 최대 동시 실험 수 |
| `max-queries-per-experiment` | Int | 100 | 실험당 최대 테스트 쿼리 수 |
| `max-versions-per-experiment` | Int | 10 | 실험당 최대 프롬프트 버전 수 |
| `max-repetitions` | Int | 5 | 버전-쿼리 쌍당 최대 반복 횟수 |
| `default-judge-model` | String | null | 기본 LLM 심사 모델. `null`이면 실험과 동일한 모델 사용 |
| `default-judge-budget-tokens` | Int | 100000 | LLM 심사 평가를 위한 기본 토큰 예산 |
| `experiment-timeout-ms` | Long | 600000 | 실험 실행 타임아웃 (ms) |
| `candidate-count` | Int | 3 | 자동 생성할 후보 프롬프트 수 |
| `min-negative-feedback` | Int | 5 | 자동 파이프라인 트리거를 위한 최소 부정 피드백 수 |
| `schedule.enabled` | Boolean | false | 스케줄 자동 최적화 활성화 |
| `schedule.cron` | String | `0 0 2 * * *` | Cron 표현식 (기본: 매일 오전 2시) |
| `schedule.template-ids` | List | [] | 대상 템플릿 ID. 비어 있으면 모든 템플릿 |

### ApiVersionContract

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `enabled` | Boolean | true | `X-Arc-Api-Version` 버전 계약 검증 활성화 |
| `current` | String | `v1` | 응답 헤더로 반환되는 현재 API 버전 |
| `supported` | String | `v1` | 지원 버전 목록(콤마 구분, 예: `v1,v2`) |

**동작 방식:**
- 요청 헤더 `X-Arc-Api-Version`는 선택 사항
- 헤더가 없으면 서버는 `current`를 사용
- 헤더가 있고 지원되지 않으면 `400 Bad Request`로 거부
- 모든 응답에 `X-Arc-Api-Version`, `X-Arc-Api-Supported-Versions` 포함

---

## 자동 구성 (Auto-Configuration)

### 빈 생성 순서와 조건

Arc Reactor는 Spring Boot Auto-Configuration으로 모든 핵심 빈을 자동 생성합니다. 모든 빈에 `@ConditionalOnMissingBean`이 적용되어 **사용자 정의 빈이 있으면 자동 생성을 건너뜁니다**.

```
ArcReactorAutoConfiguration
├── 항상 생성
│   ├── toolSelector          → AllToolSelector (모든 도구 선택)
│   ├── errorMessageResolver  → DefaultErrorMessageResolver (영어)
│   ├── agentMetrics          → NoOpAgentMetrics (메트릭 비활성화)
│   ├── tokenEstimator        → DefaultTokenEstimator (CJK 인식)
│   ├── conversationManager   → DefaultConversationManager
│   ├── mcpManager            → DefaultMcpManager
│   ├── hookExecutor          → HookExecutor (빈 Hook 리스트)
│   └── responseFilterChain   → ResponseFilterChain (빈 필터 리스트)
│
├── 조건부 생성
│   ├── jdbcMemoryStore       → @ConditionalOnClass(JdbcTemplate) + @ConditionalOnBean(DataSource)
│   ├── memoryStore           → InMemoryMemoryStore (jdbcMemoryStore 없을 때 폴백)
│   └── agentExecutor         → @ConditionalOnBean(ChatClient) (필수!)
│
├── guard.enabled=true (기본값) → GuardConfiguration
│   ├── rateLimitStage        → DefaultRateLimitStage
│   ├── inputValidationStage  → DefaultInputValidationStage
│   ├── injectionDetectionStage → DefaultInjectionDetectionStage (injection-detection-enabled=true)
│   └── requestGuard          → GuardPipeline(stages)
│
├── cache.enabled=true → CacheConfiguration
│   └── responseCache       → CaffeineResponseCache
│
├── circuit-breaker.enabled=true → CircuitBreakerConfiguration
│   └── circuitBreaker      → DefaultCircuitBreaker
│
├── fallback.enabled=true → FallbackConfiguration
│   └── fallbackStrategy    → ModelFallbackStrategy
│
└── rag.enabled=true → RagConfiguration
    ├── documentRetriever     → SpringAiVectorStoreRetriever (@ConditionalOnBean(VectorStore))
    ├── inMemoryRetriever     → InMemoryDocumentRetriever (VectorStore 없을 때 폴백)
    ├── documentReranker      → SimpleScoreReranker
    └── ragPipeline           → DefaultRagPipeline
```

### 핵심 패턴: @ConditionalOnMissingBean

모든 빈이 이 어노테이션을 사용하므로, 사용자 정의 구현으로 교체가 간단합니다:

```kotlin
// 사용자 정의 빈이 있으면 자동 생성 건너뜀
@Bean
fun toolSelector(): ToolSelector = MyCustomToolSelector()

@Bean
fun errorMessageResolver(): ErrorMessageResolver = KoreanErrorMessageResolver()

@Bean
fun agentMetrics(): AgentMetrics = MicrometerAgentMetrics(registry)
```

### MemoryStore 자동 선택 로직

```
DataSource 빈 있음?
├── YES → JdbcTemplate 클래스 있음?
│         ├── YES → JdbcMemoryStore (PostgreSQL)
│         └── NO  → InMemoryMemoryStore (폴백)
└── NO  → InMemoryMemoryStore (폴백)
```

코드 변경 없이 `build.gradle.kts`에서 JDBC 의존성 추가 + `application.yml`에 DataSource 설정만 하면 자동 전환됩니다.

### AgentExecutor 필수 의존성

`agentExecutor` 빈은 `@ConditionalOnBean(ChatClient::class)`가 적용되어 있습니다. `ChatClient` 빈이 없으면 에이전트가 생성되지 않습니다. Spring AI의 LLM provider 의존성이 필요합니다:

```kotlin
// build.gradle.kts — 하나 이상 활성화 필요
implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
// implementation("org.springframework.ai:spring-ai-starter-model-openai")
// implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
```

### 커스텀 빈 교체 예시

```kotlin
@Configuration
class MyConfig {

    // 한국어 에러 메시지
    @Bean
    fun errorMessageResolver() = ErrorMessageResolver { code, _ ->
        when (code) {
            AgentErrorCode.RATE_LIMITED -> "요청 한도 초과. 잠시 후 다시 시도하세요."
            AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
            else -> code.defaultMessage
        }
    }

    // Micrometer 메트릭
    @Bean
    fun agentMetrics(registry: MeterRegistry) = MicrometerAgentMetrics(registry)

    // 커스텀 도구 선택기
    @Bean
    fun toolSelector() = CategoryBasedToolSelector(
        categories = listOf(ToolCategory.SEARCH, ToolCategory.CALCULATION)
    )
}
```

---

## 프로덕션 프로파일 오버라이드 (`application-prod.yml`)

`SPRING_PROFILES_ACTIVE=prod`를 설정하면 다음 오버라이드가 적용됩니다:

```yaml
spring:
  codec:
    max-in-memory-size: 1MB                        # 인메모리 버퍼 크기 제한

arc:
  reactor:
    concurrency:
      max-concurrent-requests: 50                  # 기본값 20에서 증가
    security-headers:
      enabled: true
    auth:
      public-actuator-health: false                # 공개 헬스 엔드포인트 접근 비활성화
```

---

## 프로덕션 설정 예시

### 고트래픽 환경

```yaml
arc:
  reactor:
    max-tool-calls: 5            # 루프 제한 강화
    concurrency:
      max-concurrent-requests: 50   # 동시 요청 증가
      request-timeout-ms: 60000     # 타임아웃 여유
    retry:
      max-attempts: 5              # 재시도 여유
      max-delay-ms: 30000
    guard:
      rate-limit-per-minute: 30    # Rate limit 완화
      rate-limit-per-hour: 500
```

### 비용 최적화

```yaml
arc:
  reactor:
    max-tool-calls: 3            # 도구 호출 최소화
    llm:
      temperature: 0.1           # 결정적 응답
      max-output-tokens: 2048    # 출력 토큰 절약
      max-context-window-tokens: 32000  # 컨텍스트 축소
      prompt-caching:
        enabled: true            # Anthropic 프롬프트 토큰 절약
    rag:
      max-context-tokens: 2000   # RAG 토큰 절약
      top-k: 3                   # 검색 결과 축소
```

### 보안 강화

```yaml
arc:
  reactor:
    guard:
      enabled: true
      rate-limit-per-minute: 5
      rate-limit-per-hour: 50
      injection-detection-enabled: true
      canary-token-enabled: true
      topic-drift-enabled: true
    boundaries:
      input-max-chars: 3000
      system-prompt-max-chars: 20000
    concurrency:
      request-timeout-ms: 15000  # 짧은 타임아웃
    output-guard:
      enabled: true
      pii-masking-enabled: true
```

# Arc Reactor Agent 작업 루프 (Directive 기반)

> **이 루프는 `docs/agent-work-directive.md`를 기준으로 실제 코드 개선을 반복한다.**
> 측정/재시작/인프라 점검이 아닌, 작업 지시서의 5대 우선 패턴을 하나씩 착실하게 구현하는 것이 목적이다.
> **Claude에서 이 루프를 실행할 때는 기본 작업 모델로 Sonnet을 사용한다.**
> Opus로 바꾸는 것은 예외이며, 광범위한 설계 재편이나 고위험 조사처럼 Sonnet으로 충분하지 않을 때만 허용한다.
> 이 루프는 단순 QA용이 아니라 **제품 품질 개선 + 루프 자체의 자가 개발**을 함께 다룬다.

## 0) 이 루프가 지향하는 것

1. **코드 개선 우선, 측정 보조** — 외부 API 쿼터, 서버 재시작, 프로브 등 인프라 점검은 원칙적으로 건너뛴다.
2. **한 번에 하나의 Directive 패턴** — 여러 패턴을 동시에 손대지 않는다.
3. **측정 없는 개선 금지** — 코드 변경 시 단위 테스트로 효과를 검증한다. 평가셋이 없으면 만든다.
4. **opt-in 기본** — 새 기능은 기본 off, 기존 경로를 깨지 않는다.
5. **보고서는 계층적으로 유지** — 메인 상태판은 `docs/production-readiness-report.md`, 상세 작업 로그는 `docs/reports/rounds/R{N}.md`에 기록한다.
6. **자가 개발 허용** — eval, backlog, gate, evidence, watchdog 자체를 개선하는 `self_development` Round를 허용한다. 단 product axis와 연결돼야 한다.

### ⚠️ 0.1) 최상위 제약 — atlassian-mcp-server 호환성 유지

이 작업 루프의 목적은 **Arc Reactor 에이전트 자체의 성능 향상**이다.
**기존 `atlassian-mcp-server`(Jira/Confluence/Bitbucket)와의 연동은 절대 깨지면 안 된다.**

- MCP 프로토콜 경로(SSE transport, tool discovery, tool call, response parsing)를 변경하지 않는다.
- `ToolResponsePayloadNormalizer`, `ArcToolCallbackAdapter`, `McpToolRegistry` 등 MCP 연결 계층 수정 시
  atlassian-mcp-server 응답 스키마(Jira issue, Confluence page, Bitbucket PR)가 그대로 흘러가는지 확인한다.
- 도구 이름, description, 파라미터 스키마는 atlassian-mcp-server가 제공하는 대로 보존한다.
- 응답 요약/정규화 계층(#97 ACI)을 추가할 때도 **원본 필드는 보존**하고 요약은 별도 필드로 추가한다.
- 테스트에 `atlassian-mcp-server` 응답 픽스처가 있다면 해당 테스트가 통과해야 한다.
- MCP write 도구(생성/수정/삭제)는 `ToolIdempotencyGuard` 경로를 유지한다.

**호환성 검증 체크리스트** (MCP 접점을 건드리는 모든 Round에서):
```
[ ] ArcToolCallbackAdapter 인터페이스 계약 불변
[ ] McpToolRegistry 동작 불변 (도구 발견/등록)
[ ] ToolResponsePayloadNormalizer 기본 경로 불변
[ ] 한글 → Jira/Confluence 검색 쿼리 매핑 동작 불변
[ ] 도구 description 문구 불변 (LLM 선택 정확도 유지)
[ ] MCP 연결/재연결 로직 불변
[ ] 실제 SSE URL 등록 플로우 영향 없음
```

MCP 접점을 직접 건드리지 않는 작업(예: Prompt Layer 내부 refactor)은 이 체크리스트를 생략해도 된다.
MCP 접점을 건드린다면 보고서에 "MCP 호환성: 유지 확인" 항목을 명시한다.

### ⚠️ 0.2) 최상위 제약 — Redis 의미적 캐시 보존

Arc Reactor는 `RedisSemanticResponseCache`로 LLM 응답을 의미적 캐싱한다. 이 캐시는 비용/응답시간/토큰 절감의 핵심 축이다.

**캐시 키 구성** (`CacheKeyBuilder.buildScopeFingerprint`):
```
SHA-256(systemPrompt | toolNames | model | mode | responseFormat |
        responseSchema | userId | sessionId | tenantId | identity)
```

즉, **`systemPrompt` 텍스트가 1바이트라도 바뀌면 scopeFingerprint가 달라지고
기존 캐시 엔트리가 전부 stale**이 된다. 공백 1개, 섹션 헤더 1줄 추가만으로도 대량 miss 발생.

**Prompt Layer 리팩토링(#94) 규칙 — 매우 엄격**:
1. **byte-identical 출력 원칙** — 내부 구조만 계층화하고 최종 출력 텍스트는 리팩토링 전과 완전히 동일해야 한다
2. **Golden snapshot 테스트 필수** — 리팩토링 전 현재 출력을 스냅샷으로 저장한 뒤, 리팩토링 후에도 동일하게 나오는지 byte-diff로 검증
3. 불가피하게 텍스트가 바뀌면 "**cache flush 이벤트**"로 보고서에 명시하고 사용자 승인을 받는다 (이 루프에서 자동 승인 금지)
4. 동일 원칙이 `appendWorkspaceGroundingRules`, `appendLanguageRule`, `appendFewShot` 등 모든 append 메서드에 적용

**ACI 요약 계층(#97) 규칙**:
- 요약은 **tool 응답 payload**에 붙는다 → `systemPrompt`에는 영향 없음 → 캐시 키 불변
- 단, 요약된 tool 응답이 LLM에 전달되면 LLM 최종 응답 텍스트가 달라질 수 있음 → `CachedResponse.content`가 다른 것이 저장됨 → 동일 키 재방문 시 새 결과 학습
- 요약은 opt-in flag로 제어하여 기존 사용자는 캐시 재활용 유지

**Tool Approval UX(#95) 규칙**:
- 승인 요청 응답 구조가 바뀌면 프롬프트/응답 경로는 불변인지 확인
- ToolApprovalPolicy가 시스템 프롬프트를 생성하지 않으면 캐시 영향 없음

**Evaluation 메트릭(#96) 규칙**:
- 메트릭은 관측 계층 → 캐시 키/값 불변
- 단, 메트릭 수집이 `ChatClient.call()` 경로 전후에 들어가면 성능 영향 확인

**체크리스트** (캐시 접점을 건드리는 Round에서):
```
[ ] SystemPromptBuilder 출력 텍스트 byte-identical (golden snapshot)
[ ] CacheKeyBuilder.buildScopeFingerprint 출력 해시 불변
[ ] RedisSemanticResponseCache.getSemantic/putSemantic 경로 불변
[ ] ResponseCache 인터페이스 계약 불변
[ ] 캐시 TTL/크기/eviction 정책 불변
[ ] 캐시 관련 기존 테스트 전부 PASS (특히 RedisSemanticResponseCacheExtendedTest)
```

**매 round 측정 의무 (R347 추가)**:

이 캐시는 `MicrometerCacheMetricsRecorder`가 `arc.cache.hits{type=exact|semantic}`, `arc.cache.misses`, `arc.cache.cost.saved.estimate` 카운터를 증가시킨다. **매 round는 이 카운터를 측정해 보고서에 기록해야 한다.** 측정 없이 커밋하는 round는 캐시가 silent하게 비활성화되거나 fallback된 걸 놓칠 수 있다 (R265 매트릭스의 CaffeineResponseCache silent fallback 포함).

**측정 방법** (3가지 중 하나, 가장 편한 것):

1. **Admin API (권장)**: `curl -sH "Authorization: Bearer $ADMIN_JWT" http://localhost:18081/api/admin/platform/cache/stats`
   - R347 fix 이후 Micrometer 카운터를 읽는다 (이전에는 dead `PipelineHealthMonitor.cacheExactHitsTotal` 필드).
   - JSON에서 `totalExactHits`, `totalSemanticHits`, `totalMisses`, `hitRate` 추출.
2. **Prometheus 직접**: `curl -s http://localhost:18081/actuator/prometheus | grep '^arc_cache'`
3. **Admin UI**: arc-reactor-admin의 `/rag-cache` 페이지 — 동일 endpoint를 호출해 hit rate, exact/semantic/miss counter, 설정 표시.
4. **DoctorReport**: start-stack이 `--arc.reactor.diagnostics.startup-log.enabled=true`로 띄우므로 **startup 시 Response Cache 섹션이 자동 로그에 출력**. `grep "Response Cache" /tmp/arc-reactor-backend.log`.

**round 보고서 필수 라인** (`docs/reports/rounds/R{N}.md`):

```
cache_health: tier=redis-semantic hits=1234 misses=89 ratio=93.3% cost_saved=$2.41
```

- `tier`: `redis-semantic | caffeine | noop | none` (DoctorReport R238 cache tier 분류)
- `hits`: exact + semantic 합
- `misses`: miss 합
- `ratio`: `hits / (hits + misses)` 퍼센트 (소수점 첫째 자리)
- `cost_saved`: `arc.cache.cost.saved.estimate` counter 전체 합 (USD)

**회귀 감지 (자동 stop 조건)**:

- 직전 round 대비 `ratio`가 **20pp 이상 떨어지면** round stop + 원인 조사 (캐시 flush 이벤트 의심, §0.2 byte-identical 위반 또는 systemPrompt 1바이트 변경 가능성).
- `tier`가 `redis-semantic`에서 `caffeine`으로 바뀌면 round stop + Redis 연결 상태 조사 (R238 Doctor 경고 로그 참조).
- `hits = 0`이 3 round 연속이면 round stop + 캐시 path 브로큰 의심 (`AgentExecutionCoordinator.kt:396,425,433`의 `cacheMetricsRecorder?.recordMiss/recordExactHit/recordSemanticHit` 호출 경로 검증).

### ⚠️ 0.3) 최상위 제약 — 대화/스레드 컨텍스트 관리 보존

Arc Reactor의 가장 중요한 품질 축 중 하나는 **스레드/세션별 대화 컨텍스트 유지**다. Slack 스레드는 물론 REST/SSE 경로에서도 동일하게 중요하다.

**핵심 구성 요소**:

| 컴포넌트 | 파일 | 역할 |
|----------|------|------|
| sessionId 매핑 | `arc-slack/.../DefaultSlackEventHandler.kt` | `"slack-{channelId}-{threadTs}"` 형식으로 스레드별 키 생성 |
| MemoryStore 인터페이스 | `arc-core/.../memory/ConversationMemory.kt` | 히스토리 저장/조회 추상화 |
| InMemoryMemoryStore | 동일 파일 | Caffeine 기반, maxSessions=1000, LRU |
| JdbcMemoryStore | `arc-core/.../memory/JdbcMemoryStore.kt` | Postgres/H2, maxMessagesPerSession=100, TTL cleanup |
| ConversationManager | `arc-core/.../memory/ConversationManager.kt` | 계층적 메모리(facts/narrative/recent) + 세션 소유권 검증 |
| ConversationMessageTrimmer | `arc-core/.../agent/impl/ConversationMessageTrimmer.kt` | 3단계 트리밍, 마지막 UserMessage 보호 |
| CacheKeyBuilder sessionId 필드 | `arc-core/.../cache/CacheKeyBuilder.kt:55` | scopeFingerprint에 포함 → 스레드별 캐시 격리 |
| SlackThreadTracker | `arc-slack/.../slack/session/SlackThreadTracker.kt` | 봇 개시 스레드만 추적 |

**절대 건드리지 말 것 (파괴적 변경 금지)**:

1. **sessionId 포맷 변경 금지** — `"slack-{channelId}-{threadTs}"` 형식을 바꾸면 기존 대화 모두 단절
2. **MemoryStore 인터페이스 변경 금지** — `save/load/clear/ownership` 메서드 시그니처 유지
3. **JDBC 스키마 변경은 Flyway migration만** — `conversation_messages` 테이블 구조 변경 시 `V{N}__*.sql` 추가, 기존 컬럼 DROP 금지
4. **ConversationMessageTrimmer 가드 규칙 유지**:
   - Phase 2 가드는 `>` (not `>=`) — off-by-one으로 UserMessage 손실
   - `AssistantMessage(toolCalls) + ToolResponseMessage` **쌍으로** 추가/제거
   - 마지막 UserMessage(현재 프롬프트) 절대 제거 금지
   - 선행 SystemMessage(facts/narrative) 보호
5. **계층적 메모리(facts/narrative/recent) 구조 보존** — `ConversationManager` 내부 리팩토링은 가능하나 프롬프트 주입 시점/순서/분류는 불변
6. **세션 소유권 검증 로직 유지** — `ConversationManager` 가 userId 기반 ACL을 수행. Slack 스레드(`slack-` prefix)는 여러 사용자 참여를 허용하는 예외
7. **SlackThreadTracker 등록 경로 유지** — 봇이 개시하지 않은 스레드는 무시하는 기본 동작 보존

**Directive 작업별 컨텍스트 영향도**:

| 작업 | 컨텍스트 영향 | 대응 |
|------|---------------|------|
| #94 Prompt Layer 계층화 | **있음** — SystemPromptBuilder 출력이 `ConversationManager`가 주입하는 히스토리 섹션과 충돌하지 않아야 함 | byte-identical + 계층적 메모리 주입 위치 불변 |
| #95 Tool Approval UX | 없음 (승인 UX는 컨텍스트와 독립) | — |
| #96 Evaluation 메트릭 | 없음 (관측만) | 필요 시 스레드별 지표 분리 고려 (bonus) |
| #97 ACI 요약 | **있음** — 요약된 tool 응답이 히스토리에 저장되면 다음 턴 컨텍스트가 달라짐 | 원본은 메모리에 저장, LLM에만 요약 전달 고려. opt-in 필수 |

**체크리스트** (컨텍스트/스레드 경로를 건드리는 Round에서):
```
[ ] sessionId 포맷 "slack-{channelId}-{threadTs}" 불변
[ ] MemoryStore 인터페이스 계약 불변 (InMemory/Jdbc 양쪽)
[ ] conversation_messages 테이블 스키마 불변 (또는 Flyway migration 추가)
[ ] ConversationMessageTrimmer 3단계 트리밍 규칙 유지 (Phase 2 `>`)
[ ] AssistantMessage + ToolResponseMessage 쌍 무결성 유지
[ ] 마지막 UserMessage 보호 유지
[ ] 계층적 메모리 facts/narrative/recent 주입 순서 불변
[ ] 세션 소유권 검증(ConversationManager userId 기반 ACL) 유지
[ ] 스레드별 scopeFingerprint 격리 유지 (CacheKeyBuilder sessionId 필드)
[ ] ConversationManagerSessionOwnershipTest, ConversationMessageTrimmerTest 전부 PASS
[ ] DefaultSlackEventHandlerTest 전부 PASS (존재 시)
```

### ⚠️ 0.4) 외부 참조 레포 — MIT 라이선스 아이디어 참조만 허용

로컬에 clone되어 있는 외부 레포가 있다면 **아이디어 차원에서 참조 가능**하다. 단 §8 금지 사항 1번("외부 OSS 코드/프롬프트 그대로 가져오기")은 라이선스와 무관하게 절대 규칙이다.
**고정 절대경로를 가정하지 말고, 실제 경로가 존재할 때만 참조한다. 경로가 없으면 외부 참조 단계를 생략한다.**

| 레포 | 경로 | 라이선스 | 정체 |
|---|---|---|---|
| claw-code | `{environment-specific local clone path}` | MIT (ultraworkers) | Rust로 재구현한 멀티 에이전트 CLI. `clawhip` 코디네이션 루프 + Discord 인터페이스. 주요 crate: `runtime`, `api`, `commands`, `plugins`, `tools`, `telemetry` |
| openclaw | `{environment-specific local clone path}` | MIT (Peter Steinberger 2025) | Personal AI Assistant. macOS/iOS/Android native 앱 + shared core |

**허용 패턴 (✅)**:
- README / CLAUDE.md / PHILOSOPHY / ROADMAP / PARITY / docs 문서 읽기 → 아키텍처 개념 파악
- 특정 기법의 "무엇/왜"만 확인 → 자체 설계로 **재구현**
- crate/module 이름·레이아웃 관찰 → Arc Reactor에 유사 개념 신설 (이름은 자체 네이밍)
- PR 노트에 영감 출처 기록 ("영감: claw-code의 telemetry crate 구조") — audit trail 확보

**금지 패턴 (❌)**:
- 함수, 타입, 주석, 로그 메시지, 상수, 시스템 프롬프트 **복사 붙여넣기** (법적으로는 MIT가 attribution으로 허용하지만 프로젝트 정책상 금지)
- 두 창 띄우고 나란히 보면서 코딩 — substantial similarity 회피
- 자동화 scanner / codebase-scanner 에이전트가 이 경로를 탐색 대상에 포함
- retry 상수, backoff 공식, 에러 분류 휴리스틱 등 "특정 표현으로 가치가 만들어지는 영역"의 직접 차용

**참조가 유용할 영역 (좋은것만)**:
| 후보 | 해당 레포 | 매칭도 |
|---|---|---|
| Directive #94 Prompt Layer 계층화 / 시스템 프롬프트 구조 | 두 레포 모두 | 높음 — 두 프로젝트 모두 directive·prompt 체계 고민 |
| 멀티 에이전트 코디네이션 (향후 Phase 2 확장 시) | claw-code `clawhip` | 높음 — 핵심 아키텍처 철학 |
| 관찰성 / telemetry 네이밍 / 메트릭 레이블 관습 | claw-code `telemetry` crate | 중간 — Grafana 패널과 매칭 시 참고 |
| Tool registry / plugin 구조 | claw-code `plugins`, `tools` | 중간 — MCP tool adapter 설계 참고 |
| 에이전트 상태 머신 / state persistence | claw-code `runtime` | 중간 — ReAct 루프 상태 관리 참고 |

**참조하지 말아야 할 영역**:
- Auth / security (Spring Boot / WebFlux 관용 패턴이 우선, 기술 스택 완전 다름)
- JDBC / persistence (claw-code는 Rust, openclaw는 mobile storage)
- Redis semantic cache (Arc Reactor 자체 설계, byte-identical 제약 때문에 외부 영향 금지)
- ConversationManager / MemoryStore (§0.3 컨텍스트 관리 제약 하에서만 수정 가능)

**현재 cycle (R322~R325) 매칭도**: 낮음. auth/resilience/classification 방어는 Spring Boot/Kotlin 관용 패턴으로 충분히 해결 중. 도메인·스택 차이로 외부 참조 실익 제한적.

**클린룸 워크플로** (참조할 때):
1. 라운드 시작 시 목표 확인 → 외부 레포 해당 영역과 겹치는지 판단
2. 겹친다면 문서(README/docs)만 먼저 — 아키텍처 다이어그램 수준 이해
3. 필요 시 특정 모듈 구조까지만 관찰 (함수 시그니처 수준, 본문 상세는 스킵)
4. 읽은 후 **시간 간격**을 두고 자기 언어로 재구현 — 화면 닫고 재시작
5. PR 노트에 영감 출처 기록
6. 법적 요구는 아니지만 감사 추적 유지

**현 라운드에서 참조 여부 명시**: 만약 특정 라운드에서 아이디어 참조를 사용했다면, 해당 Round 보고서에 "외부 참조: {레포}/{경로} — {아이디어 요약}" 항목을 명시한다. 미사용 시 해당 항목 생략.

### 0.5) Product North Star — Enterprise Internal Assistant Benchmark

이 루프의 제품 north star는 **Glean / Moveworks / Atlassian Rovo / Microsoft Copilot Studio**처럼
직원이 실제 업무에서 매일 쓰는 enterprise internal assistant가 잘하는 핵심 능력 축을
Arc Reactor 방식으로 하나씩 줄여 가는 것이다.

단, 이 루프는 **벤더 UI/카피/표현을 따라 하는 작업이 아니라 capability delta를 줄이는 작업**만 허용한다.
벤더 이름은 north star 설명에만 쓰고, 실제 Round에서는 아래 **axis 이름만 사용**한다.

**고정 capability axis (6개)**:

| Axis | 의미 | 대표 지표 |
|---|---|---|
| `connector_permissions` | 커넥터 연결 안정성, source coverage, ACL/allowlist/권한 정합성 | tool availability, permission-denied rate, missing source rate |
| `grounded_retrieval` | 검색/인용/출처 검증 기반 답변 품질 | grounded coverage, retrieval success/empty/timeout/error, citation presence |
| `cross_source_synthesis` | Jira/Confluence/Bitbucket/Swagger 등 2개 이상 source를 합친 답변 품질 | multi-tool success rate, tool family correctness, merged answer quality |
| `safe_action_workflows` | preview/approval/write-policy/rollback-aware action 흐름 | preview success, approval accuracy, blocked write regression |
| `admin_productization` | 운영자가 반복 실패를 빠르게 고치는 control plane 가치 | top missing query closure, blocked cluster explainability, lane health |
| `employee_value` | 실제 직원 가치와 제품 방향성 | observed/grounded/blocked/interactive/scheduled, answerMode, toolFamily |

**Hard Gate 규칙**:
1. 모든 Round는 반드시 **주요 benchmark axis 1개**를 먼저 선택한다.
2. 모든 Round는 반드시 **측정 가능한 개선 가설 1개**를 적는다.
3. 선택한 axis와 연결되지 않는 작업은 원칙적으로 하지 않는다.
4. 예외적으로 foundation 작업을 할 수는 있지만, 이 경우에도 "**어떤 axis를 unblock하는지**"를 보고서에 적는다.
5. 사용자 가치와 연결되지 않는 리팩토링은 단독 목표로 선택하지 않는다.
6. 날짜/시장점유율/벤더 세부 기능처럼 빨리 낡는 비교 서술은 이 파일에 추가하지 않는다.

**축별 기본 참고 문서**:
- `connector_permissions` / `cross_source_synthesis`:
  `docs/qa-agent-quality-guide.md`, `docs/production-readiness-report.md`
- `grounded_retrieval`:
  `docs/qa-agent-quality-guide.md`, `docs/en/reference/metrics.md`
- `admin_productization` / `employee_value`:
  `docs/ko/operations/employee-value-rollout-300.md`
- `safe_action_workflows`:
  `docs/en/governance/write-tool-policy.md`, `docs/en/governance/human-in-the-loop.md`

### 0.6) 새벽 자동 반복용 Safety Rails

이 루프를 새벽에 반복 실행할 때는 "계속 바쁘게 일하는 것"보다
**작은 direct value를 안전하게 누적하는 것**이 더 중요하다.

아래 10개 safety rail은 watchdog/cron 형태의 반복 실행에서 항상 우선한다.

1. **작은 변경 우선**
   - 한 Round에서 한 subsystem 또는 한 user-visible gap만 건드린다.
   - 파일 수가 과도하게 늘어나면 범위를 줄인다.
2. **direct_value 우선**
   - 새벽 자동 실행은 `foundation`보다 `direct_value`를 우선한다.
   - `foundation` 작업은 blocker가 분명할 때만 허용한다.
3. **foundation 연속 제한**
   - `foundation` Round를 2회 연속 넘기지 않는다.
   - 2회 연속 foundation이면 다음 Round는 반드시 direct_value를 목표로 잡는다.
4. **정체 축 자동 회피**
   - 같은 axis를 3개 Round 연속 잡았는데 measurable delta가 없으면,
     다음 Round는 다른 axis로 전환한다.
5. **회귀 시 즉시 중단**
   - compile/test/regression이 깨지면 해당 Round는 확장하지 않는다.
   - 원인 축소 또는 되돌림 후보 정리까지만 하고, 큰 추가 작업은 금지한다.
6. **증거 없는 push 금지**
   - baseline과 after evidence가 없으면 commit/push하지 않는다.
   - "설명상 더 좋아 보인다"는 이유만으로 완료 처리하지 않는다.
7. **대형 리팩토링 금지**
   - 새벽 자동 실행에서는 아키텍처 재편, 대량 rename, 광범위 prompt rewrite를 하지 않는다.
   - 이런 작업은 명시적 수동 라운드에서만 한다.
8. **고위험 변경 제한**
   - migration, auth, cache key, session ownership, MCP 계약 변경은
     direct axis 연결과 회귀 테스트가 있을 때만 허용한다.
   - 그렇지 않으면 다음 수동 라운드 후보로 넘긴다.
9. **외부 의존 실패를 제품 문제와 분리**
   - 새벽 라운드에서 외부 API/MCP/권한 문제를 발견하면
     제품 결함, 환경 결함, 데이터 결함을 구분해서 기록한다.
   - 환경 결함만으로 내부 코드를 과도하게 바꾸지 않는다.
10. **최근 5개 Round 문맥 확인**
   - 새 Round 시작 전 최근 5개 Round에서
     `axis`, `delta`, `remaining gap`, `foundation/direct_value`를 먼저 읽는다.
   - 이미 실패한 방향을 반복하지 않는다.

### 0.7) 상용급 개선 루프의 고정 입력 3종

이 루프가 상용급 개선 루프로 동작하려면 아래 3개가 항상 같이 있어야 한다.

1. **고정 eval set**
   - `docs/ko/testing/atlassian-enterprise-agent-eval-set.md`
   - Jira / Confluence / Bitbucket / cross-source / safe action의 고정 케이스
2. **runtime backlog**
   - `docs/ko/operations/assistant-runtime-backlog.md`
   - top missing query, blocked false positive, wrong tool family, synthesis gap
3. **quality gates**
   - `docs/ko/operations/assistant-quality-gates.md`
   - round_gate / overnight_gate / release_gate 기준

이 3개가 없으면 이 루프는 "열심히 일하는 루프"는 될 수 있어도 "상용급으로 수렴하는 루프"는 될 수 없다.

현재 기본 제품 축은 **Atlassian assistant**이므로, 기본 우선순위는 Jira → Confluence → Bitbucket → cross-source synthesis 순이다.

### 0.8) Self-Development Round 규칙

이 루프는 두 종류의 Round를 가진다.

- `product_improvement`
  - 사용자 응답 품질, grounded retrieval, synthesis, action safety를 직접 개선
- `self_development`
  - eval set, backlog, gate, report schema, watchdog recovery처럼
    **개선 루프 자체의 품질**을 높이는 작업

기본값은 `product_improvement`다.

`self_development`는 아래를 모두 만족할 때만 허용한다.

1. `docs/ko/operations/assistant-self-development-backlog.md`의 open 항목 1개와 직접 연결
2. 어떤 product axis를 unblock하는지 1개 이상 명시
3. loop health metric 하나 이상 개선
4. touched product metric baseline을 악화시키지 않음

최근 5개 Round에서 `self_development`가 2개를 넘으면 다음 Round는 기본적으로 `product_improvement`를 우선한다.

### 0.9) Live MCP Evidence 사용 규칙

실제 Atlassian MCP가 연결되어 있다면, 이 루프는 **실제 응답을 작업 순간에만 활용**할 수 있다.
하지만 실제 데이터는 저장소에 남기지 않는다.
이 모드는 **ephemeral single-use mode**로 취급한다. 즉, 한 Round 안에서만 쓰고 끝나면 버린다.

**허용되는 방식**:

- 작업 중 실제 Jira / Confluence / Bitbucket 응답을 읽고 판단 근거로 사용
- 로컬 ignored 경로에만 임시 저장
  - `.qa-runtime/`
  - `.qa-live/`
- pass/fail, latency, tool family, empty/timeout, permission_denied, citation_presence 같은
  **익명화된 메타데이터**만 추출
- 메타데이터 추출이 끝나면 raw 데이터는 즉시 삭제
- 같은 raw 데이터를 다음 Round의 입력으로 재사용하지 않음

**절대 금지**:

- raw MCP payload를 tracked 파일에 기록
- 실제 이슈 제목, 문서 제목, PR 제목, 본문, 댓글, 설명, URL, key, accountId, email을 커밋
- 예시 프롬프트라고 해도 실제 사내 데이터 조각을 문서에 남김
- raw 응답을 요약 데이터셋, few-shot 예시, 테스트 fixture, 샘플 prompt로 승격
- 이전 Round의 raw 임시 파일을 다음 Round까지 보관
- `git add -A`처럼 ignored local evidence까지 같이 스테이징될 수 있는 방식 사용

**tracked 문서에 남겨도 되는 것**:

- eval case id (`ATL-JIRA-001`)
- backlog id (`ATL-BG-001`)
- tool family (`jira`, `confluence`, `bitbucket`)
- 결과 (`PASS`, `WARN`, `FAIL`)
- 에러 유형 (`empty_result`, `timeout`, `permission_denied`, `wrong_tool_family`)
- 수치 메타데이터 (응답 시간, 호출 수, 출처 존재 여부)
- 익명화된 요약 (`개인화 Jira 조회에서 empty_result 발생`)

**tracked 문서에 남기면 안 되는 것**:

- 실제 이슈 키 / 문서 제목 / PR 번호 / URL
- 실제 사용자 식별자 / 이메일 / accountId
- 실제 응답 문장이나 본문 인용
- raw tool arguments / raw tool outputs

**커밋 전 필수 확인**:

1. staged 파일에 `.qa-runtime/` 또는 `.qa-live/`가 없어야 한다
2. Round 보고서에는 raw data 대신 익명화된 메타데이터만 있어야 한다
3. evidence_paths는 tracked 문서 경로 또는 테스트/코드 경로만 가리켜야 한다
4. 실제 데이터가 필요한 근거는 "작업 중 확인"으로만 남기고, 내용 자체는 남기지 않는다
5. `.qa-runtime/`와 `.qa-live/` 안의 raw 데이터는 Round 종료 전 삭제한다
6. raw 데이터 기반의 영구 예시, fixture, prompt, 문서 조각을 만들지 않는다

### 0.10) 9-Point 균형 로드맵 (전 축 9점 달성 목표)

이 루프의 궁극 목표는 **모든 capability axis와 코드/productization 점수를 9점 이상으로 끌어올리는 것**이다.
2026-04-12 사용자 주도 평가 세션에서 도출된 6단계 phase 로드맵을 따라간다.
각 라운드는 어느 phase에 기여하는지 보고서에 명시하고, 완료된 deliverable은 즉시 이 §0.10 체크리스트를 업데이트한다.

**현재 baseline (2026-04-12 OSS 기준 평가)**:

| 관점 | 점수 |
|---|---|
| OSS framework로서 (코어) | 8.5 |
| 단일 회사 내부 봇 배포 | 8.5 |
| vs Onyx(Danswer) / Dify / Khoj OSS | 7 |
| Spring/Kotlin 진영 USP | 9.5 (사실상 유일) |
| 코드 품질 / 엔지니어링 규율 | 8.5 |
| Productization (admin UI 포함) | 7.5 |
| 한국 시장 특화도 | 8.5 |

**약점 핵심 2가지 — 이 두 가지만 고치면 평균 +1점 이상**:
- (a) **OSS 첫인상 자산 부재** — README, 5분 docker demo, 영상, GitHub badges, landing
- (b) **Citation grounding 미구현** — RAG 답변에 inline 출처 + admin에서 클릭 가능한 source

#### Phase 정의

| Phase | 목표 | 주 기여 axis | Status | 예상 Score Lift |
|---|---|---|---|---|
| **P0** | 코드 위생 — mega-file 분할, ktlint, internal visibility, version catalog (병행 트랙, ROI 순위 외) | 코드 품질, OSS trust | `TODO` | 코드 등급 B− → A− |
| **P1** | 첫인상 자산 (README, 5분 docker demo, 영상, badges, landing) | `admin_productization`, `employee_value` | `TODO` | productization 7.5 → 8.5 |
| **P2** | Citation grounding (RAG 응답 inline 출처 + admin UI) | `grounded_retrieval`, `cross_source_synthesis` | `TODO` | RAG 신뢰성 7 → 9 |
| **P3** | Eval 자동 러너 + CI 회귀 gate | `admin_productization`, 측정 신뢰성 | `TODO` | 코드 품질 8.5 → 9 |
| **P4** | 코어 약점 정리 (streaming budget, plan-execute, cost token, model routing) | `safe_action_workflows`, cost 측정 | `TODO` | 코어 깊이 8.5 → 9 |
| **P5** | MCP Connector Pack (5종 사전 구성 + 1-click install) | `connector_permissions` | `TODO` | connector 6 → 8 |
| **P6** | 한국 first 강화 (한국 SaaS 통합, 한국어 quickstart, OSS 노출) | `employee_value`, 차별화 | `TODO` | 한국 시장 8.5 → 9.5 |

#### Phase 별 deliverable 체크리스트

각 phase는 모든 항목이 `[x]`가 되면 status를 `DONE`으로 변경한다. 부분 완료는 `IN_PROGRESS`.

**P0 — 코드 위생 (병행 트랙, 분산 1-2주, 2026-04-12 audit 결과)**

코드는 OSS 평균 대비 매우 깨끗하나(TODO 0건, `!!` 0건, `any` 0건, `@Deprecated` 0건, 모듈 cyclic dep 0건), **5개 mega-file이 자기 규칙(메서드 ≤20줄 / 줄 ≤120자)을 어기고 있음**. 등급 B− → A−로 올리는 가장 빠른 ROI. ROI 우선순위에서 제외 — 다른 phase와 **병행 가능**, 진행을 막지 않는다.

Backend (`arc-core`):
- [ ] **`prompt/SystemPromptBuilder.kt` (1,369 LOC, `build()` 메서드 1,073줄)** — 5~7개 helper class로 분할. **byte-identical 보존(§0.2 제약)**, golden snapshot 테스트로 검증
- [ ] **`tool/ToolCallOrchestrator.kt` (1,265 LOC)** — filtering / routing / synthesis 3개 클래스 분할
- [ ] **`agent/impl/ExecutionResultFinalizer.kt` (940 LOC)** — 책임 분할 (`ExecutionResultFinalizer.kt:5686` 178자 worst line 포함)
- [ ] **`agent/impl/SpringAiAgentExecutor.kt` (840 LOC)** — god class 해체
- [ ] **`agent/impl/ManualReActLoopExecutor.kt` (887 LOC) + `StreamingReActLoopExecutor.kt` (717 LOC)** — 공통 로직 추출 (P4 streaming budget 정리와 함께 가능)
- [ ] **`ktlint` 또는 pre-commit hook 추가** — 120자 초과 143/393 파일(37%) 점진 정리
- [ ] **`internal` 키워드 적용** — autoconfigure 패키지 47개 top-level type 우선 (현재 81/393 = 21% internal). 외부 노출 surface 절반 감축 목표
- [ ] **`gradle/libs.versions.toml` Gradle version catalog 도입** — `springAiVersion` 4곳 산재 통합
- [ ] **`autoconfigure/ArcReactorJdbcStoreConfigurations.kt` (294 LOC, 5개 store 혼재)** — 1:1 파일로 분리
- [ ] 6개 `ConcurrentHashMap` → Caffeine bounded cache 검토 (R-ticket 한 건씩 마이그레이션, 정당화 KDoc 유지). 위반 위치: `StageTimingSupport.kt:39`, `McpManager.kt:174`, `InMemoryRagIngestionStores.kt`, `HookModels.kt:72`, `ExperimentStore.kt`, `LiveExperimentStore.kt`
- [ ] 7개 `runBlocking` 위치 검토 — 가능한 곳은 `suspend` 변환. 우선: `DynamicSchedulerService.kt:190,195,303` / `McpManager.kt:436` / `SemanticToolSelector.kt:370,408`. 정당화 유지: `BlockingToolCallbackInvoker.kt:31` (Spring AI 인터페이스 제약)

Frontend (`arc-reactor-admin`):
- [ ] **`src/features/issues/mcpHelpers.ts` (790 LOC)** — 헬퍼 누적 정리 (longest 파일)
- [ ] **`src/features/mcp-servers/ui/RegisterServerModal.tsx` (514 LOC)** — 60+ `as` cast → discriminated union 폼 타입 재설계
- [ ] **`src/features/mcp-servers/useMcpServersList.ts` (474 LOC)** — prefetch / filter / state 3개 훅 분할
- [ ] `tsconfig.app.json`에 `noUncheckedIndexedAccess: true` 추가
- [ ] **6개 orphan 페이지 정리** — `OutputGuardPage`, `ProactiveChannelsPage`, `PromptLabPage`, `PromptsPage`, `TenantAdminPage`, `ToolPolicyPage` (백워드 호환 리다이렉트 명시 또는 legacy 폴더 이동)
- [ ] **415개 inline `style={{}}`** → Tailwind className 점진 정리 (큰 컴포넌트부터, 한 round 당 1~2개)

Cache Observability Wiring (R347):
- [x] `PlatformAdminController.cacheStats()` single source of truth로 통일 — R347 완료. 기존에는 `PipelineHealthMonitor` AtomicLong(MetricCollectorAgentMetrics가 증가)과 Micrometer `arc.cache.hits`(cacheMetricsRecorder가 증가)가 **별개 소스로 분리**되어 split brain 가능성. 이제 Micrometer로 통일하여 admin API와 `/actuator/prometheus`가 항상 동일 값. TDD RED→GREEN, 50/50 test PASS
- [x] `start-stack.md`에 `--arc.reactor.diagnostics.startup-log.enabled=true` 추가 — R347 완료. 시작 시 Response Cache tier 자동 로그 출력 (`grep "Response Cache" /tmp/arc-reactor-backend.log`)
- [x] `PlatformAdminController.health()` cache 필드도 같은 fix 적용 — R347 완료. `/health`의 `cacheExactHits`/`cacheSemanticHits`/`cacheMisses`도 Micrometer 카운터에서 읽음. TDD RED→GREEN, `PlatformHealth` nested 테스트가 이제 `SimpleMeterRegistry`로 데이터 주입
- [x] 회귀 가드 테스트 추가 — R347 완료. `AgentExecutionCoordinatorTest`의 `cache hit일 때` 테스트에 `cacheMetricsRecorder.recordExactHit()` 호출 검증 추가. 누군가 `AgentExecutionCoordinator.kt:425`의 wire를 제거하면 즉시 회귀 감지
- [ ] (선택) 전체 testcontainers Redis end-to-end 통합 테스트 — AgentExecutionCoordinator → MicrometerCacheMetricsRecorder → `/api/admin/platform/cache/stats` 경로 전체 검증. 현재 unit 수준 회귀 가드로 80% 커버, testcontainers는 finishing move
- [ ] `PipelineHealthMonitor` cache 필드 및 `MetricCollectorAgentMetrics.recordExactCacheHit`의 AtomicLong 증가 코드 정리 — R347 이후 admin API가 더 이상 읽지 않으므로 writer도 함께 제거 가능 (단, arc-admin 다른 곳에서 snapshot cache 필드를 참조하지 않는지 grep 확인 후)

**P1 — 첫인상 자산 (목표 1주, ROI 가장 큼)**
- [ ] README 재작성: 1문장 USP + 스크린샷 3장 + 5초 GIF + Quick Start 한 블록 + Why Arc Reactor 4불릿 + Comparison 표 (vs Onyx/Dify/LangChain)
- [ ] `docker-compose.demo.yml` (backend + admin frontend + Postgres + Redis + 시드 데이터: 페르소나 3개, 샘플 문서 5개, MCP filesystem 1개)
- [ ] `make demo` 한 줄 entrypoint (또는 `./scripts/demo.sh`) — 첫 사용자가 30초 안에 admin 로그인 → 1분 안에 첫 대화 → 2분 안에 RAG 동작 확인
- [ ] 2분 데모 영상 (Loom/OBS), README 상단 임베드
- [ ] GitHub repo description, topics (`ai-agent`, `spring-ai`, `kotlin`, `enterprise`, `rag`, `mcp`)
- [ ] Badges: build, license, kotlin version, JDK version
- [ ] v0.1.0 첫 release + CHANGELOG
- [ ] `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`
- [ ] (선택) GitHub Pages 랜딩 (`arc-reactor.dev` 또는 username.github.io)

**P2 — Citation Grounding (목표 2주, 가장 큰 차별화)**
- [ ] `arc-core/.../rag/citation/CitationInjector` — retrieved doc → 프롬프트 `[1]` 인덱스 주입 + system prompt에 출처 표기 룰 추가
- [ ] `arc-core/.../rag/citation/CitationExtractor` — 응답 텍스트에서 `[N]` 패턴 추출 → `RetrievedDocument`와 매핑
- [ ] `GroundedResponse` data class (text, citations, groundedRatio)
- [ ] `AgentResult.citations` 필드 추가
- [ ] opt-in flag `arc.reactor.rag.citations.enabled` (기본 false, byte-identical 보존)
- [ ] hardening 테스트: citation 추출 정확도 + grounded ratio 계산
- [ ] arc-reactor-admin: `/sessions/:sessionId`에 inline `[N]` 마커 + 호버 source preview + 클릭 시 원문 패널
- [ ] arc-reactor-admin: `/chat-inspector`에 어떤 chunk가 사용됐는지 trace
- [ ] arc-reactor-admin: 메시지 컴포넌트에 grounded badge (✓ 출처 N개 / ⚠️ 출처 없음)
- [ ] `EvaluationMetrics`에 `groundedAnswerRate`, `citationCount`, `citationCoverage` 추가
- [ ] admin `/usage` 또는 `/performance`에 grounded rate trend chart

**P3 — Eval 자동 러너 + CI Gate (목표 1주)**
- [ ] `./gradlew evalRun -Paxis=grounded_retrieval -Pcases=ATL-JIRA-001..030` CLI
- [ ] `arc-core/.../promptlab/eval/runner/EvalRunner.kt` 구현 (3-tier `EvaluationPipeline` 재활용)
- [ ] eval set 외부화: `arc-core/src/main/resources/eval/atl-cases.yaml`
- [ ] JSON 결과 출력: axis별 pass/warn/fail count
- [ ] GitHub Actions workflow: `eval-required` 라벨 PR마다 evalRun → 직전 main 비교 → 회귀 시 PR fail
- [ ] baseline 저장: `docs/eval-baselines/main.json`
- [ ] arc-reactor-admin `/evals`: axis별 trend chart (Recharts) + 회귀 마커 + rerun 버튼

**P4 — 코어 약점 정리 (목표 2-3주)**
- [ ] `StreamingReActLoopExecutor`에 `StepBudgetTracker` 주입 + 토큰 누적 + hardening 테스트
- [ ] `PlanExecuteStrategy` JSON parse fail 시 ReAct one-shot fallback
- [ ] `PlanExecuteStrategy` plan/synthesize LLM 콜에 budget tracker wiring
- [ ] (선택) `PlanExecuteStreamingStrategy` 신설
- [ ] `DefaultPlanValidator`에 tool 권한 + idempotency 검증 강화
- [ ] `CostCalculator`에 cached/live/reasoning token 분리 (Anthropic prompt cache 활용)
- [ ] admin `/usage`에 cached vs live 비율 차트
- [ ] `MonthlyBudgetTracker` → `AgentExecutionCoordinator` wiring
- [ ] `ModelRoutingConfiguration` 실사용 fallback 로직 (primary 503 → secondary 자동 전환)
- [ ] per-tenant model preference (admin에서 설정 가능)

**P5 — MCP Connector Pack (목표 1-2주)**
- [ ] `arc-mcp-pack/docker-compose.mcp-pack.yml`: filesystem + brave-search + github + slack + postgres 5종
- [ ] 각 서버 `.env.example` 템플릿 + 발급 링크 README
- [ ] (선택) admin `/mcp-marketplace` 페이지 — 사전 검증 MCP 서버 1-click install
- [ ] security policy 자동 적용 (allowlist)
- [ ] 첫 사용자 onboarding 시 5개 connector 자동 인식 동작 검증

**P6 — 한국 first 강화 (선택, 후순위)**
- [ ] 한국어 README (영어 README와 1:1 sync)
- [ ] 한국어 데모 영상
- [ ] Naver Works 어댑터 (Slack 어댑터 패턴 재사용)
- [ ] KakaoWork 어댑터
- [ ] (선택) Jandi, Dooray 어댑터
- [ ] KoBERT 임베딩 옵션 + 한국어 chunking 휴리스틱 강화
- [ ] OSS 노출: GeekNews 글, OKKY 글, 한국 LLM 디스코드, Spring/Kotlin 페이스북 그룹

#### 라운드별 Phase 기여 규칙

1. **모든 라운드는 phase 1개 이상에 기여한다.** Round 보고서 §4.1의 `phase_contribution` 필드에 명시한다 (예: `P2`, `P1+P2`).
2. 어떤 phase에도 기여하지 않는 라운드는 원칙적으로 진행하지 않는다. 예외 시 보고서에 사유와 phase unblock 효과를 적는다.
3. **체크리스트 deliverable 완료 시 즉시 이 §0.10 표를 업데이트한다.** `[ ]` → `[x]`로 바꾸고, phase의 모든 deliverable이 끝나면 status를 `DONE`으로 변경한다. **이 업데이트는 같은 commit에 포함한다.**
4. **새 phase 추가 / 순서 조정 / deliverable 추가는 사용자 승인 필요.** 자동 변경 금지.
5. Phase status 업데이트는 cache key에 영향 없음 — qa-verification-loop.md는 `systemPrompt`가 아니므로 §0.2 byte-identical 제약과 무관.
6. **ROI 우선순위: P1 > P2 > P3 > P4 > P5 > P6.** 예외는 사용자 명시 요청 또는 blocker 발생 시. **P0(코드 위생)는 ROI 순위에서 제외 — 병행 트랙으로 언제든 작업 가능, 다른 phase 진행을 막지 않음.** 한 round 당 P0 deliverable 1개를 P1~P6 작업과 함께 처리해도 무방.
7. 같은 라운드에서 여러 phase에 동시 기여 가능하나, **한 라운드당 deliverable 1~3개로 제한** (한 번에 하나의 패턴 원칙 §0).
8. P1/P5 같은 코드 외 작업도 정상적인 round로 카운트한다. 코드 변경이 없는 round여도 evidence로 README/docker-compose/영상 등 artifact 경로를 남긴다.

#### Phase ↔ Axis 매핑

| Axis | 주 기여 Phase | 보조 기여 Phase |
|---|---|---|
| `connector_permissions` | P5 | P1 (discoverability) |
| `grounded_retrieval` | **P2** | P3 (자동 측정) |
| `cross_source_synthesis` | P2 | P3, P5 |
| `safe_action_workflows` | P4 (budget, approval, policy) | — |
| `admin_productization` | P3, P1 (UI 폴리시, eval 가시화) | P2 (citation UI) |
| `employee_value` | P1, P6 | 모든 phase |
| 코드 품질 / OSS trust (axis 외) | **P0** | P4 (코어 정리와 함께 가능) |

#### 진행도 자동 산출 방법

매 round 보고서에 아래 1줄을 함께 기록한다 (자동 집계용):

```
phase_progress: P0=4/22 P1=0/9 P2=0/11 P3=0/7 P4=0/10 P5=0/5 P6=0/7
```

- 분자: deliverable `[x]` 개수
- 분모: 전체 deliverable 개수
- 모든 phase의 분자 = 분모가 되면 9점 균형 달성

## 1) 준비 (Read)

1. `docs/agent-work-directive.md` 전체 Read
2. `docs/production-readiness-report.md`의 "10. 반복 검증 이력"에서 마지막 Round 번호 확인 → N+1
3. `TaskList` 확인 — pending/in_progress Directive 태스크 파악
   - **저장소에 별도 TaskList가 없으면, 이 파일의 §2 우선순위 표를 사실상의 TaskList로 사용한다.**
   - 이 경우 상태 관리는 별도 파일이 아니라 `docs/production-readiness-report.md`의 최근 Round 요약과
     `docs/reports/rounds/` 상세 파일로 대신한다.
4. 최근 5개 Round의 `axis / delta / remaining gap / foundation/direct_value` 확인
   - 메인 상태판의 최근 요약을 먼저 읽고, 부족하면 `docs/reports/rounds/R{N}.md` 또는 legacy archive를 추가로 연다.
5. `docs/ko/testing/atlassian-enterprise-agent-eval-set.md` Read
6. `docs/ko/operations/assistant-runtime-backlog.md` Read
7. `docs/ko/operations/assistant-quality-gates.md` Read
8. `docs/ko/operations/assistant-self-development-backlog.md` Read
9. 실제 MCP를 사용할 경우 §0.9 live evidence 규칙 재확인
10. (선택) 현재 라운드 주제가 §0.4 "참조가 유용할 영역"과 겹치면 해당 레포 문서 경량 스캔
11. **§0.10 9-Point 균형 로드맵의 phase 체크리스트 status 확인 — 어느 phase가 `IN_PROGRESS`/`TODO` 인지 파악, ROI 우선순위(P1 > P2 > P3 > P4 > P5 > P6)에 따라 다음 deliverable 후보 선정**

## 2) 작업 선택 (우선순위)

### 2.0) Benchmark axis 먼저 선택

새 Round를 시작할 때는 Directive 태스크를 고르기 전에 **benchmark axis 1개**를 먼저 고른다.

- 우선순위는 아래 tie-break 순서를 따른다.
- 이미 최근 Round에서 같은 axis를 반복했지만 지표가 실제로 나아지지 않았다면, 같은 axis를 다시 골라도 된다.
- 반대로 구현은 컸지만 사용자 가치 지표가 변하지 않았다면 다음 Round에서도 같은 axis를 이어서 잡는다.
- **이미 `in_progress` 태스크가 있으면 그 태스크를 먼저 이어서 진행한다.** 이 경우 axis는
  tie-break로 새로 고르는 것이 아니라, **현재 태스크가 직접 개선하거나 unblock하는 axis**로 맞춘다.
- 즉, tie-break 우선순위는 **새 태스크를 고를 때만** 사용한다.

**tie-break 우선순위**:
1. `connector_permissions`
2. `grounded_retrieval`
3. `cross_source_synthesis`
4. `safe_action_workflows`
5. `admin_productization`
6. `employee_value`

작업 선택 전에 아래 문서에서 현재 gap을 먼저 읽는다:
1. `docs/qa-agent-quality-guide.md`
2. `docs/ko/operations/employee-value-rollout-300.md`
3. `docs/production-readiness-report.md`
4. `docs/reports/README.md`
5. `docs/ko/operations/assistant-runtime-backlog.md`

### 2.0.1) Atlassian-first 기본 우선순위

사용자가 별도 축을 강하게 요구하지 않았다면, 기본 제품 방향은 Atlassian assistant로 본다.

- Jira / Confluence / Bitbucket 관련 backlog가 열려 있으면 먼저 본다.
- 같은 사용자 가치라면 Atlassian 기본 흐름을 먼저 고친다.
- Swagger/work 계열은 Atlassian 흐름을 깨지 않는 선에서만 함께 다룬다.

### 2.0.2) Round Class 먼저 결정

각 Round는 먼저 `round_class`를 결정한다.

- 기본값: `product_improvement`
- 예외: `self_development`

`self_development`를 고를 수 있는 경우:

- self-development backlog에 open 항목이 있고
- 그 작업이 다음 product Round의 입력 품질을 직접 높이며
- 최근 5개 Round에서 self-development가 2개 이하일 때

단순 문서 미화, 표현 수정, 구조만 예뻐지는 작업은 `self_development`로 간주하지 않는다.

아래 우선순위대로 다음 작업을 고른다. **이미 완료된 것은 건너뛴다.**

| 우선 | 패턴 | 태스크 | 착수 조건 |
|------|------|--------|-----------|
| 1 | #4 Prompt Layer 공식 계층화 | #94 | 항상 가능 |
| 2 | #1 Tool Approval 4단계 구조화 | #95 | #94 완료 후 권장 |
| 3 | #5 Evaluation 상세 메트릭 | #96 | 언제든 |
| 4 | #2 ACI 도구 출력 요약 | #97 | 범위 큰 작업 |
| 5 | #3 Patch-First Editing | #98 | 보류 (범위 결정 후) |

**이미 진행 중인 태스크가 있으면 그것을 이어서 진행한다.** 새 작업을 시작할 때는 해당 태스크를 `in_progress`로 마킹하고, 완료 시 `completed`로 전환한다.
별도 TaskList가 없다면, 이 상태 변화는 `docs/production-readiness-report.md`의 최신 Round 요약과
`docs/reports/rounds/R{N}.md`의 상세 보고서에 "현재 진행 중 / 이번 Round 완료" 문장으로 기록한다.

### 2.1) 반복 작업 방지 규칙

최근 5개 Round 안에서 아래 3개가 동시에 겹치면 **실질적으로 같은 작업**으로 본다.

- 같은 `Benchmark Axis`
- 같은 subsystem 또는 같은 핵심 파일군
- 같은 `current_gap` 또는 거의 같은 사용자 문제

이 경우에는 아래 규칙을 따른다.

1. 최근 2개 Round가 같은 작업인데 measurable delta가 없었다면, 같은 작업을 그대로 반복하지 않는다.
2. 계속 진행하려면 아래 중 최소 1개가 새로 있어야 한다.
   - 범위를 더 좁힌 새로운 가설
   - 다른 evidence source
   - 실패 원인을 분리한 더 작은 fix target
   - 같은 axis 안에서 다른 subsystem
3. 위 조건이 없으면 다른 axis나 다른 gap으로 전환한다.
4. "지난 Round와 사실상 같은데 표현만 바꾼 작업"은 금지한다.

## 3) 작업 실행 규칙

### 3.1) 수정 전 필수

1. 변경 대상 파일 전부 Read
2. 관련 테스트 파일 존재 여부 확인
3. CLAUDE.md + `.claude/rules/*.md` 원칙 숙지 (cancellation, message pair, `content.orEmpty()`, 한글 KDoc 등)

### 3.2) 코드 수정

- **opt-in**: 새 기능은 `arc.reactor.*.enabled=false` 기본값으로 추가
- **선택 의존성**: `ObjectProvider<T>`
- **Configuration 추가**: `ArcReactorAutoConfiguration.kt`의 `@Import`에 등록
- **Kotlin/Spring 규칙**: 메서드 ≤20줄, 줄 ≤120자, 한글 주석, `Regex` 함수 내 생성 금지
- **Executor 수정**: `e.throwIfCancellation()` 필수, ReAct 루프 규칙 준수

### 3.3) 테스트 필수

- JUnit 5 + MockK + Kotest assertions
- 모든 assertion에 실패 메시지 (`assertTrue(x) { "이유" }`)
- 새 클래스마다 테스트 파일 생성
- 기존 테스트 깨지지 않는지 확인

### 3.4) 빌드 검증

```bash
./gradlew compileKotlin compileTestKotlin        # warning baseline 악화 금지
./gradlew :arc-core:test --tests "변경한 Test 클래스"
./gradlew test                                    # 전체 회귀 (선택적)
```

`0 warnings`가 이미 깨진 저장소에서는 **기존 warning baseline을 악화시키지 않는 것**을 기본 규칙으로 한다.
이번 Round와 직접 관련 없는 기존 warning까지 한 번에 고치려 하지 않는다. 다만 새 warning을 추가하면 안 된다.

### 3.5) Benchmark Hypothesis

코드 수정 전에 아래 4개를 먼저 적고 시작한다.

```markdown
- chosen_axis: {connector_permissions|grounded_retrieval|cross_source_synthesis|safe_action_workflows|admin_productization|employee_value}
- current_gap: {지금 사용자에게 보이는 문제 1문장}
- expected_delta: {어떤 지표가 얼마나 좋아져야 하는지}
- evidence_paths: {테스트/문서/리포트/메트릭 경로 1개 이상}
```

이 4개는 임시 메모로 끝내지 말고, **반드시 Round 보고 섹션에 그대로 남긴다**.
보고 시 위치는 `Benchmark Hypothesis` 블록으로 고정한다.

추가로 아래 2개를 같이 정한다.

```markdown
- round_class: {product_improvement|self_development}
- evaluated_cases: {이번 Round에서 실행할 eval case 2개 이상}
- backlog_item: {이번 Round가 직접 줄이려는 backlog id 1개}
```

`evaluated_cases`가 없으면 이 Round는 품질 개선 Round로 보지 않는다.
`backlog_item`이 없으면 이 Round는 제품 개선보다 내부 정리에 가깝다고 본다.

`self_development` Round라면 아래도 함께 적는다.

```markdown
- self_dev_item: {SD-BG-...}
- loop_health_target: {eval_coverage|backlog_quality|gate_automation|evidence_capture|report_schema|watchdog_recovery}
```

**Foundation or Direct Value 분류 규칙**:
- `direct_value`: 이번 Round 결과만으로도 grounded answer, source synthesis, approval UX,
  운영자 액션 가능성, 직원 가치 지표 중 하나가 **직접 개선**되는 경우
- `foundation`: 이번 Round 결과는 직접 사용자 가치보다 작지만, 다음 Round에서 특정 axis를
  개선하기 위한 blocker 제거/관측 추가/권한 정합성 확보 역할을 하는 경우
- 애매하면 `direct_value`가 아니라 `foundation`으로 적고, 어떤 axis를 unblock하는지
  `Remaining Gap`에 명시한다

**read assistant 계열 작업**은 아래 중 최소 1개를 직접 개선 목표로 잡아야 한다.
- grounded coverage
- blocked response rate
- top missing query closure
- tool family correctness
- answer mode quality

**action/workflow 계열 작업**은 아래 3개를 모두 만족해야 한다.
- approval regression test 추가 또는 갱신
- write-policy regression test 추가 또는 갱신
- preview vs execute 구분 검증

**허용되는 foundation 작업**:
- 커넥터 가용성/권한/출처 정합성 개선
- grounded answer 품질을 높이기 위한 prompt/tool/routing 개선
- 운영자가 top missing query를 더 빨리 고치게 하는 관측 개선

**허용되지 않는 foundation 작업**:
- 사용자 가치와 연결 설명이 없는 구조 정리
- 지표/평가셋/회귀 테스트 없이 "더 좋아 보이는" 변경
- 벤더 제품을 흉내 내는 UI/카피 변경

**새벽 자동 실행 추가 규칙**:
- direct_value가 가능한데 foundation을 고르지 않는다
- measurable delta가 없는 axis를 3회 이상 연속 반복하지 않는다
- baseline/after evidence가 없으면 commit/push하지 않는다
- 실패한 Round에서 범위를 넓혀 재시도하지 않는다

### 3.6) 실패 시 행동 규칙

아래 중 하나라도 만족하면 해당 Round는 **실패 Round**로 처리한다.

- `compileKotlin` 또는 `compileTestKotlin` 실패
- 이번 Round가 추가한 warning 발생
- 대상 회귀 테스트 실패
- 관련 eval case 미실행
- 핵심 evidence 부재
- self-development Round인데 `loop_health_delta`가 비어 있음
- preview/approval/write-policy 구분이 깨짐
- raw MCP 데이터가 tracked 파일에 남아 있음
- 기존 제약(§0.1 ~ §0.3) 위반 가능성이 해소되지 않음

실패 Round에서는 아래를 강제한다.

1. **push 금지**
2. 범위를 더 넓혀서 같은 Round 안에서 추가 수정 금지
3. `docs/reports/rounds/R{N}.md`에는 반드시 실패 원인과 중단 이유를 남긴다
4. `Run Safety Decision`의 `stop_or_continue_next_round`는 반드시 `stop`
5. 다음 Round 후보는 "더 작은 복구 작업 1개"만 남긴다

실패 Round의 메인 상태판 요약은 아래처럼 짧게 남긴다.

- 분류는 유지하되, 요약 앞에 `실패:`를 붙인다
- 상세 위치는 반드시 해당 `R{N}.md`
- 성공처럼 보이는 표현 금지

## 4) 보고

Round를 끝낼 때는 아래 두 군데를 함께 갱신한다.

1. `docs/reports/rounds/R{N}.md`
   - 상세 설계/근거/테스트/evidence/remaining gap 전체 기록
2. `docs/production-readiness-report.md`의 "10. 반복 검증 이력"
   - 최근 Round 요약 엔트리만 추가

메인 상태판은 **최근 20개 Round 요약만 유지**한다.
20개를 넘기면 오래된 요약은 메인 보고서에서 제거하고, 상세 이력은 round 파일이나 archive에 남긴다.

### 4.0) 출력 형식 하드 게이트

Round 보고는 자유 서술형 문서가 아니라 **고정 스키마 출력**으로 취급한다.

아래 규칙을 지킨다.

1. `docs/reports/rounds/R{N}.md`는 §4.1 템플릿의 섹션 순서를 그대로 유지한다.
2. 필드를 빼먹지 않는다. 값이 없으면 `없음`, `해당 없음`, `미실행` 중 하나로 명시한다.
3. 제목, 섹션명, 필드명은 임의로 바꾸지 않는다.
4. 메인 상태판 요약도 §4.2의 4개 bullet을 그대로 유지한다.
5. 장문 설명이 필요하면 메인 상태판이 아니라 `R{N}.md`에만 적는다.
6. 템플릿을 요약문으로 대체하거나 여러 필드를 한 문단으로 합치지 않는다.

즉, 에이전트는 매 Round마다 **같은 모양의 출력**을 만들어야 한다.

### 4.1) Round 상세 파일 템플릿

`docs/reports/rounds/R{N}.md`:

```markdown
# Round N — 🛠️ YYYY-MM-DDTHH:MM+09:00 — Directive #X: {패턴명}

**작업 종류**: Directive 기반 코드 개선 (baseline/evidence 포함)
**Round Class**: `product_improvement | self_development`
**Directive 패턴**: #{번호} {이름}
**완료 태스크**: #{TaskID}
**Benchmark Axis**: `{axis}`
**Foundation or Direct Value**: `foundation | direct_value`
**Phase Contribution**: `P0 | P1 | P2 | P3 | P4 | P5 | P6 | P0+P2 | P1+P3 | ...` (§0.10 참조, 1개 이상 필수. P0은 병행 가능)

#### Benchmark Hypothesis
- chosen_axis: `{axis}`
- current_gap: (지금 사용자에게 보이는 문제 1문장)
- expected_delta: (어떤 지표가 얼마나 좋아져야 하는지)
- round_class: `product_improvement | self_development`
- evaluated_cases:
  - `ATL-...`
  - `ATL-...`
- backlog_item: `ATL-BG-...`
- self_dev_item: `SD-BG-... | 해당 없음`
- loop_health_target: `... | 해당 없음`
- evidence_paths:
  - `path:line`
  - `path:line` (선택)

#### 변경 요약
- 파일: `path:line`
- 핵심 변경: (한 줄)

#### 설계 메모
- (왜 이 접근인지, 대안 대비 장단점)

#### User-visible Delta
- (이번 변경으로 사용자가 체감하는 변화 1~2줄)

#### Evidence
- baseline: (기존 리포트/지표/테스트)
- after: (신규 테스트/리포트/측정값)
- live_data_usage: `yes | no`
- live_data_sanitized: `yes | no`
- live_data_deleted_after_round: `yes | no`
- evidence_paths:
  - `path:line`
  - `path:line` (선택)

#### Quality Gate Result
- evaluated_cases:
  - `ATL-...`
  - `ATL-...`
- round_gate: `PASS | FAIL`
- overnight_gate: `PASS | FAIL | NOT_EVALUATED`
- release_gate: `PASS | FAIL | NOT_EVALUATED`
- self_dev_round_gate: `PASS | FAIL | NOT_APPLICABLE`
- gate_notes: (어떤 메트릭이 통과/실패했는지)

#### Loop Health Delta
- (self-development Round라면 루프 자체가 무엇 때문에 더 좋아졌는지)
- (product_improvement Round라면 `해당 없음`)

#### Run Safety Decision
- execution_mode: `manual | overnight_watchdog`
- why_safe_to_ship_now: (이번 Round가 작은 범위인지, 왜 새벽 자동 실행에 적합했는지)
- stop_or_continue_next_round: `stop | continue`

#### Remaining Gap
- (아직 없는 capability 또는 다음 Round에서 줄여야 할 gap)

#### Phase Progress
- phase_contribution: `P0 | P1 | P2 | ...` (§0.10)
- deliverables_completed: (이번 Round에서 `[x]`로 마킹한 §0.10 항목들)
- phase_progress: `P0=N/22 P1=N/9 P2=N/11 P3=N/7 P4=N/10 P5=N/5 P6=N/7` (전체 진행도)
- §0.10 status_change: (있다면 어느 phase가 `TODO` → `IN_PROGRESS` 또는 `IN_PROGRESS` → `DONE`으로 바뀌었는지)

#### 테스트
- 신규 테스트: N개
- 결과: PASS/FAIL

#### 빌드
- compileKotlin: PASS
- 전체 테스트: PASS (또는 해당 모듈만)

#### opt-in 기본값
- 새 기능 flag: `arc.reactor.X.enabled` (기본 false)
- 기존 경로 영향: 없음

#### 다음 Round 후보
- (남은 Directive 태스크 중 다음 우선순위)
- (같은 axis 지속 또는 axis 전환 필요 여부)
```

### 4.2) 메인 상태판 요약 템플릿

`docs/production-readiness-report.md`의 "10. 반복 검증 이력":

```markdown
### Round N — YYYY-MM-DDTHH:MM+09:00 — {짧은 제목}

- round_class: `product_improvement | self_development`
- axis: `{axis}`
- 분류: `foundation | direct_value`
- phase: `P0 | P1 | P2 | P3 | P4 | P5 | P6 | P0+P2 | ...` (§0.10)
- phase_progress: `P0=N/22 P1=N/9 P2=N/11 P3=N/7 P4=N/10 P5=N/5 P6=N/7`
- 요약: (이번 Round의 가장 중요한 사용자/운영 변화 1~2줄)
- 상세 위치: `docs/reports/rounds/R{N}.md`
```

### 4.3) 메인 상태판 prune 규칙

메인 상태판에 새 Round 요약을 추가한 뒤, `10. 반복 검증 이력`의 `### Round` 엔트리 수를 확인한다.

1. 20개 이하이면 그대로 둔다.
2. 21개 이상이면 **가장 오래된 요약부터** 삭제해 다시 20개로 맞춘다.
3. 삭제 대상은 메인 상태판의 요약 엔트리뿐이다.
4. `docs/reports/rounds/R{N}.md`와 archive는 삭제하지 않는다.
5. Round 번호 재정렬이나 재번호 부여는 하지 않는다.

## 5) 커밋 & Push

```bash
git add {수정 파일들} docs/production-readiness-report.md docs/reports/rounds/R{N}.md docs/reports/README.md
git commit -m "{접두사}: R{N} — Directive #{X} {한 줄 요약}

{상세 설명}"
git push origin main
```

**커밋 접두사 가이드**:
- `feat:` — 새 기능 추가
- `refactor:` — 구조 재정비 (계층화, 분리)
- `test:` — 테스트만 추가
- `docs:` — 문서만
- `perf:` — 성능 개선 (측정값 필수)
- `fix:` — 버그 수정

## 6) 금지 사항 (Directive §8 준수)

1. 외부 OSS의 코드/시스템 프롬프트를 그대로 가져오기 — **절대 금지** (§0.4 참조 레포 포함, MIT라도 프로젝트 정책상 금지)
2. 기본값으로 멀티 에이전트/자유 루프 켜기
3. 승인 없는 위험 도구 실행 경로 만들기
4. 평가셋 없이 "좋아졌다"고 판단하기
5. 메모리/컨텍스트 무제한 누적
6. **atlassian-mcp-server 연동 경로(도구 발견/호출/응답) 파괴적 변경** — §0.1 참조
7. **Redis 의미적 캐시 scopeFingerprint 변경** — §0.2 참조
8. **sessionId 포맷 / MemoryStore 인터페이스 / ConversationMessageTrimmer 가드 규칙 파괴적 변경** — §0.3 참조

## 7) QA 측정 루프와의 관계

R217까지의 QA 측정 기반 루프는 Gemini API 쿼터 소진으로 중단된 상태다. 쿼터 회복 후 별도 재개하며, **이 루프와 혼용하지 않는다**. Round 번호는 연속으로 사용한다 (R218~는 Directive 라운드).

## 8) 한 Round 체크리스트 (요약)

```
[ ] 1. docs/agent-work-directive.md Read
[ ] 2. 마지막 Round 번호 확인
[ ] 3. 최근 5개 Round에서 axis 정체 / foundation 연속 여부 확인
[ ] 4. benchmark axis 선택
[ ] 4a. **§0.10 phase_contribution 결정 — P1~P6 중 1개 이상 명시 (ROI 우선순위 P1>P2>P3>P4>P5>P6)**
[ ] 5. round_class 결정 (`product_improvement` 기본, 예외적으로 `self_development`)
[ ] 6. baseline metric / 기존 gap 확인
[ ] 7. Atlassian eval set에서 관련 case 2개 이상 선택
[ ] 8. runtime backlog에서 이번 Round backlog item 1개 선택
[ ] 9. self-development면 `assistant-self-development-backlog.md`에서 self_dev_item 1개 선택
[ ] 10. 최근 5개 Round와 비교해 같은 작업 반복인지 확인
[ ] 11. TaskList(없으면 §2 우선순위 표)에서 다음 우선 작업 선택 (in_progress 마킹)
[ ] 12. chosen_axis / current_gap / expected_delta / round_class / evaluated_cases / backlog_item / evidence_paths 기록
[ ] 13. self-development면 self_dev_item / loop_health_target 기록
[ ] 14. direct_value 우선 여부 확인 (불가 시 foundation 이유 기록)
[ ] 15. 대상 파일 전부 Read
[ ] 16. 코드 수정 (opt-in, 한글 주석, 규칙 준수)
[ ] 17. 테스트 작성 (실패 메시지 필수)
[ ] 18. 선택한 eval case 실행
[ ] 19. ./gradlew compileKotlin compileTestKotlin
[ ] 20. ./gradlew :arc-core:test --tests "변경 테스트"
[ ] 21. baseline/after evidence 확보 확인
[ ] 22. live MCP 사용 시 raw data 비커밋 규칙 확인 (`.qa-runtime/`, `.qa-live/`만 사용)
[ ] 23. raw 데이터에서 메타데이터만 추출했는지 확인
[ ] 24. raw 데이터 임시 파일 삭제 (`.qa-runtime/`, `.qa-live/`)
[ ] 25. quality gate 판정 기록
[ ] 26. self-development면 loop_health_delta 기록
[ ] 27. 실패 게이트 해당 여부 확인 (해당 시 stop, push 금지)
[ ] 28. `docs/reports/rounds/R{N}.md` 상세 보고서를 고정 스키마대로 작성
[ ] 29. 메인 상태판의 "10. 반복 검증 이력"에 최근 Round 요약 추가
[ ] 30. 최근 20개 요약만 유지하는지 확인
[ ] 31. staged 파일에 raw data / `.qa-runtime/` / `.qa-live/`가 없는지 확인
[ ] 32. git add + commit + push
[ ] 33. TaskList 완료 마킹(없으면 최근 Round 요약 + 상세 보고서에 상태 기록), 다음 Round 후보 정리
[ ] 33a. **§0.10 phase deliverable 체크리스트 업데이트 — `[ ]` → `[x]`로 마킹, phase 전체 완료 시 status를 `DONE`으로 변경, 같은 commit에 포함**
[ ] 33b. **§0.10 baseline 표 / Phase 정의 표 자체는 사용자 승인 없이 수정 금지** (deliverable 체크박스만 자동 갱신 허용)
```

## 9) 이 루프를 교체할 때

QA 측정 기반 루프로 돌아가고 싶으면 이 파일을 이전 버전으로 되돌리거나 별도 prompt 파일을 만들어 사용자 iteration 템플릿에서 참조한다. 현재 사용자 템플릿:

```
.claude/prompts/qa-verification-loop.md 파일을 Read로 읽고 그대로 실행하라.
추가로 docs/production-readiness-report.md의 '10. 반복 검증 이력'에서
마지막 Round 번호를 확인하고 +1로 진행. 메인 보고서에는 요약만, 상세는
docs/reports/rounds/R{N}.md에 기록한다. 보고서 업데이트 시 반드시 커밋+push 한다.
```

이 파일 이름을 유지하면 사용자 템플릿을 바꾸지 않고도 루프 동작을 바꿀 수 있다.

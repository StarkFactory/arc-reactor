# Arc Reactor Agent 작업 루프 (Directive 기반)

> **이 루프는 `docs/agent-work-directive.md`를 기준으로 실제 코드 개선을 반복한다.**
> 측정/재시작/인프라 점검이 아닌, 작업 지시서의 5대 우선 패턴을 하나씩 착실하게 구현하는 것이 목적이다.

## 0) 이 루프가 지향하는 것

1. **코드 개선 우선, 측정 보조** — 외부 API 쿼터, 서버 재시작, 프로브 등 인프라 점검은 원칙적으로 건너뛴다.
2. **한 번에 하나의 Directive 패턴** — 여러 패턴을 동시에 손대지 않는다.
3. **측정 없는 개선 금지** — 코드 변경 시 단위 테스트로 효과를 검증한다. 평가셋이 없으면 만든다.
4. **opt-in 기본** — 새 기능은 기본 off, 기존 경로를 깨지 않는다.
5. **보고서는 간결하게** — 작업 내역은 `docs/production-readiness-report.md`의 "10. 반복 검증 이력"에 Round N 섹션으로 기록한다.

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

로컬에 clone되어 있는 두 레포는 **아이디어 차원에서 참조 가능**하다. 단 §8 금지 사항 1번("외부 OSS 코드/프롬프트 그대로 가져오기")은 라이선스와 무관하게 절대 규칙이다.

| 레포 | 경로 | 라이선스 | 정체 |
|---|---|---|---|
| claw-code | `/Users/stark/ai/claw-code` | MIT (ultraworkers) | Rust로 재구현한 멀티 에이전트 CLI. `clawhip` 코디네이션 루프 + Discord 인터페이스. 주요 crate: `runtime`, `api`, `commands`, `plugins`, `tools`, `telemetry` |
| openclaw | `/Users/stark/ai/openclaw` | MIT (Peter Steinberger 2025) | Personal AI Assistant. macOS/iOS/Android native 앱 + shared core |

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

## 1) 준비 (Read)

1. `docs/agent-work-directive.md` 전체 Read
2. `docs/production-readiness-report.md`의 마지막 Round 번호 확인 → N+1
3. `TaskList` 확인 — pending/in_progress Directive 태스크 파악
4. (선택) 현재 라운드 주제가 §0.4 "참조가 유용할 영역"과 겹치면 해당 레포 문서 경량 스캔

## 2) 작업 선택 (우선순위)

아래 우선순위대로 다음 작업을 고른다. **이미 완료된 것은 건너뛴다.**

| 우선 | 패턴 | 태스크 | 착수 조건 |
|------|------|--------|-----------|
| 1 | #4 Prompt Layer 공식 계층화 | #94 | 항상 가능 |
| 2 | #1 Tool Approval 4단계 구조화 | #95 | #94 완료 후 권장 |
| 3 | #5 Evaluation 상세 메트릭 | #96 | 언제든 |
| 4 | #2 ACI 도구 출력 요약 | #97 | 범위 큰 작업 |
| 5 | #3 Patch-First Editing | #98 | 보류 (범위 결정 후) |

**이미 진행 중인 태스크가 있으면 그것을 이어서 진행한다.** 새 작업을 시작할 때는 해당 태스크를 `in_progress`로 마킹하고, 완료 시 `completed`로 전환한다.

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
./gradlew compileKotlin compileTestKotlin        # 0 warnings
./gradlew :arc-core:test --tests "변경한 Test 클래스"
./gradlew test                                    # 전체 회귀 (선택적)
```

## 4) 보고

Round N 섹션을 `docs/production-readiness-report.md`의 "10. 반복 검증 이력" 끝에 추가한다.

```markdown
### Round N — 🛠️ YYYY-MM-DDTHH:MM+09:00 — Directive #X: {패턴명}

**작업 종류**: Directive 기반 코드 개선 (측정 없음)
**Directive 패턴**: #{번호} {이름}
**완료 태스크**: #{TaskID}

#### 변경 요약
- 파일: `path:line`
- 핵심 변경: (한 줄)

#### 설계 메모
- (왜 이 접근인지, 대안 대비 장단점)

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
```

## 5) 커밋 & Push

```bash
git add {수정 파일들} docs/production-readiness-report.md
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
[ ] 3. TaskList에서 다음 우선 작업 선택 (in_progress 마킹)
[ ] 4. 대상 파일 전부 Read
[ ] 5. 코드 수정 (opt-in, 한글 주석, 규칙 준수)
[ ] 6. 테스트 작성 (실패 메시지 필수)
[ ] 7. ./gradlew compileKotlin compileTestKotlin
[ ] 8. ./gradlew :arc-core:test --tests "변경 테스트"
[ ] 9. 보고서 Round N 섹션 추가
[ ] 10. git add + commit + push
[ ] 11. TaskList 완료 마킹, 다음 Round 후보 정리
```

## 9) 이 루프를 교체할 때

QA 측정 기반 루프로 돌아가고 싶으면 이 파일을 이전 버전으로 되돌리거나 별도 prompt 파일을 만들어 사용자 iteration 템플릿에서 참조한다. 현재 사용자 템플릿:

```
.claude/prompts/qa-verification-loop.md 파일을 Read로 읽고 그대로 실행하라.
추가로 docs/production-readiness-report.md의 '10. 반복 검증 이력'에서
마지막 Round 번호를 확인하고 +1로 진행. 보고서 업데이트 시 반드시 커밋+push 한다.
```

이 파일 이름을 유지하면 사용자 템플릿을 바꾸지 않고도 루프 동작을 바꿀 수 있다.

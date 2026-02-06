# Arc Reactor 남은 작업 계획

> 최종 감사: 2026-02-06 | 현재 점수: **8.0/10** | 테스트: 187개 전체 통과

## 완료된 작업

- [x] Tier 1: ToolSelector, AgentMetrics, RAG, Streaming 통합 (PR #3)
- [x] Tier 2: 스레드 안전성, AutoCloseable, JAR 정리 (PR #4)
- [x] P0: inputSchema, LlmProperties, 수동 ReAct 루프, maxToolCalls, Dispatchers.IO (PR #5)
- [x] P1: Semaphore+withTimeout, McpManager 타입 안전성, maxContextTokens, 데드코드 정리 (PR #6)

---

## P1 잔존 이슈 (출시 전 수정 권장)

### P1-1. 미사용 설정/코드 정리 (~15분)

**`LlmProperties.timeoutMs` 제거**
- 파일: `src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt:37`
- `ConcurrencyProperties.requestTimeoutMs`와 역할 중복. 어디서도 참조 안 됨
- 방안: 제거하거나, `runInterruptible` 감싸는 per-call 타임아웃으로 활용

**`AgentProperties.maxToolCalls` 연결**
- 파일: `src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt:26`
- `command.maxToolCalls`만 사용 중. Properties 기본값이 무시됨
- 방안: `command.maxToolCalls`의 fallback으로 `properties.maxToolCalls` 사용

**`ToolExecutor` object 제거**
- 파일: `src/main/kotlin/com/arc/reactor/tool/ToolExecutor.kt` (전체)
- `executeAsync`, `executeSync`, `executeWithTimeout` 모두 프로젝트에서 미사용
- 방안: 제거 (사용자용 유틸이면 유지 가능하나, 프레임워크 내부에서 미사용)

### P1-2. MessageRole.TOOL 변환 오류 (~15분)

- 파일: `src/main/kotlin/com/arc/reactor/agent/impl/SpringAiAgentExecutor.kt:341`
- 현재: `MessageRole.TOOL -> UserMessage(msg.content)` (잘못됨)
- 수정: `ToolResponseMessage`로 매핑하거나 별도 처리 필요
- 영향: 대화 이력에 Tool 메시지 포함 시 LLM이 잘못된 컨텍스트 수신

### P1-3. MCP Manager 코루틴 블로킹 (~30분)

**`synchronized` → `Mutex` 전환**
- 파일: `src/main/kotlin/com/arc/reactor/mcp/McpManager.kt:161, 292`
- `connect()`/`disconnect()`가 `suspend fun`이면서 `synchronized` 사용
- 코루틴 스레드를 블로킹하여 스레드 풀 고갈 가능
- 방안: `kotlinx.coroutines.sync.Mutex`로 교체

**`close()`의 `runBlocking` 제거**
- 파일: `src/main/kotlin/com/arc/reactor/mcp/McpManager.kt:335`
- Spring 종료 시 데드락 가능성
- 방안: `disconnect()` 내부 로직을 non-suspend private 함수로 분리

---

## P2 개선 사항 (다음 버전)

### P2-1. executeStream() 보강
- 동시성 제어(Semaphore + withTimeout) 미적용
- `agentMetrics.recordExecution()` 미호출
- `saveConversationHistory()` 미호출
- 파일: `SpringAiAgentExecutor.kt:232-310`

### P2-2. RagContext.totalTokens 부정확
- `context.length / 4` 대신 `DefaultTokenEstimator` 사용 권장
- 파일: `DefaultRagPipeline.kt:59`

### P2-3. AutoConfiguration 조건부 빈 로직
- `@ConditionalOnMissingBean(VectorStore::class, DocumentRetriever::class)` → 의도와 다르게 동작 가능
- 파일: `ArcReactorAutoConfiguration.kt:165`
- 방안: `@ConditionalOnMissingBean(DocumentRetriever::class)` 단독 사용

### P2-4. SpringAiToolCallbackAdapter 리플렉션
- `getName()` 대신 `getToolDefinition().name()` 구조로 업데이트 필요
- Spring AI 버전 업그레이드 시 호환성 문제 가능
- 파일: `ToolCallback.kt:80-129`

### P2-5. 미사용 코드 (기능에 영향 없음)
- `PassthroughQueryTransformer` - 사용자 유틸이므로 유지 가능
- `DefaultClassificationStage`/`DefaultPermissionStage` - 확장 포인트 (pass-through)
- `McpServer.autoConnect` 필드 - 미처리

### P2-6. 테스트 갭
- AutoConfiguration `@SpringBootTest` 컨텍스트 테스트
- `McpToolCallback.call()` 단위 테스트 (MCP client mock)
- `executeStream()` 도구 사용 시나리오
- `SpringAiVectorStoreRetriever` 단위 테스트

---

## 실행 순서 제안

```
1단계 (30분): P1-1 미사용 코드/설정 정리 → P1-2 MessageRole.TOOL 수정
2단계 (30분): P1-3 MCP Manager Mutex 전환 + close() 개선
3단계 (1시간): P2-1 executeStream() 동시성/메트릭 보강
4단계 (선택): P2 나머지 항목
```

## 검증

```bash
./gradlew test  # 187+ tests, all passing
```

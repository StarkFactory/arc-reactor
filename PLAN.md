# Arc Reactor 남은 작업 계획

> 최종 감사: 2026-02-06 | 현재 점수: **8.5/10** | 테스트: 187개 전체 통과

## 완료된 작업

- [x] Tier 1: ToolSelector, AgentMetrics, RAG, Streaming 통합 (PR #3)
- [x] Tier 2: 스레드 안전성, AutoCloseable, JAR 정리 (PR #4)
- [x] P0: inputSchema, LlmProperties, 수동 ReAct 루프, maxToolCalls, Dispatchers.IO (PR #5)
- [x] P1: Semaphore+withTimeout, McpManager 타입 안전성, maxContextTokens, 데드코드 정리 (PR #6)
- [x] P1-1: `LlmProperties.timeoutMs` 제거, `maxToolCalls` properties 연결 (시스템 상한), `ToolExecutor` 삭제
- [x] P1-2: `MessageRole.TOOL` → `ToolResponseMessage.builder()` 올바른 변환
- [x] P1-3: MCP Manager `synchronized` → `Mutex` 전환, `close()` `runBlocking` 제거 (`disconnectInternal`)
- [x] P2-2: RAG `context.length / 4` → `DefaultTokenEstimator` 정확한 다국어 토큰 추정
- [x] P2-3: AutoConfiguration `@ConditionalOnMissingBean(DocumentRetriever::class)` 단독 사용
- [x] DRY: `checkGuard()` + `checkBeforeHooks()` 공통 헬퍼 추출 (execute/executeStream 중복 제거)
- [x] DRY: ObjectMapper + JSON 파싱 → 파일 레벨 `parseJsonToMap()` 통합
- [x] DRY: `buildSystemPrompt()` 헬퍼 추출

---

## P2 개선 사항 (다음 버전)

### P2-1. executeStream() 보강
- 동시성 제어(Semaphore + withTimeout) 미적용
- `agentMetrics.recordExecution()` 미호출
- `saveConversationHistory()` 미호출
- 파일: `SpringAiAgentExecutor.kt:253-318`

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

## 실전 검증 (9.0 목표)

### E2E-1. 실제 MCP 서버 연동 테스트

현재 MCP 관련 테스트는 전부 mock 수준. 실제 MCP 서버를 띄워서 STDIO/SSE 연결을 검증해야 함.

**STDIO 테스트**
- `@anthropic/mcp-server-filesystem` 또는 간단한 echo MCP 서버 사용
- `DefaultMcpManager.connect()` → `listTools()` → `callTool()` 전체 흐름 검증
- 연결 실패, 타임아웃, 서버 종료 시 graceful 처리 확인

**SSE 테스트**
- 로컬 SSE MCP 서버 띄워서 `HttpClientSseClientTransport` 연동 확인
- 네트워크 끊김, 재연결 시나리오

**통합 시나리오**
- MCP 서버에서 로드한 도구를 `SpringAiAgentExecutor`의 ReAct 루프에서 실제 호출
- `BeforeToolCallHook` → MCP 도구 실행 → `AfterToolCallHook` 전체 파이프라인 검증

### E2E-2. AutoConfiguration 통합 테스트

- `@SpringBootTest`로 전체 빈 조립 검증
- `ChatClient` 없을 때 `AgentExecutor` 빈 미생성 확인
- `@ConditionalOnProperty`로 Guard 비활성화 시 Guard 빈 미생성 확인
- 커스텀 빈 등록 시 `@ConditionalOnMissingBean` 동작 확인

### E2E-3. 부하 테스트

- 동시 100+ 요청으로 Semaphore 동작 검증
- 메모리 누수 확인 (Caffeine 캐시 eviction, ConversationMemory 정리)
- 장시간 실행 시 스레드 풀 상태 모니터링

---

## 예제 앱 (오픈소스 공개용)

### Example-1. 기본 챗봇 (`examples/basic-chatbot/`)

간단한 Spring Boot 앱으로 Arc Reactor 사용법 시연:
- `application.yml` 설정 예시
- 커스텀 `ToolCallback` 구현 (날씨 조회 등)
- Guard + Hook 설정
- 멀티턴 대화 (메모리)

```
examples/basic-chatbot/
├── build.gradle.kts
├── src/main/kotlin/
│   ├── Application.kt
│   ├── WeatherTool.kt          # ToolCallback 구현 예시
│   ├── AuditHook.kt            # AfterAgentCompleteHook 예시
│   └── ChatController.kt       # REST API 엔드포인트
├── src/main/resources/
│   └── application.yml          # 전체 설정 키 예시
└── README.md
```

### Example-2. MCP 도구 연동 (`examples/mcp-tools/`)

MCP 서버 연결하여 외부 도구 사용:
- STDIO 방식 MCP 서버 등록 및 연결
- MCP 도구를 Agent에서 자동 로드하여 사용
- `ToolSelector`로 도구 필터링 예시

### Example-3. RAG 챗봇 (`examples/rag-chatbot/`)

문서 기반 Q&A 시스템:
- Spring AI VectorStore 연동
- `RagPipeline` 설정 (retriever + reranker)
- `maxContextTokens` 제한 예시
- 커스텀 `QueryTransformer` 구현

---

## 검증

```bash
./gradlew test  # 187+ tests, all passing
```

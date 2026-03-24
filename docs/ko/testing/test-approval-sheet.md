# Arc Reactor 테스트 승인 시트

> **목적**: 회사 승인용 테스트 목록. 기능별·성능·보안·프롬프트 테스트를 분류하고 바로 실행 가능한 명령어 포함.
> **대상 환경**: 최대 동접 300명, 기업 보안 등급, Admin 기능 필수
> **최종 갱신**: 2026-03-24

---

## 실행 순서

L1(보안) → L2(코어) → L3(통합) → L4(비용) → L5(운영) → L6(E2E) → P(성능) → S(프롬프트)
**하위 계층 실패 시 상위 계층 건너뛴다.**

```bash
# 사전 조건: 컴파일 0 warnings
./gradlew compileKotlin compileTestKotlin
```

---

## 카테고리 1: 보안 테스트 (L1)

### 1.1 프롬프트 인젝션 차단

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-01 | 직접 인젝션 | `./gradlew :arc-core:test --tests "*.PromptInjectionHardeningTest"` | 40+ 패턴 전부 `Rejected` | LLM 불필요 |
| S-02 | 유니코드 난독화 | 위 테스트에 포함 | 키릴/ZWSP/전각 문자 차단 | |
| S-03 | 다국어 인젝션 (15개 언어) | 위 테스트에 포함 | 한/중/일/터키/포르투갈 등 차단 | |
| S-04 | 시스템 구분자 주입 | 위 테스트에 포함 | `[SYSTEM]`, `<\|im_start\|>` 차단 | |
| S-05 | 역할 탈취 | 위 테스트에 포함 | "You are now DAN" 차단 | |
| S-06 | 메타 질문 감지 | 위 테스트에 포함 | "시스템 프롬프트 보여줘" 차단 | |

### 1.2 출력 가드

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-07 | PII 마스킹 | `./gradlew :arc-core:test --tests "*.OutputGuardHardeningTest"` | 주민번호/신용카드/이메일/전화 마스킹 | |
| S-08 | 카나리 토큰 누출 | 위 테스트에 포함 | 시스템 프롬프트 유출 탐지 | |
| S-09 | False positive 방지 | 위 테스트에 포함 | 정상 주소/날짜 통과 | |

### 1.3 도구 출력 정제

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-10 | 간접 인젝션 차단 | `./gradlew :arc-core:test --tests "*.ToolOutputSanitizationHardeningTest"` | 도구 응답 내 인젝션 → `[SANITIZED]` | |
| S-11 | 출력 크기 제한 | 위 테스트에 포함 | 초과 시 잘림 | |

### 1.4 메시지 쌍 무결성

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-12 | 다중 도구 호출 후 쌍 유지 | `./gradlew :arc-core:test --tests "*.MessagePairIntegrityHardeningTest"` | AssistantMsg + ToolResponseMsg 쌍 | |
| S-13 | 트리밍 후 쌍 유지 | 위 테스트에 포함 | 컨텍스트 축소 후에도 쌍 보존 | |

### 1.5 인증 & 인가

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-14 | JWT 검증 | `./gradlew :arc-core:test --tests "*.JwtAuthWebFilterTest" --tests "*.JwtTokenProviderTest"` | 만료/변조/null → 401 | |
| S-15 | Admin 권한 | `./gradlew :arc-core:test --tests "*.AdminAuthSupportTest"` | 비관리자 → 403 | |
| S-16 | Auth Rate Limit | `./gradlew :arc-core:test --tests "*.AuthRateLimitFilterTest"` | 10회/분 초과 → 429 | |
| S-17 | 토큰 폐기 | `./gradlew :arc-core:test --tests "*.TokenRevocationStoreTest"` | 폐기 토큰 재사용 차단 | |

### 1.6 SSRF 방지

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-18 | 사설 IP 차단 | `./gradlew :arc-core:test --tests "*.SsrfProtectionTest" :arc-web:test --tests "*.SsrfUrlValidatorTest"` | localhost/10.x/172.x/192.168.x 차단 | |

### 1.7 적대적 강화 (LLM 필요)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| S-19 | Red Team 자동화 | `GEMINI_API_KEY=$GEMINI_API_KEY ./gradlew :arc-core:test --tests "*.AdversarialRedTeamTest" -PincludeIntegration` | Guard 우회율 임계값 이하 | API 키 필요 |

### L1 일괄 실행

```bash
# 정적 하드닝 (~30초)
./gradlew :arc-core:test -PincludeHardening

# 안전 게이트 (~60초)
./gradlew :arc-core:test :arc-web:test -PincludeSafety

# 적대적 강화 (~120초, API 키 필요)
GEMINI_API_KEY=$GEMINI_API_KEY ./gradlew :arc-core:test --tests "*.AdversarialRedTeamTest" -PincludeIntegration
```

---

## 카테고리 2: 기능 테스트 (L2 - 에이전트 코어)

### 2.1 ReAct 루프

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-01 | ReAct 루프 종료 보장 | `./gradlew :arc-core:test --tests "*.ManualReActLoopExecutorTest"` | maxToolCalls 도달 시 강제 종료 | |
| F-02 | ReAct 경계값 | `./gradlew :arc-core:test --tests "*.ReActLoopHardeningTest"` | 빈 입력/초대형/특수문자 안전 처리 | |
| F-03 | ReAct 엣지 케이스 | `./gradlew :arc-core:test --tests "*.ReActEdgeCaseTest"` | 비정상 상황 복구 | |
| F-04 | 컨텍스트 트리밍 ReAct | `./gradlew :arc-core:test --tests "*.ContextTrimmingReActTest"` | 트리밍 후 루프 정상 계속 | |

### 2.2 도구 실행

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-05 | 병렬 도구 실행 | `./gradlew :arc-core:test --tests "*.ToolCallOrchestratorTest"` | 병렬 실행 + 타임아웃 | |
| F-06 | 도구 멱등성 | `./gradlew :arc-core:test --tests "*.InMemoryToolIdempotencyGuardTest"` | 동일 호출 캐시 반환 | |
| F-07 | 도구 승인 (HITL) | `./gradlew :arc-core:test --tests "*.DynamicToolApprovalPolicyTest" --tests "*.HumanInTheLoopTest"` | 위험 도구 승인 대기 | |
| F-08 | 도구 필터링 | `./gradlew :arc-core:test --tests "*.ContextAwareToolFilterTest"` | 컨텍스트 기반 도구 선택 | |

### 2.3 컨텍스트 & 메모리

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-09 | 컨텍스트 윈도우 관리 | `./gradlew :arc-core:test --tests "*.ConversationMessageTrimmerTest" --tests "*.ConversationMessageTrimmerMatrixTest"` | 쌍 단위 트리밍, off-by-one 없음 | |
| F-10 | 대화 메모리 | `./gradlew :arc-core:test --tests "*.ConversationManagerTest"` | 세션별 이력 격리 | |
| F-11 | 계층적 메모리 | `./gradlew :arc-core:test --tests "*.HierarchicalMemoryIntegrationTest"` | 장기/단기 메모리 통합 | |

### 2.4 RAG 파이프라인

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-12 | RAG 파이프라인 | `./gradlew :arc-core:test --tests "*.RagPipelineTest" --tests "*.RagContextRetrieverTest"` | 쿼리 변환→검색→리랭킹→Top-N | |
| F-13 | 하이브리드 검색 | `./gradlew :arc-core:test --tests "*.HybridRagPipelineTest"` | BM25 + 시맨틱 검색 | |
| F-14 | RAG 토큰 제한 | `./gradlew :arc-core:test --tests "*.RagPipelineMaxTokensTest"` | 컨텍스트 토큰 초과 방지 | |

### 2.5 멀티에이전트

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-15 | Sequential 오케스트레이션 | `./gradlew :arc-core:test --tests "*.SequentialOrchestratorTest"` | 순차 실행 + 개별 실패 격리 | |
| F-16 | Parallel 오케스트레이션 | `./gradlew :arc-core:test --tests "*.ParallelOrchestratorTest"` | 병렬 실행 + 부분 실패 허용 | |
| F-17 | Supervisor 오케스트레이션 | `./gradlew :arc-core:test --tests "*.SupervisorOrchestratorTest" --tests "*.SupervisorEdgeCaseTest"` | 감독자 패턴 + 엣지 케이스 | |

### 2.6 스트리밍

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-18 | 스트리밍 ReAct | `./gradlew :arc-core:test --tests "*.StreamingReActLoopExecutorTest"` | SSE 스트림 + 도구 호출 | |
| F-19 | 스트리밍 경계 | `./gradlew :arc-core:test --tests "*.StreamingEdgeCaseTest" --tests "*.StreamingBoundaryTest"` | 에러/연결끊김/리소스정리 | |

### 2.7 구조화된 출력

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| F-20 | JSON/YAML 출력 | `./gradlew :arc-core:test --tests "*.StructuredOutputTest" --tests "*.StructuredOutputValidatorTest"` | 스키마 준수 + 자동 복구 | |

### L2 일괄 실행

```bash
./gradlew :arc-core:test
```

---

## 카테고리 3: 통합 테스트 (L3)

### 3.1 Slack

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| I-01 | 이벤트 처리 | `./gradlew :arc-slack:test --tests "*.SlackEventProcessorTest"` | 중복 무시 + 서명 검증 | |
| I-02 | 이벤트 중복 제거 | `./gradlew :arc-slack:test --tests "*.SlackEventDeduplicatorTest"` | 동일 이벤트 1회만 처리 | |
| I-03 | 서명 검증 | `./gradlew :arc-slack:test --tests "*.SlackSignatureVerifierTest"` | 불일치 시 401 | |
| I-04 | 백프레셔 | `./gradlew :arc-slack:test --tests "*.SlackBackpressureLimiterTest"` | 세마포어 포화 시 거절 | |
| I-05 | 슬래시 커맨드 | `./gradlew :arc-slack:test --tests "*.SlackCommandProcessorTest" --tests "*.DefaultSlackCommandHandlerTest"` | 성공/실패 응답 | |
| I-06 | Socket Mode | `./gradlew :arc-slack:test --tests "*.SlackSocketModeGatewayTest"` | 재연결 + 지수 백오프 | |
| I-07 | 동시성 | `./gradlew :arc-slack:test --tests "*.SlackEventControllerConcurrencyTest"` | 세마포어 기반 제한 | |

### 3.2 MCP

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| I-08 | MCP 서버 등록 | `./gradlew :arc-core:test --tests "*.McpManagerTest"` | 런타임 등록/해제 | |
| I-09 | MCP 헬스체크 | `./gradlew :arc-core:test --tests "*.McpHealthPingerTest"` | 실패 시 도구 비활성화 | |
| I-10 | MCP 재연결 | `./gradlew :arc-core:test --tests "*.McpReconnectionTest"` | 복구 후 도구 재활성화 | |
| I-11 | MCP 도구 가용성 | `./gradlew :arc-core:test --tests "*.McpToolAvailabilityCheckerTest"` | 사전 검사 통과 | |

### 3.3 REST API

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| I-12 | Chat API 계약 | `./gradlew :arc-web:test --tests "*.ChatControllerTest"` | HTTP 상태코드 + 응답 구조 | |
| I-13 | 전역 에러 핸들링 | `./gradlew :arc-web:test --tests "*.GlobalExceptionHandlerTest"` | 일관된 에러 형식 | |
| I-14 | 멀티파트 업로드 | `./gradlew :arc-web:test --tests "*.MultipartChatControllerTest"` | 크기 제한 + OOM 방지 | |
| I-15 | API 버전 계약 | `./gradlew :arc-web:test --tests "*.ApiVersionContractWebFilterTest"` | 버전 호환성 | |

### L3 일괄 실행

```bash
./gradlew :arc-slack:test :arc-web:test
```

---

## 카테고리 4: 비용 & 자원 제어 (L4)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| C-01 | 비용 인식 라우팅 | `./gradlew :arc-core:test --tests "*.CostAwareModelRouterTest"` | 복잡도 기반 모델 선택 | |
| C-02 | 토큰 예산 | `./gradlew :arc-core:test --tests "*.StepBudgetTrackerTest"` | 80% 경고, 100% 종료 | |
| C-03 | 비용 계산 | `./gradlew :arc-core:test --tests "*.CostCalculatorTest"` | 모델별 단가 정확도 | core + admin |
| C-04 | 비용 이상 탐지 | `./gradlew :arc-core:test --tests "*.DefaultCostAnomalyDetectorTest" --tests "*.CostAnomalyHookTest"` | 2σ 초과 시 감지 | |
| C-05 | Rate Limiting | `./gradlew :arc-core:test --tests "*.DefaultGuardStagesTest"` | 분당/시간당 초과 → Rejected | |
| C-06 | 응답 캐시 | `./gradlew :arc-core:test --tests "*.CaffeineResponseCacheTest"` | 캐시 히트 시 LLM 미호출 | |
| C-07 | 캐시 통합 | `./gradlew :arc-core:test --tests "*.ResponseCacheIntegrationTest"` | TTL + 무효화 | |

### L4 일괄 실행

```bash
./gradlew :arc-core:test \
  --tests "*.CostAwareModelRouterTest" \
  --tests "*.StepBudgetTrackerTest" \
  --tests "*.CostCalculatorTest" \
  --tests "*.DefaultCostAnomalyDetectorTest" \
  --tests "*.CaffeineResponseCacheTest"
```

---

## 카테고리 5: 운영 성숙도 (L5 - Admin 중심)

### 5.1 멀티테넌시

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| O-01 | 테넌트 격리 | `./gradlew :arc-web:test --tests "*.TenantResolverTest" --tests "*.TenantContextResolverTest"` | 데이터 교차 접근 불가 | |
| O-02 | 테넌트 관리 | `./gradlew :arc-admin:test --tests "*.TenantServiceTest" --tests "*.TenantAdminControllerTest"` | CRUD + 일시중지/활성화 | |

### 5.2 메트릭 파이프라인

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| O-03 | Ring Buffer | `./gradlew :arc-admin:test --tests "*.MetricRingBufferTest"` | 동시 쓰기 유실 없음 | |
| O-04 | Metric Writer | `./gradlew :arc-admin:test --tests "*.MetricWriterTest"` | 배치 DB 기록 | |
| O-05 | Metric Hook | `./gradlew :arc-admin:test --tests "*.MetricCollectionHookTest"` | Core→Admin 데이터 흐름 | |
| O-06 | Pipeline Health | `./gradlew :arc-admin:test --tests "*.PipelineHealthMonitorTest"` | 버퍼 사용률/유실 카운트 | |

### 5.3 SLO & 알림

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| O-07 | SLO 서비스 | `./gradlew :arc-admin:test --tests "*.SloServiceTest"` | 가용성/레이턴시/에러 예산 | |
| O-08 | Alert 평가 | `./gradlew :arc-admin:test --tests "*.AlertEvaluatorTest"` | 규칙 기반 탐지 | |
| O-09 | Alert 스케줄러 | `./gradlew :arc-admin:test --tests "*.AlertSchedulerTest"` | 주기적 평가 | |
| O-10 | Alert 알림 | `./gradlew :arc-admin:test --tests "*.AlertNotificationServiceTest"` | 채널별 전송 | |

### 5.4 트레이싱 & 모니터링

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| O-11 | 분산 트레이싱 | `./gradlew :arc-core:test --tests "*.ArcReactorTracerTest" --tests "*.DistributedTracingSpanTest"` | Guard/LLM/Tool/RAG 단계별 span | |
| O-12 | 헬스체크 | `./gradlew :arc-core:test --tests "*.LlmProviderHealthIndicatorTest" --tests "*.McpServerHealthIndicatorTest"` | 비정상 시 unhealthy | |

### 5.5 Admin API

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| O-13 | Platform Admin | `./gradlew :arc-admin:test --tests "*.PlatformAdminControllerTest"` | 전체 엔드포인트 정상 | |
| O-14 | Tenant Admin | `./gradlew :arc-admin:test --tests "*.TenantAdminControllerTest"` | 대시보드 5개 정상 | |
| O-15 | CSV Export | `./gradlew :arc-admin:test --tests "*.ExportServiceTest"` | 데이터 완전성 | |
| O-16 | Admin Auth | `./gradlew :arc-admin:test --tests "*.AdminAuthHelperTest"` | 권한 체크 일관성 | |

### L5 일괄 실행

```bash
./gradlew :arc-admin:test
```

---

## 카테고리 6: E2E 회귀 (L6)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| E-01 | API 회귀 플로우 | `./gradlew :arc-web:test -PincludeIntegration --tests "*.ApiRegressionFlowIntegrationTest"` | Auth→Chat→Stream→MCP→Guard 전체 | |
| E-02 | Slack 사용자 여정 | `./gradlew :arc-slack:test --tests "*.SlackUserJourneyScenarioTest" --tests "*.SlackCrossToolAndProactiveE2ETest"` | 멘션→응답→스레드→슬래시 | |
| E-03 | 스케줄러 전체 흐름 | `./gradlew :arc-core:test --tests "*.DynamicSchedulerServiceTest" --tests "*.DynamicSchedulerServiceEnhancementsTest"` | CRUD→실행→재시도→이력 | |
| E-04 | Circuit Breaker 통합 | `./gradlew :arc-core:test --tests "*.CircuitBreakerIntegrationTest" --tests "*.ExecutorFallbackIntegrationTest"` | open→fallback→half-open→close | |
| E-05 | Plan-Execute 통합 | `./gradlew :arc-core:test --tests "*.PlanExecuteLoopExecutorTest"` | 계획→검증→실행 E2E | |

### L6 일괄 실행

```bash
./gradlew test -PincludeIntegration
```

---

## 카테고리 7: 성능 테스트 (P)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| P-01 | 동시 요청 제한 | `./gradlew :arc-core:test --tests "*.ConcurrencyTimeoutTest"` | maxConcurrentRequests 준수 | |
| P-02 | 메모리 동시성 | `./gradlew :arc-core:test --tests "*.MemoryStoreConcurrencyTest"` | 동시 접근 데이터 무결성 | |
| P-03 | 대화 관리 동시성 | `./gradlew :arc-core:test --tests "*.ConcurrencyTest"` | 세션별 격리 | |
| P-04 | Slack 동시 이벤트 | `./gradlew :arc-slack:test --tests "*.SlackEventControllerConcurrencyTest"` | 세마포어 기반 제한 | |
| P-05 | 토큰 추정 캐시 | `./gradlew :arc-core:test --tests "*.ConversationMessageTrimmerTest"` | 증분 캐시 히트율 | |
| P-06 | Ring Buffer 처리량 | `./gradlew :arc-admin:test --tests "*.MetricRingBufferTest"` | 300 동접 이벤트 감당 | |

### 성능 일괄 실행

```bash
./gradlew :arc-core:test --tests "*.ConcurrencyTimeoutTest" --tests "*.MemoryStoreConcurrencyTest" \
  :arc-slack:test --tests "*.SlackEventControllerConcurrencyTest" \
  :arc-admin:test --tests "*.MetricRingBufferTest"
```

---

## 카테고리 8: 프롬프트 & LLM 행동 테스트 (S - Semantic)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| SM-01 | 에이전트 모드 분류 | `./gradlew :arc-core:test --tests "*.AgentModeResolverTest"` | STANDARD/REACT/PLAN 정확 분류 | |
| SM-02 | 도구 선택 정확도 | `./gradlew :arc-core:test --tests "*.ToolPreparationPlannerMatrixTest"` | 매트릭스 기반 검증 | |
| SM-03 | 재시도 전략 | `./gradlew :arc-core:test --tests "*.RetryExecutorMatrixTest"` | 에러 후 재시도 패턴 | |
| SM-04 | 구조화된 출력 복구 | `./gradlew :arc-core:test --tests "*.StructuredResponseRepairerTest"` | 잘못된 JSON 자동 수정 | |
| SM-05 | RAG 관련성 분류 | `./gradlew :arc-core:test --tests "*.RagRelevanceClassifierTest"` | 도구 vs RAG 우선순위 | |

### 프롬프트 일괄 실행

```bash
./gradlew :arc-core:test --tests "*.AgentModeResolverTest" --tests "*.ToolPreparationPlannerMatrixTest" \
  --tests "*.RetryExecutorMatrixTest" --tests "*.StructuredResponseRepairerTest"
```

---

## 카테고리 9: Fuzz 테스트 (안정성)

| ID | 테스트 | 명령어 | 판정 기준 | 비고 |
|----|--------|--------|----------|------|
| FZ-01 | RunContext 퍼징 | `./gradlew :arc-core:test --tests "*.AgentRunContextManagerFuzzTest"` | 랜덤 상태 변이 안전 | |
| FZ-02 | ToolCallback 퍼징 | `./gradlew :arc-core:test --tests "*.ArcToolCallbackAdapterFuzzTest"` | 비정상 인자 파싱 안전 | |
| FZ-03 | Tool 인자 파서 퍼징 | `./gradlew :arc-core:test --tests "*.ToolArgumentParserFuzzTest"` | JSON 엣지 케이스 | |
| FZ-04 | Stream 이벤트 마커 | `./gradlew :arc-core:test --tests "*.StreamEventMarkerFuzzTest"` | 상태 전이 안전 | |

### Fuzz 일괄 실행

```bash
./gradlew :arc-core:test --tests "*.AgentRunContextManagerFuzzTest" --tests "*.ArcToolCallbackAdapterFuzzTest" \
  --tests "*.ToolArgumentParserFuzzTest" --tests "*.StreamEventMarkerFuzzTest"
```

---

## 전체 실행 요약

```bash
# ═══ 0. 컴파일 (0 warnings 필수) ═══
./gradlew compileKotlin compileTestKotlin

# ═══ L1. 보안 (가장 중요) ═══
./gradlew :arc-core:test -PincludeHardening
./gradlew :arc-core:test :arc-web:test -PincludeSafety

# ═══ L2. 에이전트 코어 ═══
./gradlew :arc-core:test

# ═══ L3. 통합 & 채널 ═══
./gradlew :arc-web:test :arc-slack:test

# ═══ L4. 비용 & 자원 (L2에 포함됨) ═══
# 별도 확인 시: ./gradlew :arc-core:test --tests "*.CostAwareModelRouterTest" --tests "*.StepBudgetTrackerTest"

# ═══ L5. 운영 (Admin) ═══
./gradlew :arc-admin:test

# ═══ L6. E2E 회귀 ═══
./gradlew test -PincludeIntegration

# ═══ P. 성능 (L2/L3에 포함됨) ═══
# 별도 확인 시: 위 성능 일괄 실행 참조

# ═══ S. 적대적 강화 (API 키 필요) ═══
GEMINI_API_KEY=$GEMINI_API_KEY ./gradlew :arc-core:test --tests "*.AdversarialRedTeamTest" -PincludeIntegration
```

---

## 승인 판정 기준

| 등급 | 조건 | 판정 |
|------|------|------|
| **PASS** | L1~L5 전체 통과 + L6 주요 항목 통과 | 프로덕션 배포 승인 |
| **CONDITIONAL** | L1~L3 통과 + L4/L5 일부 실패 | 조건부 승인 (실패 항목 추적) |
| **FAIL** | L1 또는 L2 실패 | 배포 차단 |

**총 테스트 항목 수**: 보안 19 + 기능 20 + 통합 15 + 비용 7 + 운영 16 + E2E 5 + 성능 6 + 프롬프트 5 + Fuzz 4 = **97개**

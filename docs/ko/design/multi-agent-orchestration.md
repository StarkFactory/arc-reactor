# 멀티에이전트 오케스트레이션 설계

## 개요

Arc Reactor 위에 경량 서브에이전트를 N개 양산하여, 에이전트끼리 자율적으로 상호작용하며 복합 작업을 수행하는 시스템.

**목표**: 사용자가 Web UI에서 에이전트를 등록/조합 → 실행 → 에이전트끼리 대화하며 작업 수행 → 결과 종합

## 핵심 아키텍처

```
사용자 (Aslan-Verse-Web / Slack / API)
  │
  ▼
Supervisor Agent (총괄)
  ├─ 작업 분석 → 필요한 에이전트 선택
  ├─ 서브에이전트 병렬/순차 실행
  ├─ 라운드별 결과 수집 + 수렴 판단
  └─ 최종 결과 종합 → 사용자에게 반환
  │
  ├──→ Agent A (HR 전문)      ──┐
  ├──→ Agent B (Dev 전문)      ──┤── AgentMessageBus (상호작용)
  ├──→ Agent C (PM 전문)       ──┤
  └──→ Agent D (법무 전문)      ──┘
  │
  ▼
Arc Reactor (공유 인프라)
  ├─ LLM API (Gemini/OpenAI/Anthropic)
  ├─ Guard Pipeline (보안)
  ├─ Tools (Jira/Confluence/Bitbucket)
  └─ Memory + ConversationManager
```

## 기존 인프라 활용

| 컴포넌트 | 상태 | 위치 |
|---------|------|------|
| `SupervisorAgent` | 구현됨 | `arc-core/.../multiagent/SupervisorAgent.kt` |
| `AgentRegistry` | 구현됨 (인메모리) | `arc-core/.../multiagent/AgentRegistry.kt` |
| `AgentSpec` | 구현됨 | `arc-core/.../multiagent/AgentSpec.kt` |
| `AgentMessageBus` | 구현됨 | `arc-core/.../multiagent/AgentMessageBus.kt` |
| `AgentExecutor` | 구현됨 | 요청마다 다른 시스템프롬프트/도구 지원 |
| `PersonaStore` | 구현됨 (JDBC) | DB 기반 페르소나 관리 |

## 에이전트 정의

```kotlin
data class AgentSpec(
    val id: String,                    // "hr-agent"
    val name: String,                  // "HR 전문가"
    val description: String,           // "인사, 연차, 복지, 온보딩 담당"
    val toolNames: List<String>,       // ["jira_search", "confluence_answer_question"]
    val keywords: List<String>,        // ["인사", "연차", "채용", "온보딩"]
    val systemPromptOverride: String?, // 페르소나 시스템 프롬프트
    val mode: AgentMode,               // REACT (기본)
    val personaId: String?             // DB 페르소나 바인딩 (선택)
)
```

**에이전트 하나 = 설정 한 줄. 프로세스가 아님.**

## 리소스 소비 분석

### 에이전트당 메모리

| 항목 | 크기 |
|------|------|
| 시스템 프롬프트 | ~2KB |
| 대화 히스토리 (10라운드) | ~50KB |
| 도구 결과 버퍼 | ~20KB |
| 코루틴 스택 | ~1KB |
| **합계** | **~73KB** |

- 에이전트 10개 동시 = ~730KB (JVM 힙 1GB 대비 0.07%)
- 에이전트 30개 동시 = ~2.2MB (무시 가능)

### LLM API 비용

| 시나리오 | API 콜 | 토큰 | Gemini Flash 비용 |
|---------|--------|------|-----------------|
| 5 에이전트 × 5 라운드 | 25 | ~100K | ~$0.008 |
| 5 에이전트 × 10 라운드 | 50 | ~300K | ~$0.02 |
| 10 에이전트 × 10 라운드 | 100 | ~800K | ~$0.06 |
| 10 에이전트 × 20 라운드 | 200 | ~2M | ~$0.15 |

### 동시성 한계

| 제약 | 한계 | 대응 |
|------|------|------|
| LLM API RPM | Gemini ~1000/분 | 에이전트 50개 병렬도 가능 |
| Arc Reactor 세마포어 | `maxConcurrentRequests` | 설정 조정 (기본 20) |
| JVM 메모리 | 1GB 힙 기준 ~300개 | 사실상 무제한 |

## 실행 모드

### 1. 순차 위임 (Sequential Delegation)
```
Supervisor → Agent A → 결과 → Agent B(A 결과 참고) → 결과 → 종합
```
- 단순, 예측 가능
- 에이전트 간 의존성 있을 때

### 2. 병렬 위임 (Parallel Delegation)
```
Supervisor → ┌ Agent A ──→ 결과 ┐
             ├ Agent B ──→ 결과 ├→ 종합
             └ Agent C ──→ 결과 ┘
```
- 빠름 (3초 vs 9초)
- 독립적인 작업일 때

### 3. 토론 모드 (Discussion / Round-Robin)
```
라운드 1: A 발언 → B 발언 → C 발언
라운드 2: A(B,C 읽고 수정) → B(A,C 읽고 수정) → C(A,B 읽고 수정)
라운드 N: Supervisor "합의 도달" 판단 → 종료
```
- 에이전트끼리 사람처럼 대화
- 브레인스토밍, 의사결정에 적합

### 4. 체인 모드 (Pipeline)
```
Agent A(분석) → Agent B(설계) → Agent C(구현) → Agent D(리뷰) → 완료
```
- 순서가 정해진 워크플로
- 각 에이전트가 이전 결과를 입력으로 받음

## 안전장치

| 위험 | 대응 |
|------|------|
| 무한 루프 | `maxRounds` 제한 (기본 10, 최대 30) |
| 컨텍스트 폭발 | 라운드별 요약 (summarizer) — 전체 대화 대신 요약본 전달 |
| 비용 폭주 | `StepBudgetTracker` — 총 토큰 예산 초과 시 중단 |
| 수렴 실패 | Supervisor가 "[DONE]" 마커 감지 또는 maxRounds 도달 시 강제 종합 |
| 보안 | Guard 파이프라인은 모든 에이전트에 공통 적용 |

## 구현 로드맵

### Phase 1: AgentSpec DB 관리 + Admin API
- `JdbcAgentSpecStore` 구현 (현재 인메모리 → DB)
- Admin API: `GET/POST/PUT/DELETE /api/admin/agents`
- Flyway 마이그레이션: `agent_specs` 테이블
- **난이도**: 작음 (기존 패턴 그대로)

### Phase 2: 오케스트레이션 엔진
- `OrchestratorService` — 실행 모드 (순차/병렬/토론/체인) 지원
- 라운드별 요약 (summarizer agent)
- 실행 상태 WebSocket 스트리밍 (진행률, 에이전트별 상태)
- **난이도**: 중간

### Phase 3: Web UI (Aslan-Verse-Web)
- 에이전트 등록/편집 화면
- 워크플로 빌더 (@xyflow/react 활용)
- 실행 뷰: 에이전트별 대화 실시간 스트리밍
- 결과 대시보드
- **난이도**: 큼

### Phase 4: Slack 멀티봇 바인딩
- `SlackMultiBotGateway` — N개 봇 동시 Socket Mode 연결
- 봇 → AgentSpec 바인딩
- **난이도**: 중간

## 사용 시나리오 예시

### 신입 온보딩
```
사용자: "김민수 신입 온보딩 시작해줘"
Supervisor → 병렬 실행:
  HR봇: Jira에 온보딩 태스크 생성, 입사 체크리스트 안내
  Dev봇: GitHub/Bitbucket 계정 생성 요청, 개발 환경 가이드
  총무봇: 장비 신청, 좌석 배정, 사원증 발급 안내
Supervisor → 종합:
  "김민수님 온보딩 3개 영역 진행:
   - HR: 체크리스트 Jira 이슈 ONBOARD-123 생성
   - Dev: 계정 요청 IT-456 생성, 환경 가이드 링크
   - 총무: 장비 신청 ADM-789 접수"
```

### 프로젝트 현황 분석
```
사용자: "프로젝트 X 전체 현황 분석해줘"
Supervisor → 병렬 실행:
  PM봇: Jira 스프린트 진행률, 지연 이슈 파악
  Dev봇: Bitbucket PR 현황, 코드 품질 체크
  QA봇: 테스트 커버리지, 버그 트렌드
Supervisor → 토론 1라운드:
  PM봇: "스프린트 70% 완료, 이슈 3개 지연"
  Dev봇: "PR 5개 미리뷰, 코드 품질 B+"
  QA봇: "커버리지 82%, 크리티컬 버그 0"
Supervisor → 종합 보고
```

## 기술 결정 사항

| 결정 | 선택 | 이유 |
|------|------|------|
| 에이전트 정의 저장 | PostgreSQL (JDBC) | Admin 관리, 영속성, 기존 패턴 |
| 에이전트 간 통신 | AgentMessageBus (인메모리) | 단일 인스턴스 전제, 지연 최소 |
| 실행 모드 | enum 기반 전략 패턴 | 확장 가능, 테스트 용이 |
| 컨텍스트 관리 | 라운드별 요약 | 토큰 폭발 방지 |
| 실시간 UI | WebSocket (STOMP or SSE) | 에이전트 대화 스트리밍 |

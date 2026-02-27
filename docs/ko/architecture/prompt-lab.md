# Prompt Lab: 자기 최적화 프롬프트 에이전트

> 사용자 피드백을 분석하여 개선된 프롬프트 후보를 자동 생성하고, 3단계 평가 엔진으로 비교 실험하는 자동화 파이프라인.

## 개요

Prompt Lab은 사용자 상호작용과 프롬프트 품질 사이의 피드백 루프를 닫는다. 수동 프롬프트 튜닝 대신 전체 사이클을 자동화한다:

```
                         Prompt Lab 파이프라인

  피드백         프롬프트          실험              보고서
  분석기   -->   후보 생성기  -->  오케스트레이터 -->  생성기
     |               |                 |                  |
 FeedbackStore   PromptTemplate    AgentExecutor     ExperimentStore
 (기존)          Store (기존)      (기존)
                     |                 |
                DRAFT 버전        3-Tier 평가
                                      |
                                   추천 결과
                                      |
                                 HITL 활성화
                                  (선택적)
```

**핵심 기능:**

1. `THUMBS_DOWN` 피드백 분석으로 프롬프트 약점 식별
2. LLM을 활용한 개선 프롬프트 후보 자동 생성
3. 동일 테스트 쿼리로 기존 vs 후보 프롬프트 비교 실험
4. 3단계 파이프라인으로 응답 평가 (구조, 규칙, LLM 판정)
5. 신뢰도 점수가 포함된 비교 보고서 생성
6. HITL 승인 게이트를 통한 추천 버전 활성화

## 활성화

```yaml
arc:
  reactor:
    prompt-lab:
      enabled: true
```

모든 빈은 `PromptLabConfiguration`에서 `@ConditionalOnProperty(prefix = "arc.reactor.prompt-lab", name = ["enabled"], havingValue = "true")`로 등록된다.

## 패키지 구조

```
arc-core/.../promptlab/
  model/
    EvaluationTier.kt              # STRUCTURAL, RULES, LLM_JUDGE
    PromptLabModels.kt             # Experiment, Trial, Report 등
  eval/
    PromptEvaluator.kt             # 평가자 인터페이스
    StructuralEvaluator.kt         # Tier 1: JSON 구조 검증
    RuleBasedEvaluator.kt          # Tier 2: 결정적 규칙
    LlmJudgeEvaluator.kt           # Tier 3: LLM 의미 판정
    EvaluationPipeline.kt          # 순차 fail-fast 오케스트레이션
  analysis/
    FeedbackAnalyzer.kt            # 피드백 기반 약점 식별
    PromptCandidateGenerator.kt    # LLM 기반 프롬프트 개선
  hook/
    ExperimentCaptureHook.kt       # AfterAgentComplete: 메타데이터 캡처
  ExperimentStore.kt               # 인터페이스 + InMemoryExperimentStore
  ExperimentOrchestrator.kt        # 실험 실행 엔진
  ReportGenerator.kt               # 비교 보고서 합성
  PromptLabScheduler.kt            # Cron 기반 자동 최적화
  PromptLabProperties.kt           # 설정 프로퍼티
  autoconfigure/
    PromptLabConfiguration.kt      # Bean 등록

arc-web/.../controller/
  PromptLabController.kt           # REST API (ADMIN 전용)
```

## 3단계 평가 엔진

각 응답은 3개 Tier를 순차 통과한다. 하위 Tier 실패 시 상위 Tier는 건너뛴다 (fail-fast).

```
응답 --> [Tier 1: 구조 검증] --> [Tier 2: 규칙 검사] --> [Tier 3: LLM 판정]
          무료, 즉시              무료, 즉시              유료, 느림
```

### Tier 1: 구조 평가기 (StructuralEvaluator)

응답 구조를 검증한다:

- **JSON 파싱 가능**: 유효한 JSON인가?
- **필수 필드**: `type` (`answer|error|action|briefing|clarification|search` 중 하나)과 `message` (`briefing`은 `summary`) 포함 여부
- **점수**: `1.0` (유효 JSON + 모든 필드), `0.5` (평문 응답 — pass), `0.3` (JSON이나 필수 필드 누락 — fail)

### Tier 2: 규칙 기반 평가기 (RuleBasedEvaluator)

eval-testing assertion에서 포팅된 결정적 규칙:

| 규칙 | 조건 | 검사 내용 |
|------|------|----------|
| 짧은 답변 | 검색 인텐트 + type=answer | 메시지 50자 이상 |
| 액션 확인 | 변형 인텐트 + success=true | 확인 문구 포함 |
| 에러 품질 | type=error | suggestions 또는 메시지 20자 이상 |
| 질문만 하는 패턴 | type=clarification | 질문만으로 끝나지 않아야 함 |

Regex 패턴은 `companion object`에 컴파일하여 핫 패스 할당을 방지.

### Tier 3: LLM 판정 평가기 (LlmJudgeEvaluator)

판정 LLM을 호출하여 rubric 기반 점수를 매긴다:

- **기본 rubric**: Helpfulness (25점), Accuracy (25점), Completeness (25점), Safety (25점)
- **커스텀 rubric**: `EvaluationConfig.customRubric`으로 오버라이드 가능
- **예산 제어**: `AtomicInteger`로 누적 토큰 추적. `llmJudgeBudgetTokens` 초과 시 `score=0.5`, reason="Budget exhausted" 반환
- **동일 모델 경고**: `model == judgeModel`이면 경고 로그 출력 (차단하지 않음)

**LLM 응답 형식:**

```json
{"pass": true, "score": 0.85, "reason": "Response is helpful and accurate..."}
```

### 파이프라인 설정

```kotlin
data class EvaluationConfig(
    val structuralEnabled: Boolean = true,
    val rulesEnabled: Boolean = true,
    val llmJudgeEnabled: Boolean = true,
    val llmJudgeBudgetTokens: Int = 100_000,
    val customRubric: String? = null
)
```

## 피드백 분석

`FeedbackAnalyzer`는 `THUMBS_DOWN` 피드백을 처리하여 프롬프트 약점을 식별한다:

```kotlin
val analysis = feedbackAnalyzer.analyze(
    templateId = "my-template",
    since = Instant.parse("2026-01-01T00:00:00Z"),  // 선택: Cron 증분 분석용
    maxSamples = 50
)
// analysis.weaknesses: [PromptWeakness(category, description, frequency, exampleQueries)]
// analysis.sampleQueries: [TestQuery(query, intent, domain)]
```

**프로세스:**

1. `FeedbackStore`에서 `templateId`로 필터링된 부정 피드백 조회
2. 피드백(쿼리 + 응답 + 코멘트)을 LLM에 전송하여 패턴 분석
3. LLM이 약점 분류: `short_answer`, `missing_sources`, `incorrect_info`, `no_tool_usage`, `missing_context`, `off_topic`, `poor_formatting`, `other`
4. 고유 쿼리를 `TestQuery` 객체로 추출 (intent/domain 유지)

**중요:** 피드백에 `templateId`가 설정되어 있어야 분석이 가능하다. `FeedbackMetadataCaptureHook`이 `HookContext.metadata`에서 `promptTemplateId`를 자동 캡처한다 (`ChatController`가 설정). `template_id`가 없는 기존 피드백은 분석 대상에 포함되지 않는다.

## 프롬프트 후보 생성

`PromptCandidateGenerator`가 개선된 프롬프트 버전을 생성한다:

```kotlin
val candidateIds = candidateGenerator.generate(
    templateId = "my-template",
    analysis = analysis,
    candidateCount = 3
)
// 반환: 생성된 PromptVersion ID 목록 (DRAFT 상태)
```

**프로세스:**

1. 템플릿의 현재 활성 버전 조회
2. 메타 프롬프트 구성: 현재 시스템 프롬프트, 식별된 약점 (카테고리, 빈도, 예시), 개선 지침
3. LLM에 N개의 다양한 후보 요청 (각각 다른 개선 전략 사용)
4. 각 후보를 `PromptTemplateStore`에 새 `PromptVersion`으로 저장
5. changeLog 기록: "Auto-generated by Prompt Lab: {weakness summary}"

## 실험 라이프사이클

### 상태

```
PENDING --> RUNNING --> COMPLETED
                   \--> FAILED
                   \--> CANCELLED
```

### 실험 모델

```kotlin
data class Experiment(
    val id: String,
    val name: String,
    val templateId: String,
    val baselineVersionId: String,       // 현재 ACTIVE 버전
    val candidateVersionIds: List<String>, // 비교할 후보 버전들
    val testQueries: List<TestQuery>,
    val evaluationConfig: EvaluationConfig,
    val model: String? = null,           // 평가 대상 LLM
    val judgeModel: String? = null,      // Tier 3 판정 LLM
    val temperature: Double = 0.3,
    val repetitions: Int = 1,            // 분산 측정용 반복 횟수
    val autoGenerated: Boolean = false,
    val status: ExperimentStatus = PENDING,
    // ... 타임스탬프, 에러 정보
)
```

### 실행 흐름

`ExperimentOrchestrator.execute()`:

1. 실험이 `PENDING` 상태인지 확인 후 `RUNNING`으로 전환
2. 각 버전 (baseline + 후보)에 대해:
   - 각 테스트 쿼리에 대해:
     - 각 반복에 대해:
       - 해당 버전의 시스템 프롬프트로 `AgentCommand` 구성
       - `AgentExecutor.execute()` 호출
       - `EvaluationPipeline`으로 응답 평가
       - 응답, 평가 결과, 토큰 사용량, 소요 시간을 `Trial`에 기록
3. 모든 Trial을 `ExperimentStore`에 저장
4. `ReportGenerator`로 보고서 생성
5. `COMPLETED`로 전환 (에러 시 `FAILED`)

**타임아웃:** `withTimeout(experimentTimeoutMs)` 적용 (기본: 10분).

### 자동 파이프라인

`ExperimentOrchestrator.runAutoPipeline()`이 모든 단계를 결합:

```
피드백 분석 --> 후보 생성 --> 실험 생성 --> 실행 --> 보고서
```

부정 피드백 수가 `minNegativeFeedback` 임계값 (기본: 5) 미만이면 건너뛴다.

## 보고서 생성

`ReportGenerator`가 실험 결과를 `ExperimentReport`로 합성한다:

### 버전별 요약

버전별 집계 메트릭:

| 메트릭 | 설명 |
|--------|------|
| `passRate` | 모든 평가 통과한 Trial 수 / 전체 Trial |
| `avgScore` | 전체 Trial의 평균 평가 점수 |
| `avgDurationMs` | 평균 응답 시간 |
| `totalTokens` | 누적 토큰 사용량 |
| `tierBreakdown` | Tier별 통과율 및 평균 점수 |
| `toolUsageFrequency` | 도구명 -> 사용 횟수 |
| `errorRate` | 실패 Trial / 전체 Trial |

### 추천

가중 점수로 최고 버전 선정: `passRate * 0.6 + avgScore * 0.4`

| 신뢰도 | 조건 |
|--------|------|
| `HIGH` | 최고 버전과 baseline의 통과율 차이 >10% |
| `MEDIUM` | 5-10% 차이 |
| `LOW` | <5% 차이 또는 데이터 부족 |

추천에 포함되는 정보:
- `improvements`: 개선된 부분 (통과율, 점수, 속도)
- `warnings`: 악화된 부분 (에러율, 토큰 사용량)

## Hook 통합

`ExperimentCaptureHook` (order=270)이 Trial 실행 중 실험 메타데이터를 캡처한다:

- **활성화 조건**: `context.metadata`에 `promptlab.experimentId`와 `promptlab.versionId`가 있을 때만
- **저장**: `ConcurrentHashMap`, 1시간 TTL, 최대 10,000 엔트리
- **에러 정책**: `failOnError = false` (fail-open)
- **목적**: 관찰성 및 향후 간접 트리거 시나리오 지원

`ExperimentOrchestrator`가 주입하는 메타데이터 키:

```kotlin
ExperimentCaptureHook.EXPERIMENT_ID_KEY  // "promptlab.experimentId"
ExperimentCaptureHook.VERSION_ID_KEY     // "promptlab.versionId"
ExperimentCaptureHook.RUN_ID_KEY         // "promptlab.runId"
```

## Cron 스케줄링

`PromptLabScheduler`로 주기적 자동 최적화를 활성화:

```yaml
arc:
  reactor:
    prompt-lab:
      schedule:
        enabled: true
        cron: "0 0 2 * * *"          # 매일 새벽 2시
        template-ids:                  # 비면 전체 템플릿 대상
          - "template-1"
          - "template-2"
```

**동작:**

- 설정된 각 템플릿에 대해 `runAutoPipeline()` 실행
- `lastRunTime`을 추적하여 `since` 파라미터 전달 (증분 분석)
- `AtomicBoolean` 잠금으로 동시 실행 방지
- `Dispatchers.IO`에서 실행하여 Spring 스케줄러 스레드 블로킹 방지

## REST API

모든 엔드포인트는 Admin 접근 필요. 기본 경로: `/api/prompt-lab`

### 실험 CRUD

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/experiments` | 실험 생성 (제한 검증 포함) |
| `GET` | `/experiments` | 실험 목록 (필터: `status`, `templateId`) |
| `GET` | `/experiments/{id}` | 실험 상세 조회 |
| `POST` | `/experiments/{id}/run` | 실험 시작 (비동기) |
| `POST` | `/experiments/{id}/cancel` | 실행 중 실험 취소 |
| `GET` | `/experiments/{id}/status` | 실험 상태 폴링 |
| `GET` | `/experiments/{id}/trials` | Trial 데이터 조회 |
| `GET` | `/experiments/{id}/report` | 비교 보고서 조회 |
| `DELETE` | `/experiments/{id}` | 실험 삭제 |

### 자동화

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/auto-optimize` | 풀 자동 파이프라인 트리거 (비동기) |
| `POST` | `/analyze` | 피드백 분석만 실행 |
| `POST` | `/experiments/{id}/activate` | 추천 버전 활성화 (HITL 게이트) |

### 입력 검증

컨트롤러가 실험 생성 전 설정 제한을 적용:

- `testQueries.size <= maxQueriesPerExperiment` (기본: 100)
- `1 + candidateVersionIds.size <= maxVersionsPerExperiment` (기본: 10)
- `repetitions <= maxRepetitions` (기본: 5)
- 동시 실행 실험 수 `maxConcurrentExperiments` 초과 시 429 반환 (기본: 3)

### 예시: 실험 생성 및 실행

```bash
# 실험 생성
curl -X POST http://localhost:8080/api/prompt-lab/experiments \
  -H "Content-Type: application/json" \
  -d '{
    "name": "고객지원 프롬프트 개선",
    "templateId": "support-template",
    "baselineVersionId": "v1",
    "candidateVersionIds": ["v2", "v3"],
    "testQueries": [
      {"query": "비밀번호 재설정 방법", "intent": "account", "domain": "support"},
      {"query": "문서는 어디에?", "intent": "docs", "domain": "knowledge"}
    ]
  }'

# 실험 실행 (비동기)
curl -X POST http://localhost:8080/api/prompt-lab/experiments/{id}/run

# 상태 폴링
curl http://localhost:8080/api/prompt-lab/experiments/{id}/status

# 보고서 조회
curl http://localhost:8080/api/prompt-lab/experiments/{id}/report

# 추천 버전 활성화 (HITL)
curl -X POST http://localhost:8080/api/prompt-lab/experiments/{id}/activate
```

### 예시: 자동 최적화

```bash
curl -X POST http://localhost:8080/api/prompt-lab/auto-optimize \
  -H "Content-Type: application/json" \
  -d '{"templateId": "support-template", "candidateCount": 3}'
```

## 설정 레퍼런스

| 프로퍼티 | 기본값 | 설명 |
|----------|--------|------|
| `arc.reactor.prompt-lab.enabled` | `false` | Prompt Lab 활성화 |
| `...max-concurrent-experiments` | `3` | 최대 동시 실행 실험 수 |
| `...max-queries-per-experiment` | `100` | 실험당 최대 테스트 쿼리 수 |
| `...max-versions-per-experiment` | `10` | 최대 버전 수 (baseline + 후보) |
| `...max-repetitions` | `5` | 최대 반복 횟수 |
| `...default-judge-model` | `null` | Tier 3 판정 기본 LLM |
| `...default-judge-budget-tokens` | `100,000` | 실험당 LLM 판정 토큰 예산 |
| `...experiment-timeout-ms` | `600,000` | 실험 타임아웃 (10분) |
| `...candidate-count` | `3` | 자동 생성 후보 수 |
| `...min-negative-feedback` | `5` | 자동 파이프라인 트리거 최소 부정 피드백 |
| `...schedule.enabled` | `false` | Cron 스케줄링 활성화 |
| `...schedule.cron` | `0 0 2 * * *` | Cron 표현식 (기본: 매일 새벽 2시) |
| `...schedule.template-ids` | `[]` | 대상 템플릿 (비면 전체) |

## Experiment Store

`InMemoryExperimentStore`가 자동 퇴거 기능이 있는 스레드 안전 저장소를 제공:

- **스레드 안전**: `ConcurrentHashMap` + `CopyOnWriteArrayList`
- **용량**: 기본 1,000개 실험
- **퇴거**: 용량 초과 시 가장 오래된 종료 상태 실험 (`COMPLETED`, `FAILED`, `CANCELLED`) 퇴거
- **오버라이드**: `ExperimentStore` 인터페이스를 구현하고 빈으로 등록

## Guard 시스템과의 상호작용

Guard 파이프라인과 Prompt Lab은 독립적인 시스템이다. Guard 시스템 (`feat/enterprise-guard-system`)이 활성화된 경우:

- `ToolOutputSanitizer`와 `CanaryToken`이 baseline과 후보 Trial에 동일하게 적용
- 비교 공정성 유지 — 양쪽 모두 동일한 보안 제약 적용
- Guard는 피드백 수집이나 Prompt Lab이 사용하는 Hook 시스템에 영향 없음

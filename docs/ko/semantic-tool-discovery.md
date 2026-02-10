# 시맨틱 도구 탐색

## 한 줄 요약

**모든 도구를 LLM에 넘기지 않고, 임베딩 유사도로 요청에 관련된 도구만 동적으로 선택합니다.**

---

## 왜 시맨틱 도구 탐색인가?

에이전트에 도구가 많아지면 전부 LLM에 넘기는 건 문제가 됩니다:

```
도구 30개인 에이전트:
  getWeather, searchWeb, calculator, sendEmail, readFile, writeFile,
  checkOrder, cancelOrder, processRefund, trackShipping, getBalance,
  createTicket, updateTicket, closeTicket, assignTicket, listTickets,
  queryDatabase, exportCsv, generateReport, scheduleTask, ...
```

- **토큰 낭비**: 모든 도구의 이름, 설명, 스키마가 토큰을 소비함
- **잘못된 도구 선택**: LLM이 긴 목록에서 관련 없는 도구를 선택할 수 있음
- **느린 응답**: 컨텍스트가 많으면 추론 시간이 길어짐

시맨틱 도구 탐색은 사용자 요청에 관련된 도구만 선택해서 해결합니다:

```
사용자: "서울 날씨 알려줘"

전체 도구 (30개) → SemanticToolSelector → [getWeather, searchWeb] (2개)

LLM에는 30개가 아닌 2개만 전달됩니다.
```

---

## 동작 원리

```
1. 시작 / 첫 요청
   모든 도구 설명을 일괄 임베딩하고 캐시
   "현재 날씨 조회"  → [0.12, -0.34, 0.56, ...]
   "환불 처리"       → [0.78, -0.11, 0.23, ...]
   ...
      ↓
2. 사용자 요청 도착
   사용자 프롬프트를 임베딩
   "날씨 알려줘" → [0.15, -0.31, 0.52, ...]
      ↓
3. 코사인 유사도 계산
   프롬프트 임베딩과 각 도구 임베딩 비교
   getWeather:     0.92  (높은 매칭)
   searchWeb:      0.71  (중간 매칭)
   processRefund:  0.08  (낮은 매칭)
   calculator:     0.12  (낮은 매칭)
      ↓
4. 임계값 필터링 + 정렬
   threshold = 0.3, maxResults = 10
   → [getWeather (0.92), searchWeb (0.71)]
      ↓
5. 선택된 도구만 LLM에 전달
   LLM은 getWeather와 searchWeb만 봄
```

### 캐시와 자동 갱신

- 도구 설명 임베딩은 `ConcurrentHashMap`에 캐시됨
- 매 요청마다 핑거프린트(모든 도구 이름 + 설명의 해시)를 계산
- 핑거프린트가 변경되면 (도구 추가, 제거, 설명 변경) 캐시가 자동 갱신됨
- 첫 요청에서만 임베딩 비용이 발생하고, 이후 요청은 캐시를 사용

---

## 설정

```yaml
arc:
  reactor:
    tool:
      selection:
        strategy: semantic          # all | keyword | semantic
        similarity-threshold: 0.3   # 최소 코사인 유사도 (0.0 ~ 1.0)
        max-results: 10             # 최대 선택 도구 수
```

### 전략 옵션

| 전략 | 동작 | EmbeddingModel 필요 |
|------|------|---------------------|
| `all` | 모든 도구를 LLM에 전달 (기본값) | 아니오 |
| `keyword` | ToolCategory 키워드 매칭 | 아니오 |
| `semantic` | 임베딩 코사인 유사도 기반 | 예 |

### 임계값 조정 가이드

- **0.1 ~ 0.2**: 매우 관대. 더 많은 도구 선택, 누락 적음, 토큰 소비 많음
- **0.3 ~ 0.4**: 균형. 대부분의 경우에 적합 (권장 시작점)
- **0.5 ~ 0.7**: 엄격. 매우 관련된 도구만 선택. 유용한 도구를 놓칠 위험
- **0.8+**: 너무 엄격. 관련 도구를 놓칠 가능성 높음

---

## 사전 요구사항

시맨틱 전략은 `EmbeddingModel` 빈이 필요합니다. 보통 Spring AI 스타터에서 제공됩니다:

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
```

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
```

`EmbeddingModel` 빈이 없으면 `SemanticToolSelector`가 자동으로 `all` 전략으로 폴백하며 경고 로그를 출력합니다. 애플리케이션이 실패하지 않습니다.

---

## 사용법

### 기본 설정

설정만 하면 됩니다. 코드 변경은 필요 없습니다.

```yaml
arc:
  reactor:
    tool:
      selection:
        strategy: semantic
        similarity-threshold: 0.3
        max-results: 10
```

`SemanticToolSelector` 빈은 다음 조건이 충족되면 자동 설정됩니다:
1. `strategy`가 `semantic`으로 설정됨
2. `EmbeddingModel` 빈이 존재함

### 프로그래밍 방식 오버라이드

자체 `ToolSelector` 빈을 제공하면 자동 설정된 것을 대체할 수 있습니다:

```kotlin
@Component
class CustomToolSelector(
    private val embeddingModel: EmbeddingModel
) : ToolSelector {

    override suspend fun select(
        prompt: String,
        tools: List<ToolCallbackWrapper>
    ): List<ToolCallbackWrapper> {
        // 커스텀 로직
        // 예: 특정 중요 도구는 항상 포함
        val semanticResults = semanticSelect(prompt, tools)
        val criticalTools = tools.filter { it.name in setOf("emergency_stop") }
        return (criticalTools + semanticResults).distinctBy { it.name }
    }
}
```

`@ConditionalOnMissingBean` 덕분에 커스텀 빈이 기본 빈을 자동으로 대체합니다.

---

## 안전한 폴백

`SemanticToolSelector`는 에이전트를 절대 차단하지 않도록 설계되었습니다:

| 상황 | 동작 |
|------|------|
| `EmbeddingModel` 빈 없음 | `all` 전략으로 폴백 |
| 임베딩 API 호출 실패 | 모든 도구 반환 (경고 로그) |
| 모든 유사도가 임계값 미만 | 모든 도구 반환 (매칭 없음으로 판단) |
| 빈 도구 목록 | 빈 리스트 반환 |

원칙: **도구를 아예 안 주는 것보다, 많이 주는 게 낫다.**

---

## 키워드 전략과 비교

| | 키워드 (`ToolCategory`) | 시맨틱 (`SemanticToolSelector`) |
|---|---|---|
| **매칭 방식** | 정확한 키워드 매칭 | 임베딩 코사인 유사도 |
| **설정 노력** | 카테고리별 키워드 정의 필요 | 설정 불필요 (도구 설명 사용) |
| **다국어** | 각 언어별 키워드 추가 필요 | 자동으로 다국어 지원 |
| **"환불 문의"** | "환불" 키워드가 있어야 매칭 | processRefund (0.87), checkOrder (0.65) 매칭 |
| **외부 API 필요** | 아니오 | 예 (EmbeddingModel) |
| **비용** | 무료 | 고유 프롬프트당 임베딩 API 비용 |

**추천:**
- 도구가 적을 때 (10개 미만): `all` 사용 -- 필터링 불필요
- 중간 (10~30개)이고 카테고리가 명확: `keyword`가 잘 동작
- 많을 때 (30개 이상) 또는 다양한 도구: `semantic`이 최적

---

## 참고 코드

| 파일 | 설명 |
|------|------|
| [`SemanticToolSelector.kt`](../../src/main/kotlin/com/arc/reactor/tool/SemanticToolSelector.kt) | 임베딩 기반 도구 선택 |
| [`AgentProperties.kt`](../../src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt) | ToolSelectionProperties 설정 |
| [`ArcReactorAutoConfiguration.kt`](../../src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorAutoConfiguration.kt) | SemanticToolSelector 자동 설정 |
| [`ToolSelector.kt`](../../src/main/kotlin/com/arc/reactor/tool/ToolSelector.kt) | ToolSelector 인터페이스 |
| [`ToolCategory.kt`](../../src/main/kotlin/com/arc/reactor/tool/ToolCategory.kt) | 키워드 기반 카테고리 시스템 (비교용) |

# 응답 후처리

## 개요

응답 후처리 파이프라인은 에이전트 응답을 호출자에게 반환하기 전에 필터를 적용합니다. 핵심 에이전트 실행기를 수정하지 않고도 콘텐츠 변환, 길이 제한, 민감 정보 제거 등의 처리가 가능합니다.

필터는 **비스트리밍 `execute()` 결과에만** 적용됩니다. 스트리밍 응답(`executeStream()`)은 실시간으로 전달되므로 필터 체인을 거치지 않습니다.

---

## 아키텍처

```
LLM 응답 → 구조화 출력 검증 → 응답 필터 체인 → 메모리 저장 → Hook → 반환
```

필터 체인은 구조화 출력 검증(JSON/YAML) 이후, 다음 단계 이전에 실행됩니다:
- 대화 메모리 저장
- AfterAgentComplete Hook 실행
- 메트릭 기록

따라서 Hook과 메모리는 **필터링된** 콘텐츠를 받습니다.

---

## ResponseFilter 인터페이스

```kotlin
interface ResponseFilter {
    val order: Int get() = 100

    suspend fun filter(content: String, context: ResponseFilterContext): String
}

data class ResponseFilterContext(
    val command: AgentCommand,
    val toolsUsed: List<String>,
    val durationMs: Long
)
```

### 핵심 규칙

- **순서(Ordering)**: `order` 값이 낮을수록 먼저 실행됩니다. 내장 필터는 1-99, 커스텀 필터는 100 이상을 사용하세요.
- **Fail-open**: 필터가 예외를 던지면 로깅 후 건너뜁니다. 체인은 이전 콘텐츠로 계속 진행합니다.
- **CancellationException**: 항상 rethrow합니다 (구조적 동시성 보존).
- **멱등성**: 필터는 여러 번 적용해도 안전해야 합니다.

---

## 내장 필터

### MaxLengthResponseFilter

설정된 글자 수 제한을 초과하는 응답을 잘라냅니다.

```yaml
arc:
  reactor:
    response:
      max-length: 10000  # 0 = 무제한 (기본값)
```

잘림이 발생하면 응답 끝에 다음이 추가됩니다:
```
[Response truncated]
```

순서: `10` (커스텀 필터보다 먼저 실행)

---

## 설정

```yaml
arc:
  reactor:
    response:
      max-length: 0          # 최대 응답 글자 수. 0 = 무제한 (기본값)
      filters-enabled: true   # 필터 체인 활성화/비활성화 (기본값: true)
```

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `max-length` | `0` | 최대 응답 글자 수. 0 = 제한 없음 |
| `filters-enabled` | `true` | 필터 체인 마스터 스위치 |

---

## 커스텀 필터 예제

### 1. ResponseFilter 구현

```kotlin
class KeywordRedactionFilter(
    private val blocklist: Set<String>
) : ResponseFilter {
    override val order = 110  // 내장 필터 이후

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        var result = content
        for (keyword in blocklist) {
            result = result.replace(keyword, "[REDACTED]", ignoreCase = true)
        }
        return result
    }
}
```

### 2. Bean 등록

```kotlin
@Bean
fun keywordRedactionFilter(): ResponseFilter {
    return KeywordRedactionFilter(setOf("secret-project", "internal-code"))
}
```

`ResponseFilterChain`이 Spring의 `ObjectProvider<ResponseFilter>`를 통해 자동으로 필터를 수집합니다.

### 3. 체인 전체 교체 (선택사항)

기본 체인을 완전히 교체하려면:

```kotlin
@Bean
fun responseFilterChain(): ResponseFilterChain {
    return ResponseFilterChain(listOf(
        MaxLengthResponseFilter(maxLength = 5000),
        KeywordRedactionFilter(setOf("secret"))
    ))
}
```

`@ConditionalOnMissingBean`이 사용되므로 커스텀 Bean이 우선합니다.

---

## 스트리밍 동작

응답 필터는 스트리밍 응답에 **적용되지 않습니다**. 이유는 다음과 같습니다:
1. 스트리밍은 토큰을 점진적으로 전달하므로, 스트림이 끝날 때까지 전체 콘텐츠를 알 수 없습니다
2. 스트림 도중에 필터를 적용하면 일관성 없는 결과가 나올 수 있습니다
3. 성능: 스트리밍은 저지연 전달을 우선합니다

스트리밍 전용 처리가 필요하면 `AfterAgentCompleteHook`을 사용하세요.

---

## 필터 체인 실행 흐름

```
입력 콘텐츠
    │
    ▼
┌─────────────────────────┐
│ MaxLengthResponseFilter │  order=10
│   (필요 시 잘라냄)       │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  커스텀 필터 A           │  order=100
│   (예: 키워드 제거)      │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│  커스텀 필터 B           │  order=200
│   (예: 푸터 추가)        │
└────────────┬────────────┘
             │
             ▼
        필터링된 콘텐츠
```

필터가 실패하면 체인은 에러를 로깅하고 **변경되지 않은 콘텐츠**를 다음 필터에 전달합니다.

# 도구(Tool) 가이드

## 도구란?

에이전트가 **외부 세계와 상호작용하는 수단**입니다.

LLM은 텍스트만 생성할 수 있습니다. 계산, API 호출, 파일 읽기 같은 실제 작업은 못 합니다.
도구를 주면 LLM이 "이 도구를 써야겠다"고 판단하고 호출합니다.

```
사용자: "서울 날씨 알려줘"

[에이전트]
  LLM: "날씨 도구를 호출해야겠다"
  → getWeather({city: "Seoul"}) 호출
  → "Seoul: Sunny, 22°C"
  LLM: "서울은 현재 맑음, 22도입니다."
```

도구가 없으면 LLM은 학습된 지식으로만 답변합니다 (부정확할 수 있음).
도구가 있으면 **실시간 데이터와 실제 작업**이 가능해집니다.

---

## 도구의 3가지 종류

Arc Reactor에서 도구를 만드는 방법은 3가지입니다.

### 1. LocalTool — Spring AI 어노테이션 방식 (권장)

가장 간단합니다. 클래스에 `@Tool` 어노테이션을 붙이면 자동으로 도구가 됩니다.

```kotlin
@Component
class WeatherTool(
    private val weatherApi: WeatherApiClient  // Spring DI 주입 가능
) : LocalTool {

    override val category = DefaultToolCategory.SEARCH  // 도구 분류 (선택)

    @Tool(description = "도시의 현재 날씨를 조회합니다")
    fun getWeather(
        @ToolParam(description = "도시 이름 (예: Seoul)") city: String
    ): String {
        return weatherApi.getCurrentWeather(city)
    }

    @Tool(description = "일기예보를 조회합니다")
    fun getForecast(
        @ToolParam(description = "도시 이름") city: String,
        @ToolParam(description = "일수 (1~7)") days: Int
    ): String {
        return weatherApi.getForecast(city, days)
    }
}
```

**특징:**
- `@Component`만 붙이면 자동 등록 (별도 설정 불필요)
- 메서드 시그니처에서 JSON 스키마가 자동 생성됨
- Spring DI로 Service, Repository 주입 가능
- 하나의 클래스에 여러 `@Tool` 메서드 정의 가능
- `category`로 도구 분류 가능 (ToolSelector가 사용)

> 예시 코드: [`WeatherTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/WeatherTool.kt)

---

### 2. ToolCallback — 직접 구현 방식

JSON 스키마를 직접 작성하고, `call()` 메서드를 구현합니다.

```kotlin
@Component
class CalculatorTool : ToolCallback {

    override val name = "calculator"
    override val description = "수학 계산을 수행합니다"

    override val inputSchema = """
        {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "계산식 (예: 3 + 5)"
            }
          },
          "required": ["expression"]
        }
    """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val expression = arguments["expression"] as? String
            ?: return "Error: expression is required"
        // 계산 로직
        return evaluate(expression)
    }
}
```

**특징:**
- 스키마를 직접 제어 (세밀한 설정 가능)
- `suspend fun` — 비동기 실행 가능
- Spring DI 없이도 사용 가능 (단위 테스트 쉬움)
- `@Component` 붙이면 자동 등록, 안 붙이면 수동 등록

**언제 쓰나:**
- 스키마를 세밀하게 제어하고 싶을 때
- suspend 함수가 필요할 때 (외부 API 비동기 호출)
- 프레임워크 독립적인 도구를 만들 때

> 예시 코드: [`CalculatorTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/CalculatorTool.kt), [`DateTimeTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/DateTimeTool.kt)

---

### 3. MCP 도구 — 외부 서버에서 가져오는 방식

[MCP(Model Context Protocol)](https://modelcontextprotocol.io/)는 외부 서버가 도구를 제공하는 표준 프로토콜입니다.

```bash
# 관리자 API로 MCP 서버 등록
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "filesystem",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    },
    "autoConnect": true
  }'
```

**특징:**
- 도구 구현이 외부 서버에 있음 (코드 작성 불필요)
- 오픈소스 MCP 서버가 많음 (파일, DB, GitHub, Slack 등)
- 연결하면 도구가 자동으로 등록됨
- STDIO(로컬 프로세스)와 SSE(HTTP) 전송 방식 지원

**동작 원리:**
```
에이전트 → McpToolCallback.call() → MCP 프로토콜 → 외부 서버 → 결과
```

내부적으로 `McpToolCallback`이 MCP 도구를 `ToolCallback` 인터페이스로 감싸서,
에이전트 입장에서는 로컬 도구와 동일하게 사용합니다.

---

## 3가지 비교

| | LocalTool | ToolCallback | MCP 도구 |
|---|---|---|---|
| **구현 위치** | 같은 프로젝트 | 같은 프로젝트 | 외부 서버 |
| **스키마 생성** | 자동 (`@Tool` 어노테이션) | 수동 (JSON 작성) | 자동 (서버가 제공) |
| **Spring DI** | 가능 | 가능 | 해당 없음 |
| **비동기** | 불가 (Spring AI 제약) | 가능 (`suspend fun`) | 가능 |
| **등록 방법** | `@Component` | `@Component` 또는 수동 | `mcpManager.connect()` |
| **사용 시점** | 대부분의 경우 | 세밀한 제어 필요 시 | 외부 서비스 연결 시 |

**추천:** 대부분의 경우 **LocalTool** 방식을 쓰세요. 가장 간단하고 Spring 생태계와 잘 맞습니다.

---

## 에이전트가 도구를 사용하는 흐름

```
1. 도구 수집
   LocalTool 목록 (@Component로 자동 발견)
   + ToolCallback 목록 (@Component로 자동 발견)
   + MCP 도구 목록 (McpManager에서 가져옴)
      ↓
2. 도구 필터링 (ToolSelector)
   사용자 프롬프트를 분석해서 관련 도구만 선택
   예: "날씨 알려줘" → SEARCH 카테고리 도구만 선택
      ↓
3. LLM에 도구 목록 전달
   도구 이름 + 설명 + 스키마가 LLM 컨텍스트에 포함됨
      ↓
4. LLM이 도구 호출 결정
   "getWeather를 city=Seoul로 호출해야겠다"
      ↓
5. 도구 실행
   BeforeToolCall 훅 → call() 실행 → AfterToolCall 훅
      ↓
6. 결과를 LLM에 전달
   LLM이 도구 결과를 바탕으로 최종 응답 생성
```

---

## 도구 분류 (ToolCategory)

도구가 많아지면 모든 도구를 LLM에 넘기는 건 비효율적입니다.
`ToolCategory`를 지정하면 **사용자 프롬프트와 관련된 도구만** LLM에 전달합니다.

```kotlin
class OrderTool : LocalTool {
    override val category = DefaultToolCategory.SEARCH  // "검색", "조회" 키워드에 매칭

    @Tool(description = "주문 조회")
    fun getOrder(orderId: String): String { ... }
}
```

기본 제공 카테고리:

| 카테고리 | 매칭 키워드 |
|---------|-----------|
| `SEARCH` | 검색, search, 찾아, find, 조회, query |
| `CREATE` | 생성, create, 만들어, 작성, write |
| `ANALYZE` | 분석, analyze, 요약, summary, 리포트 |
| `COMMUNICATE` | 전송, send, 메일, email, 알림, notify |
| `DATA` | 데이터, data, 저장, save, 업데이트 |

`category = null`이면 항상 선택됩니다 (필터링 대상 아님).

커스텀 카테고리도 만들 수 있습니다:

```kotlin
object FinanceCategory : ToolCategory {
    override val name = "FINANCE"
    override val keywords = setOf("결제", "payment", "환불", "refund", "잔액", "balance")
}

class PaymentTool : LocalTool {
    override val category = FinanceCategory
    ...
}
```

---

## 도구 등록 방법 정리

### 자동 등록 (권장)

`@Component`만 붙이면 Spring이 자동으로 찾아서 에이전트에 등록합니다.

```kotlin
@Component  // 이것만 붙이면 끝
class MyTool : LocalTool { ... }

@Component
class AnotherTool : ToolCallback { ... }
```

### 수동 등록

`@Component` 없이 직접 빈으로 등록하거나, 멀티에이전트 node에 전달합니다.

```kotlin
// 방법 1: @Bean으로 등록
@Configuration
class ToolConfig {
    @Bean
    fun calculatorTool(): ToolCallback = CalculatorTool()
}

// 방법 2: 멀티에이전트 node에 직접 전달
MultiAgent.supervisor()
    .node("refund") {
        tools = listOf(CheckOrderTool(), ProcessRefundTool())  // 이 노드 전용 도구
    }
```

### MCP 도구 등록

관리자 REST API로 MCP 서버를 등록합니다:

```bash
curl -X POST http://localhost:8080/api/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "name": "github",
    "transportType": "STDIO",
    "config": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-github"]
    },
    "autoConnect": true
  }'
```

---

## 도구 실행 결과 (ToolResult)

도구가 구조화된 결과를 반환하고 싶을 때 `ToolResult`를 사용할 수 있습니다.

```kotlin
override suspend fun call(arguments: Map<String, Any?>): Any {
    val orderId = arguments["orderId"] as? String
        ?: return SimpleToolResult.failure("orderId is required")

    val order = orderRepository.findById(orderId)
        ?: return SimpleToolResult.failure("Order not found: $orderId")

    return SimpleToolResult.success(
        message = "주문 조회 성공",
        data = mapOf("orderId" to order.id, "status" to order.status)
    )
}
```

단순한 문자열 반환도 가능합니다:

```kotlin
override suspend fun call(arguments: Map<String, Any?>): Any {
    return "Seoul: Sunny, 22°C"  // 문자열도 OK
}
```

---

## 멀티에이전트에서의 도구

멀티에이전트 Supervisor 패턴에서는 **에이전트 자체가 도구**가 됩니다.

```
Supervisor 에이전트의 도구 목록:
  - calculator          ← 일반 도구 (ToolCallback)
  - getWeather          ← 로컬 도구 (LocalTool)
  - file_read           ← MCP 도구 (외부 서버)
  - delegate_to_refund  ← WorkerAgentTool (에이전트가 도구로 포장됨)
```

에이전트 입장에서는 4개 다 똑같은 도구입니다. 자세한 내용은 [Supervisor 패턴 가이드](../architecture/supervisor-pattern.md)를 참고하세요.

---

## 참고 코드

| 파일 | 설명 |
|------|------|
| [`ToolCallback.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCallback.kt) | 도구 인터페이스 |
| [`LocalTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/LocalTool.kt) | Spring AI 어노테이션 마커 |
| [`ToolCategory.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolCategory.kt) | 도구 분류 시스템 |
| [`ToolSelector.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/ToolSelector.kt) | 도구 필터링 |
| [`McpManager.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/mcp/McpManager.kt) | MCP 서버 관리 |
| [`CalculatorTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/CalculatorTool.kt) | ToolCallback 구현 예시 |
| [`WeatherTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/tool/example/WeatherTool.kt) | LocalTool 구현 예시 |
| [`WorkerAgentTool.kt`](../../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/WorkerAgentTool.kt) | 에이전트를 도구로 감싸는 어댑터 |

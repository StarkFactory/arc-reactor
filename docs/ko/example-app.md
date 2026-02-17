# 예시 애플리케이션 & 도구 추가 가이드

## 프로젝트 구조

Arc Reactor는 **fork해서 자신만의 도구를 붙여나가는 코어 프로젝트**입니다.
라이브러리가 아니라 직접 실행하는 Spring Boot 애플리케이션입니다.

```
src/main/kotlin/com/arc/reactor/
├── ArcReactorApplication.kt          ← 메인 진입점
├── config/
│   └── ChatClientConfig.kt           ← ChatClient 빈 설정
├── controller/
│   └── ChatController.kt             ← REST API 엔드포인트
├── tool/
│   ├── ToolCallback.kt               ← 도구 인터페이스 (핵심!)
│   └── example/
│       ├── CalculatorTool.kt          ← 예시: 계산기 도구
│       └── DateTimeTool.kt            ← 예시: 현재 시간 도구
├── agent/                             ← 에이전트 코어 (건드릴 필요 없음)
├── guard/                             ← 보안 가드 (건드릴 필요 없음)
├── hook/                              ← 훅 시스템 (건드릴 필요 없음)
├── memory/                            ← 대화 기억 (건드릴 필요 없음)
└── rag/                               ← RAG 파이프라인 (건드릴 필요 없음)
```

---

## 빠른 시작

### 1. Gemini API 키 설정

```bash
# https://aistudio.google.com/apikey 에서 발급
export GEMINI_API_KEY=your-api-key
```

### 2. 실행

```bash
./gradlew :arc-app:bootRun
```

### 3. 테스트 호출

```bash
# 일반 응답
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "3 + 5는 얼마야?"}'

# 스트리밍 응답 (SSE)
curl -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "지금 서울 시간 알려줘"}' \
  --no-buffer
```

---

## 자신만의 도구 추가하기

### 핵심: 3단계만 하면 됩니다

1. `ToolCallback` 인터페이스 구현
2. `@Component` 붙이기
3. 끝! (자동 등록됨)

### 예시: 날씨 도구 만들기

```kotlin
package com.arc.reactor.tool.example

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class WeatherTool : ToolCallback {

    // 1. 도구 이름 (LLM이 이 이름으로 호출함)
    override val name = "get_weather"

    // 2. 설명 (LLM이 언제 이 도구를 쓸지 판단하는 기준)
    override val description = "Get current weather for a city"

    // 3. 입력 스키마 (LLM이 어떤 파라미터를 보내야 하는지)
    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "city": {
                  "type": "string",
                  "description": "City name (e.g., Seoul, Tokyo)"
                }
              },
              "required": ["city"]
            }
        """.trimIndent()

    // 4. 실제 로직
    override suspend fun call(arguments: Map<String, Any?>): Any {
        val city = arguments["city"] as? String
            ?: return "Error: city parameter is required"

        // 여기에 실제 날씨 API 호출 로직을 넣으면 됩니다
        return "현재 $city 날씨: 맑음, 22도"
    }
}
```

이렇게 파일 하나 추가하면, LLM이 자동으로 이 도구를 인식하고 필요할 때 호출합니다.

---

## 포함된 예시 도구

### CalculatorTool

수학 계산 도구. 사칙연산(add, subtract, multiply, divide)을 수행합니다.

```
사용자: "152 곱하기 38은?"
LLM: [calculator 도구 호출] → "152.0 * 38.0 = 5776.0"
LLM: "152 곱하기 38은 5,776입니다."
```

### DateTimeTool

현재 날짜/시간 조회 도구. 타임존을 지정할 수 있습니다.

```
사용자: "뉴욕은 지금 몇 시야?"
LLM: [current_datetime 도구 호출, timezone="America/New_York"]
LLM: "뉴욕은 현재 2026-02-06 10:30:00 (Thursday)입니다."
```

---

## API 엔드포인트

### POST /api/chat

일반 응답. 전체 결과를 한 번에 반환합니다.

**요청:**
```json
{
  "message": "3 + 5는 얼마야?",
  "systemPrompt": "당신은 수학 선생님입니다.",
  "userId": "user-123"
}
```

**응답:**
```json
{
  "content": "3 + 5는 8입니다!",
  "success": true,
  "toolsUsed": ["calculator"],
  "errorMessage": null
}
```

### POST /api/chat/stream

스트리밍 응답 (SSE). 토큰 단위로 실시간 전달합니다.

```
data: 계산
data: 해
data: 볼게요
data: .
[도구 실행 중...]
data: 3
data:  +
data:  5
data: 는
data:  8
data: 입니다!
```

---

## 설정 (application.yml)

```yaml
# Gemini 설정
spring:
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY}
        chat:
          options:
            model: gemini-2.0-flash  # gemini-2.0-pro 등으로 변경 가능

# 에이전트 설정
arc:
  reactor:
    max-tool-calls: 10              # 한 요청에서 최대 도구 호출 수
    llm:
      temperature: 0.7              # 창의성 (0.0 ~ 1.0)
      max-output-tokens: 4096       # 최대 응답 길이
    guard:
      enabled: true                 # 보안 가드 활성화
      rate-limit-per-minute: 60     # 분당 요청 제한
```

---

## LLM 제공자 변경

기본은 Gemini (API Key)입니다. 다른 제공자를 사용하려면 `build.gradle.kts`에서 변경하세요.

### OpenAI 사용 시

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-starter-model-openai")
// compileOnly("org.springframework.ai:spring-ai-starter-model-google-genai")  ← 주석 처리
```

```yaml
# application.yml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
```

### Anthropic (Claude) 사용 시

```kotlin
// build.gradle.kts
implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
// compileOnly("org.springframework.ai:spring-ai-starter-model-google-genai")  ← 주석 처리
```

```yaml
# application.yml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-5-20250929
```

---

## 동작 원리 (간단 요약)

```
1. 사용자가 /api/chat에 메시지 보냄
2. ChatController → AgentExecutor.execute() 호출
3. Guard 파이프라인이 보안 검사 (Rate Limit, Injection Detection 등)
4. LLM에게 메시지 + 등록된 도구 목록 전달
5. LLM이 도구 호출 결정 → 도구 실행 → 결과를 LLM에 다시 전달
6. LLM이 최종 답변 생성
7. 사용자에게 응답 반환
```

핵심은 **5번 단계가 자동으로 반복**(ReAct 루프)된다는 것입니다.
LLM이 도구를 더 호출하고 싶으면 계속 호출하고, 충분하면 최종 답변을 줍니다.

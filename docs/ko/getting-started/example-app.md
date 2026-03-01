# 예제 앱 및 도구 확장 가이드

Arc Reactor는 fork 후 커스터마이즈해서 운영하는 실행형 멀티모듈 Spring Boot 애플리케이션입니다.

## 현재 모듈 구조 (요약)

```text
arc-app         실행 어셈블리 (bootRun / bootJar)
arc-core        에이전트 런타임 (ReAct, guard/hook, tool 추상화, memory, auth)
arc-web         REST/SSE API 레이어
arc-admin       운영 컨트롤 플레인 기능 (metrics/tracing/tenant dashboard)
arc-slack       Slack 채널 연동
arc-error-report 선택형 에러 리포트 모듈
```

## 최소 로컬 실행

```bash
export GEMINI_API_KEY=your-api-key
export ARC_REACTOR_AUTH_JWT_SECRET=$(openssl rand -base64 32)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arcreactor
export SPRING_DATASOURCE_USERNAME=arc
export SPRING_DATASOURCE_PASSWORD=arc
./gradlew :arc-app:bootRun
```

## 첫 API 호출

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"qa@example.com","password":"passw0rd!","name":"QA"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

curl -X POST http://localhost:8080/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"3 + 5는?"}'
```

스트리밍:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Tenant-Id: default" \
  -H "Content-Type: application/json" \
  -d '{"message":"ReAct를 3줄로 요약해줘."}'
```

## ToolCallback 방식으로 도구 추가

`ToolCallback`은 런타임에서 사용하는 프레임워크 독립 도구 인터페이스입니다.

```kotlin
package com.arc.reactor.tool.custom

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class WeatherTool : ToolCallback {
    override val name: String = "get_weather"
    override val description: String = "도시 이름으로 현재 날씨를 조회"

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "city": {"type": "string", "description": "도시 이름"}
              },
              "required": ["city"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val city = arguments["city"] as? String
            ?: return "Error: city is required"
        return "$city 날씨: 맑음, 22C"
    }
}
```

## LocalTool + @Tool 방식으로 도구 추가

메서드 기반 강타입 도구가 필요하면 `LocalTool` + Spring AI `@Tool`을 사용합니다.

```kotlin
package com.arc.reactor.tool.custom

import com.arc.reactor.tool.LocalTool
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

@Component
class RepoTool : LocalTool {

    @Tool(description = "리포지토리 기본 브랜치 조회")
    suspend fun getDefaultBranch(repo: String): String {
        return "main"
    }
}
```

## 도구 추가 후 점검 체크리스트

- Spring Bean으로 등록되었는지 확인 (`@Component` 또는 `@Bean`)
- 도구 이름/설명이 LLM 선택에 충분히 명확한지 확인
- 복구 가능한 실패는 예외 대신 `"Error: ..."` 문자열 반환
- 통합 스모크 테스트에서 `toolsUsed`에 실제 도구명이 기록되는지 확인

## 다음 문서

- [설정 Quickstart](configuration-quickstart.md)
- [설정 레퍼런스](configuration.md)
- [도구 레퍼런스](../reference/tools.md)
- [MCP 런타임 관리](../architecture/mcp/runtime-management.md)

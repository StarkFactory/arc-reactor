---
paths:
  - "**/*.kt"
---

# Kotlin & Spring Conventions

## Kotlin

- `suspend fun` — 모든 주요 인터페이스 (agent, tool, guard, hook)
- `ArcToolCallbackAdapter`만 `runBlocking(Dispatchers.IO)` 사용 (Spring AI 인터페이스 제약)
- Regex → `companion object` 또는 top-level `val`. 함수 내 `Regex()` 금지
- `content.orEmpty()` 우선 (`content!!` 금지)
- 로깅: `private val logger = KotlinLogging.logger {}` 파일 최상단 (클래스 밖)
- 메서드 ≤20줄, 줄 ≤120자. 한글 KDoc/주석 (영문 금지)

## Spring Boot

- `@ConditionalOnMissingBean` — 모든 auto-config 빈에 필수
- `@ConditionalOnClass` + `@ConditionalOnBean` — 선택 의존성
- 선택 의존성 → `ObjectProvider<T>`. 필수 → 직접 주입
- 컨트롤러: `@Tag` + `@Operation(summary = "...")` 필수
- Admin: `AdminAuthSupport.isAdmin(exchange)` + `forbiddenResponse()` — inline 중복 금지
- 새 Configuration → `ArcReactorAutoConfiguration.kt`의 `@Import` 목록에 추가

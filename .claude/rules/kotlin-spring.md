# Kotlin & Spring Conventions

## Kotlin

- `suspend fun` — agent, tool, guard, hook 모든 주요 인터페이스가 코루틴 기반
- `ArcToolCallbackAdapter`는 `runBlocking(Dispatchers.IO)` 사용 (Spring AI 인터페이스 제약)
- Regex는 hot path에서 매번 컴파일하지 말 것. `companion object`나 top-level `val`로 추출
- `content.orEmpty()` 선호 (`content!!` 대신 — 불필요한 non-null assertion 경고 방지)

## Spring Boot

- `@ConditionalOnMissingBean` — 모든 자동 설정 빈에 적용. 사용자가 자기 빈으로 오버라이드 가능
- `@ConditionalOnClass` + `@ConditionalOnBean` — 선택적 의존 (PostgreSQL, VectorStore 등)
- `compileOnly` dependency = 선택적. 사용자가 필요 시 `implementation`으로 변경
- example 패키지: `@Component` 주석 처리 상태. 프로덕션에서 자동 등록 방지

## 새 기능 추가 시 체크리스트

1. 인터페이스 정의 (확장 가능하게)
2. 기본 구현 제공
3. `ArcReactorAutoConfiguration`에 빈 등록 (`@ConditionalOnMissingBean`)
4. 테스트 작성 (`AgentTestFixture` 활용)
5. `./gradlew compileKotlin compileTestKotlin` — 경고 0개 확인
6. `./gradlew test` — 전체 테스트 통과 확인

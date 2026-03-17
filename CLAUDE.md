# Arc Reactor

Spring AI 기반 AI Agent 프레임워크 (Kotlin/Spring Boot). Fork 후 도구를 연결하여 사용.

> **아키텍처, 설계 철학, 코드 규칙, Gotchas는 [AGENTS.md](./AGENTS.md)에 정의되어 있다.** 이 파일은 Claude Code 전용 워크플로우 지침만 포함.

## Commands

```bash
./gradlew test                                             # 전체 테스트
./gradlew test --tests "com.arc.reactor.agent.*"           # 패키지 필터
./gradlew test --tests "*.SpringAiAgentExecutorTest"       # 단일 파일
./gradlew compileKotlin compileTestKotlin                  # 컴파일 체크 (0 warnings 필수)
./gradlew bootRun                                          # 실행 (GEMINI_API_KEY 필수)
./gradlew test -Pdb=true                                   # PostgreSQL/PGVector/Flyway 포함
./gradlew test -PincludeIntegration                        # @Tag("integration") 테스트 포함
```

## Environment Variables

| Variable | Required | When |
|----------|----------|------|
| `GEMINI_API_KEY` | 필수 | bootRun |
| `SPRING_AI_OPENAI_API_KEY` | 선택 | OpenAI 백엔드 |
| `SPRING_AI_ANTHROPIC_API_KEY` | 선택 | Anthropic 백엔드 |
| `SPRING_DATASOURCE_URL` | 선택 | 운영 DB (`jdbc:postgresql://localhost:5432/arcreactor`) |
| `SPRING_DATASOURCE_USERNAME` / `PASSWORD` | 선택 | 운영 DB (`arc` / `arc`) |
| `SPRING_FLYWAY_ENABLED` | 선택 | DB 마이그레이션 (`true`) |

**절대 `application.yml`에 빈 기본값으로 provider API 키를 설정하지 말 것.**

## Claude Code Workflow

- **브랜치 전략**: 항상 feature 브랜치에서 작업, main 직접 수정 금지
- **작업 전 확인**: `git status && git branch`
- **커밋 전 게이트**: `./gradlew compileKotlin compileTestKotlin` — 0 warnings
- **PR 전 게이트**: `./gradlew test` — 전체 통과
- **커밋 단위**: `git diff`로 확인 후 의미 단위로 분리 커밋
- **주석 언어**: 한글 KDoc/주석 (코드 내 영문 주석 금지)
- **파일 배치**: 인터페이스 → 패키지 루트, 구현체 → `impl/`, 데이터 → `model/`
- **핵심 파일 수정 시**: `SpringAiAgentExecutor.kt` (~626줄)는 전체를 먼저 읽을 것

## PR and Dependency Policy

- CI 머지 게이트: `build`, `integration`, `docker` 모두 통과
- LLM 호출 추가 기능: PR 설명에 비용 영향 메모 필수
- Patch/minor 의존성: CI 통과 후 머지
- Major 의존성: 마이그레이션 노트 + 롤백 플랜 필수
- Spring Boot major: 메인테이너 명시적 승인 없이 차단

## MCP Registration

REST API로만 등록 — `application.yml` 하드코딩 금지:

```
POST /api/mcp/servers
SSE:   { "name": "my-server", "transportType": "SSE", "config": { "url": "http://localhost:8081/sse" } }
STDIO: { "name": "fs-server", "transportType": "STDIO", "config": { "command": "npx", "args": [...] } }
```

## Documentation Pointers

| Path | Content |
|------|---------|
| `AGENTS.md` | 아키텍처, 철학, Gotchas, 코드 규칙, 확장 포인트 |
| `.claude/rules/kotlin-spring.md` | Kotlin/Spring 코드 컨벤션 |
| `docs/en/architecture/` | 상세 아키텍처 (EN) |
| `docs/ko/architecture/` | 상세 아키텍처 (KO) |
| `docs/en/reference/tools.md` | 도구 레퍼런스 |
| `docs/en/reference/rag-papers.md` | RAG 학술 레퍼런스 |
| `docs/en/engineering/testing-and-performance.md` | 테스트 패턴 |

## Reference Policy

외부 논문/기법 기반 기능 구현 시 `docs/en/reference/`에 문서화 (제목, 저자, 연도, 링크, 적용 위치).

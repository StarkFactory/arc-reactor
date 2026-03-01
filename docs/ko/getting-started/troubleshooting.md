# 트러블슈팅 가이드 (요약)

자주 발생하는 문제와 빠른 점검 포인트를 정리합니다.

## 1) MCP 서버가 연결되지 않음

점검:

- 트랜스포트가 `SSE` 또는 `STDIO`로 설정되었는지 확인
- SSE는 절대 `http/https` URL인지 확인
- STDIO는 command 경로/실행 가능 여부 확인

참고:

- 상세 설명은 [MCP 트러블슈팅](../architecture/mcp/troubleshooting.md)

## 2) 애플리케이션 실행 실패 (API 키/설정)

점검:

- `GEMINI_API_KEY` 또는 선택한 모델 제공자 키가 설정되어 있는지
- OpenAI 사용 시 `SPRING_AI_OPENAI_API_KEY`
- Anthropic 사용 시 `SPRING_AI_ANTHROPIC_API_KEY`

## 3) 테스트가 느리거나 불안정함

권장:

- 기본: `./gradlew test --continue`
- 통합 포함: `./gradlew test -PincludeIntegration`
- 실패 경로 테스트는 짧은 timeout 사용
- reconnect 검증 테스트가 아니면 reconnect 비활성화

참고:

- [테스트/성능 가이드](../engineering/testing-and-performance.md)

## 4) 실행 명령 혼동

현재 권장:

- 로컬 실행: `./gradlew :arc-app:bootRun`
- 실행 JAR: `./gradlew :arc-app:bootJar`

참고:

- [모듈 레이아웃](../architecture/module-layout.md)

## 5) `arc.reactor.auth.enabled=false` 오류로 기동 실패

원인:

- `arc.reactor.auth.enabled` 토글은 제거되었고, 인증은 항상 필수다.

해결:

- `arc.reactor.auth.enabled` 설정을 모두 제거
- `ARC_REACTOR_AUTH_JWT_SECRET`만 필수 설정
- 필요 시 `ARC_REACTOR_AUTH_DEFAULT_TENANT_ID` 설정

## 6) postgres-required 모드에서 username/password 누락

원인:

- `arc.reactor.postgres.required=true` 상태에서 DB 계정 정보가 비어 있음

해결:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

로컬 비프로덕션에서만 예외적으로:

- `ARC_REACTOR_POSTGRES_REQUIRED=false`

## 7) `Migration checksum mismatch for migration version <N>`로 기동 실패

원인:

- 이미 적용된 `V*.sql` 마이그레이션 파일이 수정/이름변경/삭제되어 Flyway 검증이 실패함.

해결(권장):

1. 기존 `V<version>__*.sql` 변경을 되돌림
2. 추가 변경은 새 버전 마이그레이션(`V<next>__*.sql`)으로 작성
3. 재배포

긴급 복구:

- 되돌리기 어려운 상황에서만, 승인/백업 후 `flyway repair` 절차 사용

상세 절차:

- [데이터베이스 마이그레이션 런북](database-migration-runbook.md)

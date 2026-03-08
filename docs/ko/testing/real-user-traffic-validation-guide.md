# 실제 호출형 사용자 트래픽 검증 가이드 (Arc Reactor MCP)

## 1. 목적

실서비스와 유사한 흐름으로 `/api/chat`을 100~300+ 건 이상 호출해, 아래 항목을 동시에 점검한다.

- 실제 네트워크 호출 성공/실패 패턴
- MCP 도구 사용 추적 (`toolsUsed`)
- 정책에 의한 차단(읽기 제한, 권한 제한, 가드 차단) 유무
- 요청량 증가 시 제한(429/Rate limit) 반응
- 모델별 응답 품질/출처 정책 준수

## 2. 실행 스크립트

`scripts/dev/run-real-user-traffic-validation.sh` 추가됨.

기본값:

- Base URL: `http://localhost:18081`
- 모델: `gemini-2.0-flash`
- 기본 시나리오: `core-runtime,employee-value`
- 기본 케이스: `320`
- 요청 간 지연: `1800ms`
- 셔플: `true`

실행:

```bash
BASE_URL=http://localhost:18081 \
VALIDATION_CASE_LIMIT=80 \
VALIDATION_CASE_DELAY_MS=600 \
VALIDATION_SUITES=core-runtime,employee-value \
AR_REACTOR_VALIDATION_MODEL=gemini-2.0-flash \
./scripts/dev/run-real-user-traffic-validation.sh
```

## 3. 관리자 권한 미구성 동작

래퍼/스크립트는 다음 동작을 지원한다.

- 기본 사용자 토큰: 등록 후 로그인(없으면 자동 생성)
- `--admin-token`/`--admin-email/--admin-password` 미제공 시
  - `/api/mcp/servers/*` 조회는 생략하고 경고만 남김
  - 핵심 `/api/chat` 검증은 계속 진행
- Swagger/Atlassian 헬스체크가 닿지 않으면 `URLError`를 문자열로 기록하고 검증은 종료하지 않음

## 4. 현재 실행 예시(2026-03-08)

### 40건 예시 실행
- 스위트: `core-runtime`
- 입력: `VALIDATION_CASE_LIMIT=40`
- 결과 요약
  - `total=40`, `policy_blocked=32`, `blocked=6`, `good=0`, `failed=0`
  - 대부분은 인증/도구 가용성(환경 변수 설정/권한 전제) 영향으로 블록 또는 정책 제한으로 분류

## 5. 300건 이상 실행 권고 방법

```bash
VALIDATION_CASE_LIMIT=320 \
VALIDATION_SUITES=core-runtime,employee-value \
VALIDATION_CASE_DELAY_MS=1200 \
VALIDATION_SHUFFLE=true \
VALIDATION_SHUFFLE_SEED=19 \
AR_REACTOR_VALIDATION_MODEL=gemini-2.0-flash \
./scripts/dev/run-real-user-traffic-validation.sh
```

추가 권장:

- `VALIDATION_ADMIN_EMAIL`/`VALIDATION_ADMIN_PASSWORD`를 제공하면 MCP 인벤토리/프리플라이트까지 함께 저장
- 실제 운영 유저 계정 매핑 검증이 필요하면 `VALIDATION_REQUESTER_EMAIL` 또는 `VALIDATION_REQUESTER_ACCOUNT_ID`와 함께 `personalized` 스위트를 추가

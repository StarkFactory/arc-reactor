# CTO 승인용 통합 검증 테스트 계획

Arc Reactor 배포 전, CTO 승인(Go/No-Go) 판단에 필요한 보안/성능/기능 검증 절차를 정의합니다.

## 1) 목적

- 보안: 보수적인 엔터프라이즈 환경에서도 허용 가능한 통제 수준인지 검증
- 성능: 운영 부하에서 지연/오류율/SLO가 허용 범위 내인지 검증
- 기능: 핵심 사용자 시나리오와 관리자 시나리오가 회귀 없이 동작하는지 검증

## 2) 승인 게이트 (Go/No-Go)

아래 3개 게이트를 모두 통과해야 승인합니다.

| 게이트 | 판정 기준 | 실패 시 조치 |
|---|---|---|
| G1. Security Gate | Critical/High 보안 결함 0건, 인증/인가 fail-close 보장 | 배포 중단, 원인 수정 후 재검증 |
| G2. Performance Gate | 정의된 SLO(지연/오류율/가용성) 모두 충족 | 병목 개선 후 재측정 |
| G3. Functional Gate | 핵심 시나리오 100% 성공, 회귀 0건 | 회귀 수정 및 재실행 |

## 3) 범위

### 백엔드 API 범위

- 인증/인가: `/api/auth/*`, 관리자 권한 보호 엔드포인트(예: `/api/mcp/servers`, `/api/output-guard/rules`, `/api/ops/dashboard`)
- 코어 에이전트: `/api/chat`, `/api/chat/stream`, `/api/chat/multipart`
- 정책/거버넌스: Guard, Output Guard, Tool Policy, MCP Access Policy
- 운영/관측: `/api/ops/dashboard`, 감사로그(`/api/admin/audits`) 조회

### 프론트(arc-reactor-admin) 범위

- 대시보드 데이터 표기 정합성(백엔드 API 응답과 UI 값 일치)
- 관리자/개발자 모드 권한별 UI 노출 제어
- 관리자 전용 화면의 읽기/쓰기 제어 및 다국어/반응형 기본 품질

## 4) 보안 검증 계획

### 4.1 인증/인가 및 Fail-Close

- 비인증 요청이 보호 API에서 `401`을 반환하는지 확인
- 일반 사용자 토큰으로 관리자 API 접근 시 `403` + `ErrorResponse`를 반환하는지 확인
- 테넌트 컨텍스트 불일치 시 fail-close(`400`) 확인
- Guard가 차단한 요청이 우회되지 않고 즉시 실패하는지 확인

실행:

```bash
./scripts/dev/validate-security-e2e.sh \
  --base-url http://localhost:8080 \
  --admin-token "$ADMIN_TOKEN"
```

보안 우선 검증 포인트(LLM 의존 없음):

- 인증 없는 보호 API 접근 차단(401)
- 일반 사용자의 관리자 API 접근 차단(403 + 오류 바디)
- 토큰 변조 차단(401)
- 테넌트 불일치 fail-close(400)
- 로그아웃 후 토큰 재사용 차단(401)
- 인증 브루트포스 방어(로그인 rate limit 429)

### 4.2 Guard / Filtering / Output Guard

- 입력 Guard: 프롬프트 인젝션, 과대 입력, 이상 문자(예: zero-width) 차단
- 출력 Guard: 정책 위반 응답 차단/마스킹 동작 확인
- 필터링 정책 변경 후 즉시 반영 및 감사 이벤트 생성 확인

검증 포인트:

- 기대 에러코드(`GUARD_REJECTED`, `OUTPUT_GUARD_REJECTED`) 일관성
- 스트리밍 경로(`/api/chat/stream`)에서도 동일 정책 적용

### 4.3 감사로그 최소수집(PII 최소화)

- 감사로그에 관리자 식별자는 `admin account` 수준으로만 남고, 이름/프로필 등 과도한 PII가 저장되지 않는지 확인
- 감사로그 조회 시 민감정보가 그대로 노출되지 않는지 샘플 검증

증적:

- `/api/admin/audits` 응답 샘플(민감정보 마스킹 확인)
- 관련 DB 레코드 샘플(필요 시)

### 4.4 의존성/운영 보안

- 보안 헤더(CSP/HSTS/X-Frame-Options 등) 기본 적용 여부 확인
- 관리자 API 네트워크 접근 제어(내부망/게이트웨이) 운영 설정 점검
- API 키/시크릿이 `application.yml` 기본값으로 노출되지 않았는지 점검

## 5) 성능 검증 계획 (k6 중심)

### 5.1 부하 시나리오

| 시나리오 | 대상 | 기본 부하 | 통과 기준 |
|---|---|---|---|
| P0. Auth Brute-force | `/api/auth/login` | `VUS=10`, `1m` | `auth_rate_limited_ratio > 10%`, 예상 외 상태 < 1% |
| P1. Chat API Smoke | `/api/chat` | `VUS=5`, `1m` | `http_req_failed < 5%`, `p95 < 3000ms` |
| P2. Chat API Stress | `/api/chat` | `VUS=30`, `5m` | 에러율 급증 없음, 타임아웃 제어 |
| P3. Streaming Soak | `/api/chat/stream` | 10~20 동시 연결, 10m | 연결 안정성, 중도 끊김율 기준 충족 |
| P4. Slack Gateway | `/api/slack/events`, `/api/slack/commands` | `VUS=30`, `2m` | `http_req_failed < 5%`, `p95 < 1500ms` |
| P5. Admin Read APIs | `/api/ops/dashboard`, `/api/admin/audits` | `VUS=10`, `3m` | `p95 < 1000ms`, 오류율 < 1% |

실행 예시:

```bash
# Auth brute-force baseline (no LLM dependency)
BASE_URL=http://localhost:8080 VUS=10 DURATION=1m \
  ./scripts/load/run-auth-rate-limit-load-test.sh

# Chat API baseline
BASE_URL=http://localhost:8080 VUS=5 DURATION=1m \
  ./scripts/load/run-chat-load-test.sh

# Slack gateway baseline
BASE_URL=http://localhost:8080 MODE=mixed VUS=30 DURATION=2m \
  ./scripts/load/run-slack-load-test.sh
```

### 5.2 자원/안정성 체크

- CPU/메모리/GC/스레드 풀 포화 여부
- DB 커넥션 풀 고갈 여부
- Redis 사용 시 캐시 히트율/지연, 미사용 시 인메모리 폴백 동작 및 성능 영향
- 장애 주입(LLM 지연/오류) 시 Circuit Breaker 및 타임아웃 동작

## 6) 기능 검증 계획

### 6.1 백엔드 E2E 시나리오

필수 시나리오:

- ask: `/api/chat` 단건 응답
- react: `/api/chat/stream` 스트리밍 수명주기
- vector: `/api/documents` + `/api/documents/search`
- metrics: `/api/ops/dashboard`
- approval: `/api/approvals` 승인/반려

실행:

```bash
./scripts/dev/validate-agent-e2e.sh \
  --base-url http://localhost:8080 \
  --admin-token "$ADMIN_TOKEN" \
  --require-approval
```

### 6.2 프론트 대시보드 정합성 (arc-reactor-admin)

검증 항목:

- 대시보드 지표 카드/차트 값이 백엔드 응답과 일치
- 관리자 모드에서 write 제어가 의도대로 제한되는지 확인
- 개발자 모드 전환 시 고급 기능 노출 복원
- 감사로그/정책/세션 화면에서 권한/오류 표시가 일관적인지 확인

실행:

```bash
cd /Users/stark/ai/arc-reactor-admin
npm run lint
npm run build
npm run verify:admin-api
```

수동 QA 체크리스트:

- `arc-reactor-admin/docs/admin-qa-checklist.md`

## 7) 실행 순서 (권장)

1. 정적 품질 게이트: `./gradlew compileKotlin compileTestKotlin`
2. 단위/통합 테스트: `./gradlew test`, 필요 시 `-PincludeIntegration`, `-Pdb=true`
3. 보안 우선 런타임 검증: `validate-security-e2e.sh`
4. E2E 시나리오 검증: `validate-agent-e2e.sh`
5. k6 성능 검증: auth/chat/slack/admin read API 시나리오
6. 프론트 정합성 검증: lint/build/API 커버리지 + 수동 QA
7. 결과 정리 및 CTO 승인 리뷰

## 8) 산출물 (CTO 제출 패키지)

- 테스트 실행 로그(명령/시간/환경 포함)
- JUnit 리포트(`build/test-results/test`)
- k6 결과 요약(JSON/콘솔 요약, p50/p95/error rate)
- 보안 검증 체크리스트(인증/인가/Guard/감사로그 최소수집)
- 프론트 QA 결과(스크린샷 포함)
- 최종 판정서(Go/No-Go + 리스크/후속조치)

## 9) 실패 판정 기준

- 인증/인가 우회 가능성 1건 이상
- Guard/Output Guard 비활성 또는 우회 가능성 확인
- 감사로그에 불필요한 개인정보 저장 확인
- 성능 SLO 미달(핵심 API p95/오류율 기준 위반)
- 핵심 시나리오 회귀 1건 이상

## 10) 승인 회의용 요약 템플릿

- 배포 대상 버전/커밋:
- 검증 기간:
- 테스트 환경(인프라/DB/Redis/LLM Provider):
- Security Gate 결과:
- Performance Gate 결과:
- Functional Gate 결과:
- 잔여 리스크 및 완화 계획:
- 최종 권고: `GO` 또는 `NO-GO`

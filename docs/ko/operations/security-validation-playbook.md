# 보안 검증 플레이북 (LLM 비의존 우선)

보수적인 기업 환경에서 배포 전 반드시 확인해야 하는 보안 검증 순서입니다.

## 1) 즉시 실행 (기능/보안 fail-close 확인)

```bash
./scripts/dev/validate-security-e2e.sh \
  --base-url http://localhost:8080 \
  --admin-token "$ADMIN_TOKEN"
```

검증 항목:

- 인증 없는 보호 API 차단(401)
- 일반 사용자의 관리자 API 접근 차단(403 + 오류 바디)
- 토큰 변조 차단(401)
- 테넌트 불일치 fail-close(400)
- 과대 입력 Guard 차단(400 또는 guarded 200 + errorMessage)
- 로그아웃 후 토큰 재사용 차단(401)
- 로그인 브루트포스 차단(429)

## 2) 보안 부하 검증 (Auth brute-force)

```bash
BASE_URL=http://localhost:8080 VUS=10 DURATION=1m \
  ./scripts/load/run-auth-rate-limit-load-test.sh
```

권장 통과 기준:

- `auth_rate_limited_ratio > 10%`
- `auth_unexpected_status_ratio < 1%`
- `http_req_duration p95 < 1000ms`

## 3) Guard/Filtering 부하 검증 (Chat fail-close 계약)

```bash
BASE_URL=http://localhost:8080 AUTH_TOKEN="$USER_TOKEN" MODE=mixed VUS=5 DURATION=1m \
  ./scripts/load/run-chat-guard-load-test.sh
```

권장 통과 기준:

- `chat_guard_contract_failure_ratio < 1%`
- `chat_guard_unexpected_status_ratio < 1%`
- `http_req_duration p95 < 2000ms`

## 4) 보안 베이스라인 스캔 (소스/FS)

```bash
./scripts/dev/run-security-baseline-local.sh
```

산출물:

- `artifacts/security-baseline/<timestamp>/gitleaks.sarif`
- `artifacts/security-baseline/<timestamp>/trivy-fs.json`
- `artifacts/security-baseline/<timestamp>/summary.md`

## 5) 결과 보고 포맷 (CTO 제출)

- 실행 시각/환경(브랜치, 커밋 SHA, DB/Redis/LLM 사용 여부)
- 실패 항목(있다면 재현 명령 + 로그 스니펫)
- 최종 판정: `GO` 또는 `NO-GO`

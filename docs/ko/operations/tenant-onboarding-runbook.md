# 테넌트 온보딩 런북

배포 시 테넌트/인증 설정 혼선을 줄이기 위한 운영 절차입니다.

## 1) 사전 조건

- Arc Reactor가 실행 중이며 인증은 항상 활성 상태여야 함.
- Admin 토큰(또는 admin 로그인 계정) 준비.
- 플랫폼 테넌트 API를 쓰려면 `arc-admin` 모듈 활성화 필요.

## 2) 테넌트 생성 (플랫폼 컨트롤 플레인)

`arc-admin`이 활성화된 경우 먼저 테넌트 메타데이터를 생성합니다.

```bash
curl -X POST http://localhost:8080/api/admin/platform/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "acme-prod",
    "name": "Acme Production",
    "plan": "ENTERPRISE"
  }'
```

검증:

```bash
curl http://localhost:8080/api/admin/platform/tenants \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

## 3) 사용자 토큰 발급 및 테넌트 컨텍스트 확인

회원 가입/로그인으로 JWT를 발급합니다.

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@acme.com","password":"passw0rd!","name":"Acme User"}'
```

중요 동작:

- JWT `tenantId`는 `arc.reactor.auth.default-tenant-id` 값을 사용.
- 런타임 테넌트 해석 우선순위:
  1. JWT `tenantId` 클레임
  2. `X-Tenant-Id` 헤더
- 클레임/헤더가 불일치하면 chat 엔드포인트는 fail-close로 `400` 반환.

## 4) 테넌트 fail-close 동작 검증

런타임 계약/에이전트 QA 스크립트 실행:

```bash
./scripts/dev/validate-runtime-contract.sh --base-url http://localhost:8080
./scripts/dev/validate-agent-e2e.sh --base-url http://localhost:8080 --admin-token "$ADMIN_TOKEN"
```

기대 결과:

- 비인증 요청 -> `401`
- 일반 사용자의 MCP 인벤토리 조회 -> `403`
- chat 요청에서 테넌트 컨텍스트 이상 -> fail-close (`400`)

## 5) 테넌트 대시보드 검증 (admin)

```bash
curl http://localhost:8080/api/admin/tenant/overview \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "X-Tenant-Id: acme-prod"
```

테넌트 대시보드가 `404`이면 확인:

- 런타임에 `arc-admin` 의존성이 포함되었는지
- `DataSource`가 설정되었는지
- 배포 프로필에서 tenant admin 기능 경로가 활성인지

## 6) 운영 혼선 체크리스트

- "설정했는데 적용이 안 됨":
  - 런타임 프로필/실제 env 적용값 확인
  - 현재 토큰의 JWT tenant claim 확인
- "헤더를 넣었는데 거부됨":
  - JWT tenant claim과 `X-Tenant-Id` 불일치 여부 확인
- "승인 API가 없음":
  - `/api/approvals`는 `arc.reactor.approval.enabled=true`일 때만 등록

## 참고

- `docs/ko/governance/authentication.md`
- `docs/ko/admin/api-reference.md`
- `scripts/dev/validate-runtime-contract.sh`
- `scripts/dev/validate-agent-e2e.sh`

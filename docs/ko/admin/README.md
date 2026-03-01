# 관리자 문서

이 섹션은 Arc Reactor 관리자 가이드를 포함합니다.

## 목차

- [API 레퍼런스](./api-reference.md) — 모든 관리자 전용 REST 엔드포인트에 대한 완전한 레퍼런스. 인증 설정, 요청/응답 스키마, 상태 코드를 포함합니다.

## 빠른 시작

### 런타임 인증 필수

1. 토큰을 받기 위해 로그인합니다:
   ```bash
   curl -X POST http://localhost:8080/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"email":"admin@example.com","password":"yourpassword"}'
   ```

2. 이후 요청에 토큰을 사용합니다:
   ```bash
   curl http://localhost:8080/api/personas \
     -H "Authorization: Bearer <token>"
   ```

3. 관리자 엔드포인트는 `ADMIN` role 토큰이 필요합니다.
   일반 사용자 토큰은 `403 Forbidden`을 반환합니다.

## 주요 관리자 작업

| 작업 | 엔드포인트 |
|------|---------|
| MCP 서버 등록 | `POST /api/mcp/servers` |
| 페르소나 생성 | `POST /api/personas` |
| 프롬프트 템플릿 관리 | `POST /api/prompt-templates` |
| RAG 문서 추가 | `POST /api/documents` |
| 감사 로그 조회 | `GET /api/admin/audits` |
| 운영 대시보드 | `GET /api/ops/dashboard` |
| 플랫폼 헬스 (arc-admin) | `GET /api/admin/platform/health` |
| 테넌트 대시보드 (arc-admin) | `GET /api/admin/tenant/overview` |

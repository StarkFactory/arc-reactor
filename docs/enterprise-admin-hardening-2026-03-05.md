# Enterprise Admin Hardening (2026-03-05)

## 목적

기업 중앙관리형 Arc Reactor 운영을 위해 다음 요구를 반영했다.

- 관리자 시스템에서 사용자 식별정보를 기본 저장하지 않는다.
- 최대 1000명 규모(단일 에이전트 + Slack 다수 사용자) 운영 시 대시보드/운영 기능이 유지되어야 한다.
- 보안 민감값(토큰/시크릿)은 조회 API에서 노출되지 않아야 한다.
- 권한 분리: 비개발 관리자(`ADMIN_MANAGER`)는 읽기 중심 대시보드 접근, 개발 관리자(`ADMIN`/`ADMIN_DEVELOPER`)는 설정/변경 권한 유지.

## 탐색 범위(다중 루프)

아래 영역을 반복적으로 점검했다.

- `arc-admin`: 수집 훅, 쿼리 서비스, 대시보드, 권한 헬퍼, 컨트롤러, 마이그레이션 구조
- `arc-web`: MCP 서버 관리 API, 보안 응답, 관리자 권한 경계
- `arc-slack`: 대규모 요청 처리/레이트리밋/중복 방지 구조 점검
- 테스트 코드 전체: 권한/수집/쿼리/MCP 응답 회귀 영향 확인
- 문서/설정: 운영자 관점에서 새 기본값과 권한 모델 정합성 점검

## 구현 변경

### 1) 개인정보 비식별 기본값 강화 (`arc-admin`)

- 새 설정 추가
  - `arc.reactor.admin.privacy.store-user-identifiers` (default `false`)
  - `arc.reactor.admin.privacy.store-session-identifiers` (default `false`)
- `MetricCollectionHook`:
  - 기본값에서 `userId`, `sessionId`를 메트릭 이벤트에 저장하지 않음
  - 세션 식별자 저장 비활성 시 `SessionEvent` 자체 발행하지 않음
- `AgentTracingHooks`:
  - 기본값에서 span tag `user_id`, `session_id`를 남기지 않음
  - 설정으로만 opt-in 가능

### 2) 권한 분리 정렬 (읽기 대시보드 vs 쓰기 관리)

- `AdminAuthSupport`에 `isAnyAdmin()` 추가
- `TenantAdminController`:
  - 읽기 대시보드/조회 (`overview`, `usage`, `quality`, `tools`, `cost`, `slo`, `alerts`, `quota`)는 `ADMIN_MANAGER` 허용
  - CSV export는 기존대로 개발자 관리자 전용 유지
- `PlatformAdminController`:
  - 읽기 대시보드 성격의 `health`, `tenants/analytics`는 `ADMIN_MANAGER` 허용
  - 테넌트 CRUD/가격/알림 규칙 변경 등 쓰기 동작은 기존 권한 유지

### 3) MCP 민감정보 노출 차단

- `McpServerController.getServer()` 응답의 `config` 필드에 마스킹 적용
  - 대상 키 패턴: `token`, `secret`, `password`, `apiKey`, `credential` (대소문자/구분자 대응)
  - 마스킹 값: `********`
- 등록/업데이트 저장값은 기존과 동일하게 유지(조회 응답만 마스킹)

### 4) 대시보드/내보내기 보완

- `MetricQueryService.getTopUsers()`:
  - 사용자 식별자 집계 결과가 비어있으면 채널 기반 fallback(`Channel:<name>`) 제공
  - 비식별 모드에서도 대시보드 위젯이 빈 상태로만 끝나지 않도록 개선
- `ExportService.exportExecutionsCsv()`:
  - CSV에서 `user_id` 컬럼 제거

## 테스트/회귀 보강

아래 테스트를 함께 수정/추가했다.

- `AdminPropertiesTest`: privacy 기본값/커스텀 값 검증
- `MetricCollectionHookTest`: 기본 비식별 동작 + opt-in 저장 동작 검증
- `AgentTracingHooksTest`: 비식별 기본 모드에서 `user_id/session_id` 미태깅 검증
- `AdminAuthSupportTest`: `isAnyAdmin` 검증
- `TenantAdminControllerTest`: `ADMIN_MANAGER` 읽기 허용 + export 제한 검증
- `PlatformAdminControllerTest`: `ADMIN_MANAGER` 대시보드 접근 허용 검증
- `MetricQueryServiceTest`: top users fallback(채널 기반) 검증
- `McpServerControllerTest`: 민감 config 마스킹 검증

## 운영 관점 결론

- 중앙관리형 엔터프라이즈 운영에 필요한 최소 보안선(식별자 기본 비저장 + 민감 설정 마스킹)을 적용했다.
- 관리자 역할 분리를 API 레벨에서 명확히 해 비개발 관리자의 읽기 접근성과 개발 관리자 제어 권한을 분리했다.
- 개인정보를 저장하지 않는 기본 정책에서도 대시보드가 동작하도록 fallback 경로를 추가했다.


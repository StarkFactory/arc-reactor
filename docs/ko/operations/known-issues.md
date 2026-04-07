# 알려진 이슈 및 제한사항

## 활성 이슈

### ~~1. Web Chat API에서 metric_sessions 미수집~~ ✅ 해결됨
- **수정**: ChatController.resolveMetadata에서 sessionId 미존재 시 `web-{UUID}` 자동 생성
- **커밋**: `0a055aa9`

### 2. Flyway V28 checksum 불일치
- **증상**: 서버 시작 시 Flyway validation 실패
- **원인**: V28 마이그레이션 파일 checksum이 DB 기록과 불일치
- **해결**: `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`로 우회 중
- **영구 해결**: DB를 fresh로 재생성하거나 flyway_schema_history 수동 수정
- **우선순위**: 낮음 (프로덕션 배포 시 fresh DB 사용 예정)

### 3. estimated_cost_usd 기존 데이터 0
- **증상**: V49 마이그레이션 전 기록된 token_usage의 비용이 전부 0
- **원인**: model_pricing 테이블에 provider가 'gemini'로 등록되었으나 실제 이벤트는 'google'
- **해결**: V49 마이그레이션으로 수정 완료. 기존 0 데이터는 소급 수정 불가.
- **우선순위**: 낮음 (향후 데이터는 정상 기록)

## 제한사항

### 환경변수 필수 설정
- `ARC_REACTOR_ADMIN_ENABLED=true` — 없으면 Trace/ToolCall/TokenCost/Eval 등 API 404
- `ARC_REACTOR_ADMIN_PRIVACY_STORE_SESSION_IDENTIFIERS=true` — 없으면 Usage/SlackActivity/ConvAnalytics 빈 배열
- `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` — Flyway checksum 이슈 우회

### RAG Analytics 테넌트 격리
- `rag_ingestion_candidates` 테이블에 `tenant_id` 컬럼 없음
- 단일 테넌트 전제로 동작. 멀티 테넌트 시 마이그레이션 필요.

### 멀티에이전트 Phase 1 한계
- AgentSpec CRUD + Reactor Universe UI만 구현
- 실제 오케스트레이션 실행 (에이전트 간 위임/협업)은 Phase 2에서 구현 예정

### 멀티 봇 토큰 DB 저장 (V46)
- `slack_bot_instances` 테이블에 `bot_token`/`app_token`이 평문 저장됨
- 기존 정책은 "Slack 토큰은 환경변수로만 관리"였으나 멀티 봇 지원으로 DB 저장 불가피
- **대응 필요**: 애플리케이션 레벨 암호화(AES-256-GCM) 적용 또는 Vault 연동
- **우선순위**: 높음 (프로덕션 배포 전 반드시 해결)

# 런타임 설정 서비스 설계

## 문제

현재 65개+ 환경변수 중 ~20개는 Admin이 런타임에 변경 가능해야 하지만, 환경변수라서 재배포 필요.

## 목표

Admin UI에서 기능 토글/파라미터를 변경하면 **재배포 없이** 즉시 반영.

## 분류

### Admin 관리 대상 (DB → 런타임 변경)

```
기능 토글:
├── guard.enabled                    — Guard 파이프라인
├── output-guard.enabled             — 출력 검증
├── rag.enabled                      — RAG (검색 보강)
├── citation.enabled                 — 출처 표시
├── slack.tools.enabled              — Slack 도구
├── multi-bot.enabled                — 멀티 봇
├── scheduler.enabled                — 스케줄러 (비활성 = cron 중지)
├── approval.enabled                 — 도구 승인
├── tool-policy.enabled              — 도구 정책

튜닝:
├── llm.default-model                — 기본 LLM 모델
├── max-tool-calls                   — 최대 도구 호출 수
├── max-tools-per-request            — 요청당 최대 도구 수
├── guard.rate-limit-per-minute      — 분당 요청 제한
├── guard.rate-limit-per-hour        — 시간당 요청 제한
├── tool-selection.strategy          — 도구 선택 전략
├── tool-selection.similarity-threshold — 유사도 임계값
```

### 환경변수 유지 (인프라/보안)

```
├── JWT_SECRET, DB URL, API Keys     — 보안
├── SLACK_BOT_TOKEN, APP_TOKEN       — 인프라
├── GEMINI_API_KEY                   — 인프라
├── SPRING_DATASOURCE_*              — 인프라
├── MCP_ALLOW_PRIVATE_ADDRESSES      — 네트워크
├── Slack 캐시 TTL, Dedup 설정       — 세부 튜닝 (개발팀)
```

## DB 스키마

```sql
CREATE TABLE IF NOT EXISTS runtime_settings (
    key         VARCHAR(200)    PRIMARY KEY,
    value       TEXT            NOT NULL,
    type        VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    category    VARCHAR(50)     NOT NULL DEFAULT 'general',
    description TEXT,
    updated_by  VARCHAR(100),
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- type: STRING, BOOLEAN, INTEGER, DOUBLE
-- category: guard, llm, slack, rag, tool, scheduler
```

## 아키텍처

```
Admin UI
  ↓ PUT /api/admin/settings/{key}
RuntimeSettingsController
  ↓
RuntimeSettingsService (Caffeine 캐시 + DB)
  ↓ 캐시 TTL 30초 (DB 부하 최소화)
RuntimeSettingsStore (JDBC)
  ↓
runtime_settings 테이블
```

## 코드 사용 패턴

```kotlin
// Before (환경변수)
@Value("\${arc.reactor.guard.enabled:true}")
private val guardEnabled: Boolean = true

// After (런타임 설정)
@Autowired
private val settings: RuntimeSettingsService

fun isGuardEnabled(): Boolean =
    settings.getBoolean("guard.enabled", default = true)
```

## 우선순위 변경 규칙

```
DB 설정 (런타임) > 환경변수 > application.yml 기본값
```

DB에 값이 있으면 환경변수를 오버라이드. DB에 없으면 기존 환경변수 동작 유지 (하위 호환).

## Admin API

```
GET    /api/admin/settings              — 전체 설정 목록 (카테고리별)
GET    /api/admin/settings/{key}        — 개별 설정 조회
PUT    /api/admin/settings/{key}        — 설정 변경
DELETE /api/admin/settings/{key}        — 기본값으로 리셋
```

## 응답 형식

```json
{
  "key": "guard.enabled",
  "value": "true",
  "type": "BOOLEAN",
  "category": "guard",
  "description": "Guard 파이프라인 활성화",
  "source": "database",
  "updatedBy": "admin@arc.dev",
  "updatedAt": "2026-04-06T10:00:00Z"
}
```

## 구현 순서

1. DB 마이그레이션 (`runtime_settings` 테이블)
2. `RuntimeSettingsStore` (JDBC)
3. `RuntimeSettingsService` (Caffeine 캐시 + 타입 변환)
4. `RuntimeSettingsController` (Admin API)
5. 기존 코드에서 환경변수 → `RuntimeSettingsService` 전환 (점진적)
6. 초기 데이터 마이그레이션 (기존 환경변수 값을 DB에 시딩)

## 주의사항

- 캐시 TTL은 30초 — 변경 후 최대 30초 지연
- 즉시 반영이 필요하면 캐시 무효화 API 제공 (`POST /api/admin/settings/refresh`)
- 설정 변경 시 감사 로그 기록 (AdminAuditStore 연동)
- 잘못된 설정으로 시스템 장애 방지 → 검증 규칙 필수

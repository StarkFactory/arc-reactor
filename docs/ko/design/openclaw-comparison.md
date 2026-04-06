# Arc Reactor vs OpenClaw 비교 분석

## 요약

Arc Reactor는 **기업 중앙관리형**, OpenClaw는 **개인 생산성형**.
기업 300명+ 환경에서 Arc Reactor가 우위인 영역이 압도적.

## Arc Reactor 우위 (기업 중앙관리)

| 영역 | Arc Reactor | OpenClaw |
|------|------------|----------|
| 멀티 테넌트 | DB 기반 유저/테넌트 | 단일 사용자 |
| Admin API | 페르소나/봇/설정/스케줄 CRUD | 없음 (파일 편집) |
| Guard 보안 | 7단계 입력+출력 파이프라인 | 기본 수준 |
| 인증/인가 | JWT + RBAC + 토큰 폐기 | 없음 |
| 감사 로그 | AdminAuditStore | 기본 로깅 |
| 사내 도구 | Jira/Confluence/Bitbucket MCP | 범용 도구 |
| DB 영속 | PostgreSQL + Flyway | 파일 (Markdown) |
| 멀티 봇 | DB 봇 인스턴스 + 페르소나 | 없음 |
| 런타임 설정 | Redis + DB + Admin API | 파일 편집 |

## OpenClaw 우위 (개인 생산성)

| 영역 | OpenClaw | 기업에 필요? |
|------|----------|------------|
| 30+ 채널 | WhatsApp/Telegram/Discord 등 | 낮음 (Slack 충분) |
| 4,000+ 스킬 | ClawHub 생태계 | 중간 |
| 로컬 도구 | 파일/쉘/브라우저 직접 제어 | 낮음 (보안 위험) |
| 컨텍스트 엔진 | 플러그인 교체 가능 | 중간 |

## 차용할 패턴

### 이미 차용 완료
- 시스템 프롬프트 외부 파일화 (SOUL.md → prompts/*.md)
- 주기적 스케줄 (/reactor loop ← HEARTBEAT)
- 2-tier 검증 (Guard: 규칙 기반 → LLM)

### 향후 차용 검토
- 유저별 사용량 대시보드 (토큰/비용 추적)
- 피드백 기반 프롬프트 자동 조정

### 차용 불필요
- 30+ 채널 지원 (기업은 Slack + Web 충분)
- 로컬 파일/쉘 직접 실행 (기업 보안 정책상 위험)
- ClawHub 스킬 생태계 (MCP 표준으로 대체)

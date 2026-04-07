# Arc Reactor 설계 문서

## 설계 문서

| 문서 | 상태 | 설명 |
|------|------|------|
| [slack-loop-command.md](slack-loop-command.md) | ✅ 구현 완료 | `/reactor loop` — 주기적 업무 브리핑 |
| [multi-bot-persona.md](multi-bot-persona.md) | 🔨 Phase 2 완료 | 멀티 봇 페르소나 — DB + Admin API |
| [runtime-settings.md](runtime-settings.md) | ✅ 구현 완료 | 런타임 설정 — Redis + DB + Admin API |
| [openclaw-comparison.md](openclaw-comparison.md) | 📋 분석 완료 | OpenClaw vs Arc Reactor 비교 |
| [feedback-dashboard.md](feedback-dashboard.md) | ✅ 구현 완료 | 피드백 통계 API + Admin UI 연동 |
| [authentication-architecture.md](authentication-architecture.md) | ✅ 구현 완료 | aslan-iam 중앙 인증 + 토큰 교환 패턴 |
| [multi-agent-orchestration.md](multi-agent-orchestration.md) | 🔨 Phase 1 완료 | 멀티에이전트 — DB + API + Reactor Universe UI |

## 구현 우선순위

### 완료

1. ~~`/reactor loop` Slash Command~~ ✅
2. ~~멀티 봇 Phase 1-2 (DB + Admin API)~~ ✅
3. ~~런타임 설정 서비스~~ ✅
4. ~~기업용 명령어 5종~~ ✅
5. ~~멀티에이전트 Phase 1 (AgentSpec DB + Admin API + Reactor Universe UI)~~ ✅
6. ~~Agents & AI Apps (assistant_thread_started, 추천 프롬프트, 타이핑 표시)~~ ✅
7. ~~인증 아키텍처 (aslan-iam 토큰 교환 + 직접 로그인 fallback)~~ ✅
8. ~~Admin API 15개 백엔드 전수 구현~~ ✅ (아래 표 참조)

### 다음 목표

9. 멀티에이전트 Phase 2 (오케스트레이션 엔진 — 순차/병렬/토론/체인)
10. 멀티 봇 Phase 3 (멀티 WebSocket 동시 연결)
11. Admin UI ↔ 백엔드 API 연결 (프론트엔드)
12. 데이터 보존 정책 자동 삭제 스케줄러
13. RAG 문서 hit rate 추적 (검색 로깅)

## Admin API 현황 (15개)

| # | 기능 | 엔드포인트 | 모듈 | 상태 |
|---|------|-----------|------|------|
| 1 | 실행 트레이스 | `/api/admin/traces` | arc-admin | ✅ |
| 2 | 도구 호출 상세 | `/api/admin/tool-calls` | arc-admin | ✅ |
| 3 | 토큰/비용 분석 | `/api/admin/token-cost` | arc-admin | ✅ |
| 4 | 사용자 사용량 | `/api/admin/users/usage` | arc-admin | ✅ |
| 5 | Slack 활동 | `/api/admin/slack-activity` | arc-admin | ✅ |
| 6 | Input Guard | `/api/admin/input-guard` | arc-web | ✅ |
| 7 | Eval 대시보드 | `/api/admin/evals` | arc-admin | ✅ |
| 8 | 모델 레지스트리 | `/api/admin/models` | arc-web | ✅ |
| 9 | 레이턴시 | `/api/admin/metrics/latency` | arc-admin | ✅ |
| 10 | 에이전트 스펙 | `/api/admin/agent-specs` | arc-web | ✅ |
| 11 | RBAC | `/api/admin/rbac` | arc-web | ✅ |
| 12 | 데이터 보존 | `/api/admin/retention` | arc-web | ✅ |
| 13 | 감사 내보내기 | `/api/admin/audits/export` | arc-web | ✅ |
| 14 | RAG 분석 | `/api/admin/rag-analytics` | arc-admin | ✅ |
| 15 | 대화 분석 | `/api/admin/conversation-analytics` | arc-admin | ✅ |

> 상세 API 스펙: [../admin/admin-api-reference.md](../admin/admin-api-reference.md)

## 운영/가이드 문서

| 문서 | 설명 |
|------|------|
| [../guide/slack-commands.md](../guide/slack-commands.md) | Slack 명령어 가이드 |
| [../guide/slack-bot-setup.md](../guide/slack-bot-setup.md) | Slack 봇 설정 (8개 이벤트 + Agents & AI Apps) |
| [../admin/admin-api-reference.md](../admin/admin-api-reference.md) | Admin API 전체 레퍼런스 (프론트 개발 가이드) |
| [../operations/environment-variables.md](../operations/environment-variables.md) | 환경변수 가이드 |
| [../operations/cache-strategy.md](../operations/cache-strategy.md) | Redis 캐시 전략 |

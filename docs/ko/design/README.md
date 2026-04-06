# Arc Reactor 설계 문서

## 설계 문서

| 문서 | 상태 | 설명 |
|------|------|------|
| [slack-loop-command.md](slack-loop-command.md) | ✅ 구현 완료 | `/reactor loop` — 주기적 업무 브리핑 |
| [multi-bot-persona.md](multi-bot-persona.md) | 🔨 Phase 2 완료 | 멀티 봇 페르소나 — DB + Admin API |
| [runtime-settings.md](runtime-settings.md) | ✅ 구현 완료 | 런타임 설정 — Redis + DB + Admin API |
| [openclaw-comparison.md](openclaw-comparison.md) | 📋 분석 완료 | OpenClaw vs Arc Reactor 비교 |
| [feedback-dashboard.md](feedback-dashboard.md) | 📋 설계 완료 | 피드백 대시보드 — 수동 반영 워크플로 |

## 구현 우선순위

1. ~~`/reactor loop` Slash Command~~ ✅
2. ~~멀티 봇 Phase 1-2 (DB + Admin API)~~ ✅
3. ~~런타임 설정 서비스~~ ✅
4. ~~기업용 명령어 5종~~ ✅
5. 멀티 봇 Phase 3 (멀티 WebSocket)
6. 피드백 통계 API 구현
7. 유저별 사용량 대시보드

## 운영/가이드 문서

| 문서 | 설명 |
|------|------|
| [../guide/slack-commands.md](../guide/slack-commands.md) | Slack 명령어 가이드 |
| [../guide/slack-bot-setup.md](../guide/slack-bot-setup.md) | Slack 봇 설정 10단계 |
| [../operations/environment-variables.md](../operations/environment-variables.md) | 환경변수 가이드 |
| [../operations/cache-strategy.md](../operations/cache-strategy.md) | Redis 캐시 전략 |

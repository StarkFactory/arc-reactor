# Arc Reactor 설계 문서

## 현재 설계 문서

| 문서 | 상태 | 설명 |
|------|------|------|
| [slack-loop-command.md](slack-loop-command.md) | ✅ 구현 완료 | `/loop` Slash Command — 주기적 업무 브리핑 |
| [multi-bot-persona.md](multi-bot-persona.md) | 🔨 Phase 1 완료 | 멀티 봇 페르소나 — 1 Reactor + N 봇 |
| [runtime-settings.md](runtime-settings.md) | 📋 설계 완료 | 런타임 설정 서비스 — Admin DB 관리 |

## 구현 우선순위

1. ~~`/loop` Slash Command~~ ✅
2. ~~멀티 봇 Phase 1 (DB + Store + Admin API)~~ ✅
3. 런타임 설정 서비스 구현
4. 멀티 봇 Phase 2 (멀티 WebSocket)
5. 멀티 봇 Phase 3 (Admin UI)

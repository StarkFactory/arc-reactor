# Reports Layout

Arc Reactor의 반복 검증 기록은 아래 구조를 따른다.

- `docs/production-readiness-report.md`
  메인 상태판. 현재 상태, 출시 게이트, 최근 Round 요약만 유지
- `docs/reports/rounds/R{N}.md`
  각 Round의 상세 로그
- `docs/reports/archive/production-readiness-report-legacy-2026-04-12.md`
  2026-04-12 이전의 대형 누적 보고서

## 운영 규칙

1. 새 Round를 수행하면 `R{N}.md` 상세 파일을 먼저 작성한다.
2. 메인 상태판의 `10. 반복 검증 이력`에는 요약만 추가한다.
3. 메인 상태판에는 최근 20개 Round 요약만 유지한다.
4. 오래된 상세 이력은 round 파일 또는 archive에서 찾는다.

## 목적

- 에이전트가 최근 상태를 빠르게 읽을 수 있게 한다.
- 거대한 단일 파일 누적을 막는다.
- 사람과 watchdog가 같은 구조를 읽도록 맞춘다.

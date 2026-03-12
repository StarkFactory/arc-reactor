# Arc Reactor — Autonomous Improvement Loop

## 시작

`claude-progress.txt` (마지막 20줄), `git log --oneline -5`, `IMPLEMENTATION_PLAN.md`, `KNOWN_ACCEPTABLE.md` 읽기.

---

## 1단계: 탐색

IMPLEMENTATION_PLAN.md에 미완료(`- [ ]`) 항목이 **0개**일 때만 실행.

### 회전 렌즈

`claude-progress.txt`에서 마지막 렌즈 확인 → **다음 번호** 사용 (8 → 1 순환).

| # | 렌즈 | 초점 |
|---|------|------|
| 1 | 보안 | 인젝션, SSRF, 권한/인증 우회, 정보 노출, 타이밍 공격 |
| 2 | 견고성 | 리소스 누수, 동시성, 에러 복구, 미처리 예외 |
| 3 | Agent 동작 | ReAct 루프, tool call 실패, 무한 루프, 컨텍스트 트리밍, 메시지 쌍 |
| 4 | 성능 | 핫패스 Regex/할당, N+1, 불필요한 직렬화, 캐시 미스 |
| 5 | 테스트 갭 | 소스 대비 테스트 누락, 엣지 케이스, 에러 경로 |
| 6 | API 계약 | Breaking change, 응답 형식, @Operation 누락, 에러 표준 |
| 7 | 운영 | 헬스체크, 메트릭/로깅, 설정 기본값, graceful shutdown |
| 8 | Critical Gotchas | CancellationException, activeTools=emptyList, .forEach suspend, AssistantMessage builder, Guard null userId |

### 스캔

1. `./gradlew compileKotlin compileTestKotlin --no-daemon` — 0 warnings 확인
2. `.claude/agents/codebase-scanner` (sonnet) 3~5개 병렬 — 렌즈 + 스캔 영역 + KNOWN_ACCEPTABLE 전문 전달
3. 후보를 **직접 Grep/Read로 검증** (별도 검증 에이전트 금지)
   - 해당 라인 Read → 상위 호출자 방어 여부 Grep → 기존 테스트 커버 여부 Grep

### 필터링

KNOWN_ACCEPTABLE에 있으면 스킵. 이론적이면 스킵. 설계 의도면 스킵. 코드 경로 증명 불가면 스킵.
통과 → IMPLEMENTATION_PLAN.md에 `- [ ] [HIGH|MED] Px: \`파일:라인\` — 설명`. LOW → KNOWN_ACCEPTABLE에 기록.

탐색에서 항목 발견 시 **같은 반복**에서 2단계로 진행.

---

## 2단계: 구현

P0 → P1 → P2 → P3 → P4 순. 같은 우선순위에서 2~3개 배치 가능 (수정 파일 비중복).

**각 항목**: 코드 검색 → 수정 (CLAUDE.md Gotchas 준수) → diff 자기 검증 (해결? 부작용? 최소?)

**배치 완료 후**:
1. `./gradlew compileKotlin compileTestKotlin --no-daemon`
2. 타겟 테스트 (수정 모듈 기준):
   - `arc-core` → `./gradlew test --no-daemon` (전체 — 모든 모듈이 의존)
   - 리프 모듈 → `./gradlew :arc-web:test :arc-slack:test --no-daemon` (해당 모듈만, 병렬 가능)
   - 리프: arc-web, arc-admin, arc-slack, arc-teams, arc-google, arc-error-report
3. 커밋 직전에만 `./gradlew test --no-daemon` 전체 1회
4. 3회 실패 시 `git checkout .` 후 다른 항목

**브랜치**: P0~P2 → `improve/[설명]` 브랜치 → dev 머지 → 삭제. P3~P4 → dev 직접 커밋.

**기록**: IMPLEMENTATION_PLAN.md `- [x]` + 커밋 해시. claude-progress.txt에 1줄 append.

---

## 완료 조건

P0~P3 미완료 0개 + 최근 탐색 P0~P3 신규 0건 → `<promise>IMPROVEMENTS COMPLETE</promise>`

**수렴**: 8렌즈 전부 1회 이상 + 마지막 전체 사이클 P0~P3 신규 0건, 또는 20회 초과 시 P0~P1만.

---

## 절대 규칙

1. 기존 테스트를 깨지 않는다
2. main 브랜치 금지, AGENTS.md 수정 금지
3. **Gradle**: 항상 `--no-daemon` 사용 (daemon 잔류/충돌 방지). 동시 실행 금지
4. progress 50줄 초과 시 `claude-progress-archive.txt`로 이동, 최근 20줄 유지

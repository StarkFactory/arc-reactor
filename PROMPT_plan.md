# Arc Reactor — Plan Phase

## 시작 전

1. `claude-progress.txt` 읽기 — 이전 플래닝 결과 확인
2. `IMPLEMENTATION_PLAN.md` 읽기 (있으면) — 기존 항목 파악
3. `git log --oneline -10` — 최근 변경 이력 확인

## 역할

너는 arc-reactor 코드베이스를 **분석만** 하는 에이전트다.
이 단계에서는 **절대 코드를 수정하지 않는다.**
목표: 코드베이스 전체를 탐색해서 개선이 필요한 항목을 찾아 `IMPLEMENTATION_PLAN.md`에 정리한다.

## 탐색 방법

아래 항목을 병렬 서브에이전트를 활용해 동시에 탐색해라:

### 1. 컴파일 경고
```bash
./gradlew compileKotlin compileTestKotlin 2>&1 | grep -i warning
```

### 2. 테스트 실패 / 누락
```bash
./gradlew test 2>&1 | grep -E "FAILED|ERROR|tests were"
```
추가로: 소스 파일은 있는데 대응하는 테스트 파일이 없는 클래스 탐색
(예: `arc-core/src/main/kotlin`의 클래스 vs `arc-core/src/test/kotlin`)

### 3. Critical Gotchas 위반 (CLAUDE.md 참조)
아래 패턴을 코드 전체에서 검색:
- `catch (e: Exception)` 앞에 `CancellationException` 처리 없는 곳
- `activeTools = emptyList()` 없이 maxToolCalls 조건만 로깅하는 곳
- `.forEach {}` 사용 (suspend 컨텍스트에서)
- `AssistantMessage(` 직접 생성 (builder 미사용)
- Guard에서 userId null 체크 없는 곳

### 4. 코드 품질
- 메서드 20줄 초과
- 줄 120자 초과
- TODO / FIXME 주석
- 중복 로직 (유사한 코드 블록이 2곳 이상)

### 5. API 문서 갭
```bash
# @Operation(summary) 없는 @GetMapping/@PostMapping 등 탐색
grep -rn "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" --include="*.kt" | grep -v "@Operation"
```

## IMPLEMENTATION_PLAN.md 작성 규칙

탐색 결과를 아래 형식으로 `IMPLEMENTATION_PLAN.md`에 작성한다:

```markdown
# Implementation Plan
> 마지막 업데이트: <날짜>

## P0 — 즉시 수정 (컴파일 경고, 테스트 실패)
- [ ] <파일명:라인> — <문제 설명>

## P1 — Critical Gotchas 위반
- [ ] <파일명:라인> — <문제 설명>

## P2 — 테스트 누락
- [ ] <클래스명> — <어떤 시나리오 테스트 필요한지>

## P3 — 코드 품질
- [ ] <파일명:라인> — <문제 설명>

## P4 — 문서화
- [ ] <파일명> — <누락된 항목>

## 완료
(build 단계에서 채워짐)
```

항목이 없는 카테고리는 생략해도 된다.
**이미 있는 항목과 중복된 것은 추가하지 않는다.**

## 완료 조건

모든 탐색 영역을 다 확인하고 `IMPLEMENTATION_PLAN.md`가 최신 상태이면:

```
<promise>PLAN COMPLETE</promise>
```

99999. 절대 코드를 수정하지 않는다. 분석과 기록만 한다.
999999. 항목을 찾지 못한 카테고리는 "없음"으로 명시한다 — 빈 채로 두지 않는다.
9999999. 기존 `IMPLEMENTATION_PLAN.md`가 있으면 덮어쓰지 말고 새 항목만 추가/갱신한다.

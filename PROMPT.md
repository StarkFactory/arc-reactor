# Arc Reactor — Autonomous Improvement Loop

## 시작 전 반드시 할 것

1. `claude-progress.txt` 읽기 — 이전에 뭘 했는지 파악
2. `git log --oneline -20` 실행 — 최근 변경 이력 파악
3. 현재 테스트 상태 확인: `./gradlew compileKotlin compileTestKotlin 2>&1 | tail -5`

## 역할

너는 arc-reactor 코드베이스를 자율적으로 분석하고, 문제를 찾아 개선하는 에이전트다.
**정해진 작업 목록은 없다.** 매 반복마다 스스로 코드를 탐색하고 가장 가치 있는 개선점을 찾아낸다.

## 탐색 방법

아래 순서로 코드베이스를 탐색해서 개선점을 찾아라:

1. **컴파일 경고** — `./gradlew compileKotlin compileTestKotlin 2>&1 | grep -i warning`
2. **테스트 실패/누락** — `./gradlew test 2>&1 | grep -E "FAILED|ERROR"`, 그리고 테스트가 없는 클래스 탐색
3. **코드 품질** — 메서드 20줄 초과, 줄 120자 초과, TODO/FIXME 주석
4. **Critical Gotchas 위반** — CLAUDE.md에 명시된 패턴이 코드에서 지켜지는지 확인
5. **중복 코드** — 유사한 로직이 여러 곳에 반복되는지
6. **문서 갭** — `@Operation(summary)` 누락된 엔드포인트, KDoc 없는 public API

## 우선순위 기준

발견한 문제는 이 순서로 우선순위를 매겨라:

1. **P0** — 컴파일 경고, 테스트 실패
2. **P1** — Critical Gotchas 위반, 보안 관련 패턴
3. **P2** — 테스트 누락 (기존 기능에 대한 테스트)
4. **P3** — 코드 품질 (리팩터링, 중복 제거)
5. **P4** — 문서화, 주석

## 반복당 규칙

- **1개 개선만 한다.** 여러 개 동시에 건드리지 않는다
- 개선 전에 반드시 이유를 `claude-progress.txt`에 한 줄로 기록한다
- 구현 후 반드시 검증한다

## 검증 순서 (반드시 모두 통과해야 커밋 가능)

```bash
./gradlew compileKotlin compileTestKotlin   # 0 warnings 필수
./gradlew test                              # 전체 통과 필수
```

테스트 실패 시: 원인 파악 → 수정 → 재검증. 해결 못하면 변경사항 되돌리고 다른 개선점 선택.

## Git 규칙

```bash
# 반드시 dev 기반으로 feature 브랜치 생성
git checkout dev
git checkout -b improve/<짧은-설명>   # 예: improve/add-stream-test

# 작업 완료 후
git add <수정한 파일들>
git commit -m "<타입>: <변경 내용>"   # 예: test: add executeStream tool call coverage

# dev로 머지
git checkout dev
git merge improve/<짧은-설명>
git branch -d improve/<짧은-설명>
```

**절대 main 브랜치를 건드리지 않는다.**

## claude-progress.txt 업데이트

작업 완료 후 반드시 아래 형식으로 추가:

```
[반복 N] <날짜>
- 발견: <어떤 문제를 찾았는지>
- 개선: <무엇을 했는지>
- 결과: <테스트 통과 여부, 커밋 해시>
- 다음 후보: <다음에 봐야 할 것들>
```

## 완료 조건

아래 중 하나라도 해당되면 루프를 종료한다:

- 탐색 후 P0~P2 범위에서 더 이상 의미 있는 개선점이 없을 때
- 연속 3회 동일한 영역에서 개선점을 못 찾을 때

종료 시 출력:
```
<promise>IMPROVEMENTS COMPLETE</promise>
```

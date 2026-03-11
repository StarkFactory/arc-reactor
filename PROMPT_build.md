# Arc Reactor — Build Phase

## 시작 전 (매 반복마다 반드시)

1. claude-progress.txt 읽기 — 이전에 뭘 했는지 파악
2. git log --oneline -10 실행 — 최근 커밋 확인
3. IMPLEMENTATION_PLAN.md 읽기 — 미완료 항목 중 최상위 우선순위 1개 선택

## 역할

너는 IMPLEMENTATION_PLAN.md의 항목을 하나씩 구현하는 에이전트다.
반복당 1개 항목만 처리한다.

## 구현 순서

### 1. 항목 선택
IMPLEMENTATION_PLAN.md에서 P0 → P1 → P2 → P3 → P4 순으로 미완료(- [ ]) 항목 중 첫 번째를 선택한다.

### 2. 코드 탐색 (구현 전 필수)
절대 없을 것이라고 가정하지 않는다. 반드시 먼저 검색한다.
grep, Glob, Read 도구로 관련 파일/클래스/함수를 먼저 찾는다.
이미 유사한 구현이 있으면 재구현하지 말고 기존 코드를 활용한다.

### 3. 복잡한 결정 전
아키텍처 결정이 필요하거나 영향 범위가 넓으면 ultrathink — 구현 전 충분히 추론한다.

### 4. 브랜치 생성
dev 브랜치에서 시작:
  git checkout dev
  git checkout -b improve/[짧은-설명]

### 5. 구현
- CLAUDE.md의 Critical Gotchas 준수
- 메서드 20줄 이하, 줄 120자 이하
- 기존 테스트 assertion 변경 금지
- 새 기능이면 테스트도 함께 작성

### 6. 검증 (통과해야만 커밋 가능)
아래 명령어를 순서대로 실행:
  ./gradlew compileKotlin compileTestKotlin   (0 warnings 필수)
  ./gradlew test                              (전체 통과 필수)

검증 실패 시:
- 원인 파악 후 수정 후 재검증
- 3회 시도 후도 실패하면: git checkout . 으로 되돌리고 다른 항목 선택

### 7. 커밋 & 머지 & 푸시
  git add [수정한 파일들만]
  git commit -m "[타입]: [변경 내용 한 줄]"
  git checkout dev
  git merge improve/[짧은-설명]
  git branch -d improve/[짧은-설명]
  git push origin dev

절대 main 브랜치를 건드리지 않는다.

### 8. IMPLEMENTATION_PLAN.md 업데이트
완료한 항목을 - [ ] 에서 - [x] 로 변경하고 완료 섹션으로 이동:
  - [x] [항목] — 완료 ([커밋 해시])

### 9. claude-progress.txt 업데이트
아래 형식으로 append:
  [반복 N] [날짜 시간]
  - 선택: [IMPLEMENTATION_PLAN.md의 어떤 항목]
  - 구현: [무엇을 했는지]
  - 결과: 테스트 통과 / 커밋 [해시]
  - 다음: [다음 우선순위 항목]

## 완료 조건

IMPLEMENTATION_PLAN.md의 P0~P2 항목이 모두 - [x] 이면 아래를 출력한다:

<promise>IMPROVEMENTS COMPLETE</promise>

99999. 구현 전 반드시 코드 검색 먼저 — 있는 것을 재구현하지 않는다.
999999. 1개 항목만. 욕심내서 여러 개 동시에 고치지 않는다.
9999999. 기존 테스트를 깨지 않는다. 실패하면 원복 후 다른 항목 선택.
99999999. main 브랜치 절대 금지. dev 기반 feature 브랜치에서만 작업.
999999999. AGENTS.md는 수정하지 않는다. 운영 정보만 있는 파일이다.

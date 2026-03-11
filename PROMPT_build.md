# Arc Reactor — Plan & Build

## 시작 전 (매 반복마다 반드시)

1. `claude-progress.txt` 읽기 — 이전에 뭘 했는지 파악
2. `git log --oneline -10` 실행 — 최근 커밋 확인
3. `IMPLEMENTATION_PLAN.md` 읽기 — 현재 상태 파악

---

## 1단계: 계획 (반복 1에서 반드시 실행)

IMPLEMENTATION_PLAN.md에 미완료(`- [ ]`) 항목이 **0개**이면 이 단계를 실행한다.
미완료 항목이 이미 있으면 이 단계를 건너뛰고 2단계로 간다.

### 동작

병렬 서브에이전트를 **3~5회** 호출해서 코드베이스 전체를 탐색한다.
**절대 코드를 수정하지 않는다.** 분석과 기록만 한다.

### 탐색 영역 (서브에이전트에 분배)

1. **컴파일 경고 & 테스트 실패**
   ```bash
   ./gradlew compileKotlin compileTestKotlin 2>&1 | grep -i warning
   ./gradlew test 2>&1 | grep -E "FAILED|ERROR"
   ```

2. **Critical Gotchas 위반** (CLAUDE.md 참조)
   - `catch (e: Exception)` 앞에 `CancellationException` 처리 없는 곳
   - `activeTools = emptyList()` 없이 maxToolCalls 조건만 로깅하는 곳
   - `.forEach {}` 사용 (suspend 컨텍스트에서)
   - `AssistantMessage(` 직접 생성 (builder 미사용)
   - Guard에서 userId null 체크 없는 곳

3. **코드 품질**
   - 메서드 20줄 초과, 줄 120자 초과
   - TODO / FIXME 주석
   - 중복 로직

4. **보안**
   - 인젝션 가능성 (헤더, SQL, 경로)
   - 권한 우회, 정보 노출

5. **성능**
   - 핫패스 비효율, 불필요한 할당, 확장성 병목

6. **테스트 갭**
   - 소스 클래스는 있는데 대응 테스트 파일이 없는 곳
   - 중요 시나리오 테스트 누락

7. **API 문서 갭**
   - `@Operation(summary)` 없는 엔드포인트

### IMPLEMENTATION_PLAN.md 작성 규칙

탐색 결과를 아래 형식으로 작성한다. 최대 **100개** 항목까지.

```markdown
# Implementation Plan
> 마지막 업데이트: <날짜>

## P0 — 즉시 수정 (런타임 크래시, 데이터 손실)
- [ ] <파일명:라인> — <문제 설명>

## P1 — Agent 동작 결함 (잘못된 응답, 무한 루프, 도구 실패 미처리)
- [ ] <파일명:라인> — <문제 설명>

## P2 — 견고성 (에러 복구 실패, 리소스 누수, 동시성 문제)
- [ ] <파일명:라인> — <문제 설명>

## P3 — 보안 (권한 우회, 인젝션, 정보 노출)
- [ ] <파일명:라인> — <문제 설명>

## P4 — 성능 / 코드 품질 / 문서화
- [ ] <파일명:라인> — <문제 설명>

## 완료
(build 단계에서 채워짐)
```

- 항목이 없는 카테고리는 `(없음)`으로 명시한다
- 기존 항목과 중복되면 추가하지 않는다
- 이전 사이클 완료 항목은 보존한다

### 1단계 완료 후

`claude-progress.txt`에 계획 결과를 기록하고 **같은 반복 안에서 2단계로 진행**한다.

---

## 2단계: 구현 (반복 2~N)

### 1. 항목 선택
IMPLEMENTATION_PLAN.md에서 P0 → P1 → P2 → P3 → P4 순으로 미완료(`- [ ]`) 항목 중 첫 번째를 선택한다.

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
완료한 항목을 `- [ ]`에서 `- [x]`로 변경하고 완료 섹션으로 이동:
  - [x] [항목] — 완료 ([커밋 해시])

### 9. claude-progress.txt 업데이트
아래 형식으로 append:
  [반복 N] [날짜 시간]
  - 선택: [IMPLEMENTATION_PLAN.md의 어떤 항목]
  - 구현: [무엇을 했는지]
  - 결과: 테스트 통과 / 커밋 [해시]
  - 다음: [다음 우선순위 항목]

---

## 완료 조건

IMPLEMENTATION_PLAN.md의 P0~P2 항목이 모두 `- [x]`이면 아래를 출력한다:

<promise>IMPROVEMENTS COMPLETE</promise>

---

## 절대 규칙

99999. 구현 전 반드시 코드 검색 먼저 — 있는 것을 재구현하지 않는다.
999999. 1개 항목만. 욕심내서 여러 개 동시에 고치지 않는다.
9999999. 기존 테스트를 깨지 않는다. 실패하면 원복 후 다른 항목 선택.
99999999. main 브랜치 절대 금지. dev 기반 feature 브랜치에서만 작업.
999999999. AGENTS.md는 수정하지 않는다. 운영 정보만 있는 파일이다.

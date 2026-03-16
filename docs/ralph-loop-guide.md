# Ralph Loop 사용 가이드

## 핵심 원리 (3줄 요약)

1. `/ralph-loop "프롬프트"` 를 실행하면 Claude가 작업 후 종료하려 할 때마다 **Stop Hook이 같은 프롬프트를 다시 주입**한다
2. 매 반복마다 **컨텍스트 윈도우는 리셋**된다. Claude의 "기억"은 오직 **파일과 git 히스토리**뿐이다
3. PROMPT.md는 루프의 "프로그램 파일" — 매 반복마다 동일하게 전달되는 불변 지시서다

---

## 동작 순서 (내부 메커니즘)

```
1. /ralph-loop "$(cat PROMPT.md)" 실행
         ↓
2. .claude/ralph-loop.local.md 생성 (프롬프트 저장)
         ↓
3. Claude가 프롬프트 받아서 작업 시작
         ↓
4. Claude가 종료 시도
         ↓
5. Stop Hook 발동 → <promise> 태그 있으면 종료, 없으면:
         ↓
6. 동일한 프롬프트 다시 주입 (iteration + 1)
         ↓
7. Claude: 새 컨텍스트로 시작 → 파일/git으로 이전 작업 파악 → 작업 → 반복
```

---

## 두 페이즈 구조

arc-reactor는 **Plan → Build** 두 단계로 분리되어 있다.

| 페이즈 | 파일 | 역할 |
|--------|------|------|
| Plan | `PROMPT_plan.md` | 코드베이스 탐색 → 문제 발견 → `IMPLEMENTATION_PLAN.md` 작성 |
| Build | `PROMPT_build.md` | `IMPLEMENTATION_PLAN.md` 항목 하나씩 구현 → 검증 → 커밋 |

## 사용법

### 1단계: Plan 루프 (먼저 실행)

```bash
cd ~/ai/arc-reactor
claude
```

Claude Code 안에서:
```
/ralph-loop "$(cat PROMPT_plan.md)" --completion-promise "PLAN COMPLETE" --max-iterations 5
```

→ `IMPLEMENTATION_PLAN.md`가 채워지면 자동 종료.

### 2단계: Build 루프 (Plan 완료 후)

```
/ralph-loop "$(cat PROMPT_build.md)" --completion-promise "IMPROVEMENTS COMPLETE" --max-iterations 80
```

→ `IMPLEMENTATION_PLAN.md` P0~P2 항목 모두 완료 시 자동 종료.

### 중단하고 싶을 때

```
/cancel-ralph
```

---

## 파일별 역할

| 파일 | 역할 | 변경 주체 |
|------|------|-----------|
| `PROMPT_plan.md` | 탐색 지시서 (불변) | 사람 |
| `PROMPT_build.md` | 구현 지시서 (불변) | 사람 |
| `IMPLEMENTATION_PLAN.md` | 발견된 문제 목록 + 완료 추적 | Claude |
| `claude-progress.txt` | 반복 간 기억 | Claude |
| `.claude/ralph-loop.local.md` | 루프 상태 (자동 생성) | Stop Hook |

---

## PRD가 왜 자꾸 언급되냐?

**PRD는 필수가 아니다.** 선택적 패턴 중 하나일 뿐.

- "새 기능을 만들어라" 같은 작업 → 뭘 만들지 명세가 필요 → PRD가 유용
- "코드베이스를 탐색하며 스스로 개선해라" 같은 작업 → PROMPT.md에 탐색 기준만 잘 써주면 충분

arc-reactor처럼 **기존 코드를 자율 개선하는 루프**에는 PRD 불필요.

---

## Claude의 "기억" 구조

```
반복 1: 컨텍스트 리셋 → PROMPT.md 읽기 → 파일/git으로 현황 파악 → 작업 → claude-progress.txt 업데이트 → 커밋
반복 2: 컨텍스트 리셋 → PROMPT.md 읽기 → claude-progress.txt + git log로 이전 작업 파악 → 작업 → ...
반복 N: ...
```

Claude는 이전 대화를 기억하지 못한다.
`claude-progress.txt`와 `git log`가 유일한 연속성이다.

---

## arc-reactor 실행 명령 (복붙용)

```bash
# 새 터미널
cd ~/ai/arc-reactor
claude
```

**Step 1 — Plan (코드 분석, 문제 목록 작성):**
```
/ralph-loop "$(cat PROMPT_plan.md)" --completion-promise "PLAN COMPLETE" --max-iterations 5
```

**Step 2 — Build (문제 하나씩 구현):**
```
/ralph-loop "$(cat PROMPT_build.md)" --completion-promise "IMPROVEMENTS COMPLETE" --max-iterations 80
```

---

## 파라미터 설명

| 파라미터 | 의미 | 권장값 |
|----------|------|--------|
| `--max-iterations` | 최대 반복 횟수 (안전장치) | 80 (≈5~6시간) |
| `--completion-promise` | 이 문자열이 Claude 출력에 나오면 자동 종료 | `"IMPROVEMENTS COMPLETE"` |

PROMPT.md에 아래 지시가 있어야 자동 종료됨:
```
더 이상 개선점이 없으면 <promise>IMPROVEMENTS COMPLETE</promise> 출력
```

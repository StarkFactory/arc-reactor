---
name: qa-watchdog
description: >
  Arc Reactor QA 검증 루프를 cron으로 등록하고 첫 라운드를 즉시 실행한다.
  사용자가 "QA 돌려줘", "검증 루프 시작", "qa-watchdog", "cron QA",
  "프로덕션 준비도 체크", "20분 주기 검증" 등을 언급하면 이 스킬을 사용한다.
  이미 cron이 등록된 상태에서 다시 호출하면 기존 cron을 교체한다.
argument-hint: "[interval: e.g. '20m' or '1h', default 20m]"
---

# QA Watchdog: Cron 등록 + 즉시 실행

이 스킬은 두 가지를 한다:
1. **CronCreate로 반복 cron 등록** — `qa-verification-loop.md`를 주기적으로 실행
2. **첫 라운드 즉시 실행** — 등록 후 바로 1회 실행

실행 로직 자체는 `.claude/prompts/qa-verification-loop.md`에 정의되어 있다.
이 스킬은 그 프롬프트를 cron에 연결하는 **셋업 역할**만 한다.
Claude에서 실행할 때는 기본 모델을 **Sonnet**으로 사용한다.

---

## Step 1: 기존 cron 확인

CronList로 기존 QA 관련 cron이 있는지 확인한다.
이미 있으면 CronDelete로 제거하고 새로 등록한다 (중복 방지).

## Step 2: 간격 결정

`$ARGUMENTS`에서 간격을 파싱한다:
- `20m` → `*/20 * * * *`
- `30m` → `*/30 * * * *`
- `1h` → `0 */1 * * *`
- 비어있으면 기본값 `20m` → `*/20 * * * *`

## Step 3: CronCreate 등록

```
CronCreate(
  cron: "{파싱된 cron 표현식}",
  prompt: ".claude/prompts/qa-verification-loop.md 파일을 Read로 읽고 그대로 실행하라. 추가로 docs/production-readiness-report.md의 \"10. 반복 검증 이력\"에서 마지막 Round 번호를 확인하고 +1로 진행.\n\n보고서 업데이트 시 반드시 커밋+push 한다. 메인 보고서에는 요약만, 상세 내용은 docs/reports/rounds/R{N}.md에 기록한다.",
  recurring: true
)
```

등록 후 사용자에게 알린다:
- Job ID
- 주기 (사람이 읽을 수 있는 형태)
- 세션 한정 + 7일 자동 만료
- 취소 방법: `CronDelete {jobId}`

## Step 4: 첫 라운드 즉시 실행

cron 등록 직후 **`qa-verification-loop.md`를 Read로 읽고 그대로 실행**한다.
첫 cron fire를 기다리지 않는다.

실행 순서:
1. `docs/production-readiness-report.md`의 "10. 반복 검증 이력"에서 마지막 Round 번호 확인 → +1
2. `qa-verification-loop.md`의 Phase 1 지시대로 3-에이전트 병렬 디스패치
3. Phase 2: 결과 종합
4. Phase 3: 커밋 + 메인 보고서 요약 + Round 상세 보고서 + push

# Atlassian MCP 골든 시나리오 (2026-03-03)

## 목적

유저 질문이 들어왔을 때 Atlassian MCP 도구가 의도한 대로 라우팅/실행되는지, 그리고 응답이 운영상 허용 가능한 형태인지 회귀 검증하기 위한 골든 시나리오 세트.

## 시나리오 자산

- 시나리오 정의: `scripts/dev/scenarios/atlassian-golden-scenarios.json`
- 실행기: `scripts/dev/validate-scenario-matrix.py`
- 운영 가이드: `scripts/dev/scenarios/README.md`

## 구성 방식

- 도구 수: 47개 (atlassian MCP 서버 등록 도구 기준)
- 사용자 활동 축: 6개
  - `first_contact`, `follow_up`, `incident`, `daily_ops`, `release`, `handoff`
- 채널 축: 3개
  - `web`, `slack`, `api`
- 페르소나 축: 4개
  - `pm`, `developer`, `qa`, `oncall`
- 총 확장 케이스:
  - `47 x 6 x 3 x 4 = 3384`
  - + 계약(Contract) 체크 2
  - = `3386`

## 기대값(골든 기준)

도구 라우팅 케이스(`tags: golden, atlassian, routing`)는 아래를 모두 만족해야 PASS:

- HTTP 상태: `200`
- 본문 `success`: `true`
- `toolsUsed`에 해당 타깃 도구가 반드시 포함
- 응답 텍스트에 아래 패턴이 없어야 함
  - `unknown error occurred`
  - `an unknown error occurred`
  - `rate limit exceeded`

참고: 권한 부족/허용 프로젝트 제한 등의 비즈니스 오류 메시지는 현재 환경 정책 결과로 허용(도구 라우팅 성공으로 간주).

## 실행 예시

샘플 실행:

```bash
python3 scripts/dev/validate-scenario-matrix.py \
  --base-url http://localhost:8080 \
  --scenario-file scripts/dev/scenarios/atlassian-golden-scenarios.json \
  --include-tags routing \
  --max-cases 40 \
  --seed 2603 \
  --case-delay-ms 250 \
  --report-file /tmp/arc-scenario-golden-sample.json
```

전체 실행:

```bash
python3 scripts/dev/validate-scenario-matrix.py \
  --base-url http://localhost:8080 \
  --scenario-file scripts/dev/scenarios/atlassian-golden-scenarios.json \
  --include-tags golden \
  --case-delay-ms 900 \
  --rate-limit-backoff-sec 65 \
  --report-file /tmp/arc-scenario-golden-full.json
```

## 최신 스모크 결과

- 실행 시각: 2026-03-03
- 커맨드: routing 태그 12건 샘플
- 결과: `12/12 PASS`
- 리포트: `/tmp/arc-scenario-golden-smoke-3386-v2.json`

## 확장 전략

- 수만 건 부하 검증이 필요하면 다음 축을 추가:
  - `locale` (ko/en)
  - `urgency` (normal/high/critical)
  - `tenant` (단일/멀티)
- `validate-scenario-matrix.py`의 `--max-cases`, `--seed`를 조합해 재현 가능한 부분 샘플링을 사용.

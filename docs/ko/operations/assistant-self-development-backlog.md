# Assistant Self-Development Backlog

> 목적: Arc Reactor가 스스로 더 잘 개선되기 위해 필요한 루프 자체의 부족함을 관리하는 backlog
> 범위: eval, backlog, gate, evidence, report, watchdog 운영

---

## 1. 이 문서의 역할

이 문서는 제품 기능 backlog가 아니라 **개선 루프 자체의 backlog**다.

즉, 아래 같은 항목을 다룬다.

- eval set이 너무 작음
- backlog가 실제 사용자 실패를 충분히 반영하지 못함
- quality gate가 문서에만 있고 자동 집계가 약함
- 라운드 보고서가 스키마를 자주 어김
- watchdog가 실패 후 recovery 후보를 충분히 남기지 못함

이 문서는 `qa-verification-loop.md`가 `self_development` Round를 선택할 때 읽는 입력이다.

---

## 2. 항목 스키마

```markdown
### SD-BG-001
- lane: `eval_coverage | backlog_quality | gate_automation | evidence_capture | report_schema | watchdog_recovery`
- symptom: (루프 자체의 부족함 1문장)
- current_limit: (지금 왜 개선 속도를 막는지)
- expected_delta: (무엇이 좋아져야 하는지)
- impact_on_product_axis: `connector_permissions | grounded_retrieval | cross_source_synthesis | safe_action_workflows | admin_productization | employee_value`
- status: `open | in_progress | mitigated | closed`
- evidence:
  - `path:line`
- next_action: (다음 self-development Round에서 할 가장 작은 일 1개)
```

---

## 3. 현재 우선 backlog

### SD-BG-001

- lane: `eval_coverage`
- symptom: Atlassian eval set이 starter set 수준이라 표현 변형과 edge case coverage가 부족하다
- current_limit: 같은 기능이 좋아진 것처럼 보여도 케이스 수가 적어 회귀를 놓칠 수 있다
- expected_delta: Jira/Confluence/Bitbucket 케이스 수와 변형 폭 증가
- impact_on_product_axis: `grounded_retrieval`
- status: `open`
- evidence:
  - `docs/ko/testing/atlassian-enterprise-agent-eval-set.md:1`
- next_action: lane별 한국어 변형 케이스를 최소 2개씩 추가한다

### SD-BG-002

- lane: `backlog_quality`
- symptom: runtime backlog가 seed 수준이라 실제 사용자 실패 패턴의 밀도가 아직 낮다
- current_limit: 무엇을 고칠지 판단할 때 대표 실패가 충분히 구조화되지 않는다
- expected_delta: top missing query, wrong tool family, blocked false positive를 더 잘 분류
- impact_on_product_axis: `admin_productization`
- status: `open`
- evidence:
  - `docs/ko/operations/assistant-runtime-backlog.md:1`
- next_action: backlog 항목에 frequency 또는 recurrence 신호를 추가한다

### SD-BG-003

- lane: `gate_automation`
- symptom: quality gate가 문서에 정의돼 있지만 매 Round 자동 판정은 아직 약하다
- current_limit: 사람이 해석해야 하는 부분이 많아 반복 실행 품질이 흔들릴 수 있다
- expected_delta: gate 계산 근거를 더 기계적으로 남길 수 있어야 한다
- impact_on_product_axis: `admin_productization`
- status: `open`
- evidence:
  - `docs/ko/operations/assistant-quality-gates.md:1`
- next_action: Round 보고서에 gate metric 근거 필드를 더 고정적으로 남긴다

### SD-BG-004

- lane: `report_schema`
- symptom: `R{N}.md` 스키마가 생겼지만 실제 반복 실행에서 필드 누락 가능성이 있다
- current_limit: 보고서 품질이 흔들리면 다음 Round 입력 품질도 흔들린다
- expected_delta: report schema compliance를 self-development gate로 강제
- impact_on_product_axis: `employee_value`
- status: `open`
- evidence:
  - `.claude/prompts/qa-verification-loop.md:459`
- next_action: self-development Round에서 report schema compliance를 필수 gate로 다룬다

### SD-BG-005

- lane: `watchdog_recovery`
- symptom: 실패 Round가 늘어나면 recovery 후보 축적과 stop semantics가 더 중요해진다
- current_limit: 실패 후 복구 전략이 약하면 새벽 반복이 같은 문제를 재시도할 수 있다
- expected_delta: 실패 시 recovery 후보와 축소된 다음 행동이 더 일관되게 남아야 한다
- impact_on_product_axis: `safe_action_workflows`
- status: `open`
- evidence:
  - `.claude/prompts/qa-verification-loop.md:422`
- next_action: 실패 Round 보고서에서 recovery 후보 1개를 필수화한다

---

## 4. 운영 규칙

1. `self_development` Round는 반드시 이 문서의 항목 하나와 연결된다.
2. self-development는 제품 축을 unblock할 때만 허용한다.
3. self-development 결과가 product axis와 연결되지 않으면 단순 내부 정리로 보고 우선순위를 낮춘다.
4. 최근 5개 Round 중 self-development가 2개를 넘으면 다음 Round는 product_improvement가 우선이다.
5. self-development 항목도 닫을 때는 evidence가 필요하다.

# Assistant Quality Gates

> 목적: Arc Reactor를 상용급 assistant이자 스스로 개선 가능한 agent loop로 만들기 위해 각 Round, 새벽 자동 실행, 출시 판단에 공통으로 쓰는 pass/fail 기준
> 기본 범위: Jira / Confluence / Bitbucket 중심 assistant

---

## 1. 왜 필요한가

좋은 프롬프트나 좋은 리팩토링만으로는 상용급 품질을 보장할 수 없다.
반드시 **숫자로 된 gate**가 있어야 한다.

이 문서는 세 단계 gate를 정의한다.

- `round_gate`
- `overnight_gate`
- `release_gate`
- `self_dev_round_gate`

---

## 2. 공통 메트릭

| 메트릭 | 의미 |
|---|---|
| `tool_family_correctness` | Jira/Confluence/Bitbucket 중 기대 tool family를 맞췄는가 |
| `grounded_coverage` | grounded answer에서 실제 근거가 포함되는가 |
| `citation_presence` | 근거 답변에 링크/출처가 있는가 |
| `cross_source_success` | 2개 이상 source를 함께 써야 하는 질문에서 synthesis가 성공하는가 |
| `blocked_false_positive_rate` | 답해야 하는 질문을 잘못 막는 비율 |
| `empty_or_timeout_rate` | 빈 응답 또는 timeout 비율 |
| `safe_action_correctness` | preview/approval/write-policy가 정확히 동작하는가 |
| `evidence_completeness` | baseline, after, test, report evidence가 모두 남았는가 |
| `live_data_sanitization` | 실제 MCP 데이터를 써도 tracked 문서에는 익명화 메타데이터만 남겼는가 |

### 2.1 루프 건강도 메트릭

| 메트릭 | 의미 |
|---|---|
| `eval_case_execution_rate` | 계획한 eval case를 실제로 실행했는가 |
| `backlog_linkage_rate` | 각 Round가 backlog item과 실제로 연결돼 있는가 |
| `report_schema_compliance` | `R{N}.md` 필수 필드가 빠짐없이 채워졌는가 |
| `gate_decision_completeness` | round/overnight/release/self-dev gate 판정이 기록됐는가 |
| `recovery_candidate_presence` | 실패 Round에서 다음 복구 작업 1개가 남았는가 |
| `loop_health_delta` | self-development Round가 루프 자체를 개선했는가 |

### 2.2 실제 데이터 취급 원칙

실제 Atlassian MCP 응답을 활용할 수는 있지만, gate를 통과하려면 아래가 반드시 필요하다.

1. tracked 파일에는 raw data가 없어야 한다
2. evidence는 익명화된 메타데이터여야 한다
3. 실제 응답 저장이 필요하면 `.qa-runtime/` 또는 `.qa-live/` 같은 ignored 경로만 사용한다
4. 실제 데이터 조각이 커밋되면 gate는 즉시 실패다
5. raw 데이터는 **해당 Round 안에서만** 쓰고, 메타데이터 추출 후 삭제해야 한다
6. raw 응답을 다음 Round의 few-shot, fixture, prompt 예시로 승격하면 실패다

---

## 3. Gate 정의

### 3.1 round_gate

한 Round를 commit 가능한지 판단하는 최소 기준이다.

| 메트릭 | 기준 |
|---|---|
| touched eval cases | 최소 2개 실행 |
| tool_family_correctness | touched cases 기준 baseline 악화 금지 |
| grounded_coverage | touched grounded cases 기준 baseline 악화 금지 |
| blocked_false_positive_rate | 새 false positive 추가 금지 |
| empty_or_timeout_rate | baseline 악화 금지 |
| safe_action_correctness | touched action cases는 100% |
| evidence_completeness | 100% |
| live_data_sanitization | 100% |

하나라도 어기면 그 Round는 실패다.

### 3.2 overnight_gate

새벽 자동 반복을 계속 진행해도 되는지 판단하는 기준이다.

| 메트릭 | 기준 |
|---|---|
| tool_family_correctness | 85% 이상 |
| grounded_coverage | 80% 이상 |
| citation_presence | 85% 이상 |
| cross_source_success | 75% 이상 |
| blocked_false_positive_rate | 5% 이하 |
| empty_or_timeout_rate | 10% 이하 |
| safe_action_correctness | 100% |
| evidence_completeness | 100% |
| live_data_sanitization | 100% |

이 gate를 만족하지 못하면 watchdog는 같은 방향을 확장하지 말고 더 작은 fix로 돌아가야 한다.

### 3.3 release_gate

외부 공개 또는 production-ready를 주장하기 전 필요한 기준이다.

| 메트릭 | 기준 |
|---|---|
| tool_family_correctness | 92% 이상 |
| grounded_coverage | 90% 이상 |
| citation_presence | 95% 이상 |
| cross_source_success | 85% 이상 |
| blocked_false_positive_rate | 2% 이하 |
| empty_or_timeout_rate | 3% 이하 |
| safe_action_correctness | 100% |
| evidence_completeness | 100% |
| live_data_sanitization | 100% |

### 3.4 self_dev_round_gate

`self_development` Round에서 추가로 만족해야 하는 기준이다.

| 메트릭 | 기준 |
|---|---|
| self_dev_item linkage | 100% |
| eval_case_execution_rate | 100% |
| backlog_linkage_rate | 100% |
| report_schema_compliance | 100% |
| gate_decision_completeness | 100% |
| loop_health_delta | 최소 1개 명시 |
| product metric guardrail | touched product metric baseline 악화 금지 |
| live_data_sanitization | 100% |

하나라도 어기면 self-development Round는 실패다.

---

## 4. 판정 규칙

### Green

- 모든 해당 gate 충족
- 다음 Round 진행 가능

### Yellow

- baseline 악화는 없지만 overnight_gate 또는 release_gate 일부 미달
- 직접 가치가 큰 작은 수정만 허용

### Red

- round_gate 실패
- push 금지
- 실패 보고서 작성 후 stop

### Blue

- self-development Round가 self_dev_round_gate까지 통과
- 다음 product_improvement Round의 입력 품질이 직접 좋아진 상태

---

## 5. Atlassian 기본 운영 규칙

현재 Arc Reactor의 기본 제품 축은 Atlassian assistant다.
따라서 gate 계산도 아래 우선순위를 따른다.

1. Jira
2. Confluence
3. Bitbucket
4. cross-source synthesis
5. safe action workflow

Swagger나 기타 work 도구는 보조 지표로 다룬다.

self-development도 허용되지만, 기본 원칙은 **product_improvement 우선**이다.
최근 5개 Round에서 self-development가 2개를 넘으면 다음 Round는 product_improvement가 기본이다.

---

## 6. Round 보고서에 남겨야 할 gate 필드

각 `R{N}.md`에는 아래를 남긴다.

```markdown
#### Quality Gate Result
- evaluated_cases:
  - `ATL-JIRA-001`
  - `ATL-SYN-002`
- round_gate: `PASS | FAIL`
- overnight_gate: `PASS | FAIL | NOT_EVALUATED`
- release_gate: `PASS | FAIL | NOT_EVALUATED`
- self_dev_round_gate: `PASS | FAIL | NOT_APPLICABLE`
- gate_notes: (어떤 메트릭이 통과/실패했는지)
```

---

## 7. 해석 원칙

1. 한 번 PASS했다고 release quality로 간주하지 않는다.
2. release_gate는 rolling trend가 뒷받침돼야 한다.
3. cross-source synthesis는 단일 source보다 더 엄격하게 본다.
4. safe action은 부분 점수보다 **정확한 차단과 승인**을 더 중시한다.
5. self-development는 product axis를 unblock할 때만 높은 우선순위를 가진다.

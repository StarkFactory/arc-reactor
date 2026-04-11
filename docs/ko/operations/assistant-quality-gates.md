# Assistant Quality Gates

> 목적: Arc Reactor를 상용급 assistant로 개선할 때 각 Round, 새벽 자동 실행, 출시 판단에 공통으로 쓰는 pass/fail 기준
> 기본 범위: Jira / Confluence / Bitbucket 중심 assistant

---

## 1. 왜 필요한가

좋은 프롬프트나 좋은 리팩토링만으로는 상용급 품질을 보장할 수 없다.
반드시 **숫자로 된 gate**가 있어야 한다.

이 문서는 세 단계 gate를 정의한다.

- `round_gate`
- `overnight_gate`
- `release_gate`

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
- gate_notes: (어떤 메트릭이 통과/실패했는지)
```

---

## 7. 해석 원칙

1. 한 번 PASS했다고 release quality로 간주하지 않는다.
2. release_gate는 rolling trend가 뒷받침돼야 한다.
3. cross-source synthesis는 단일 source보다 더 엄격하게 본다.
4. safe action은 부분 점수보다 **정확한 차단과 승인**을 더 중시한다.

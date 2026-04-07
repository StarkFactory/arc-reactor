# Arc Reactor 에이전트 품질 검증 루프 (20분 주기)

당신은 AI Agent 품질 엔지니어다. 20분마다 **3개 병렬 에이전트**를 동시 실행하여
**응답 품질, 도구 정확도, 의도 분류**를 검증하고 개선한다.

**핵심 원칙:**
- 매 Round 반드시 실제 채팅 API를 호출하여 응답 품질을 측정한다
- 문제 발견 시 코드를 수정한다 (보고서만 쓰지 않는다)
- `docs/qa-agent-quality-guide.md`를 참조 가이드로 사용한다
- push = 완료. 추적 파일은 커밋하지 않는다

---

## Phase 0: 준비

1. `docs/qa-agent-quality-guide.md`를 Read로 읽어 검증 시나리오와 기준을 확인한다
2. `docs/qa-agent-quality-guide.md`의 "8. Round 검증 결과 기록"에서 마지막 Round 번호 확인 → +1
3. 이전 Round에서 FAIL/WARN이 있었다면 해당 시나리오를 이번에 다시 검증

---

## Phase 1: 3 에이전트 동시 디스패치

**반드시 하나의 메시지에 3개 Agent 호출을 동시에 보낸다.**

### Agent 1: 응답 품질 + 도구 정확도 검증

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  Arc Reactor 에이전트의 응답 품질과 도구 정확도를 검증하라.
  
  **반드시 docs/qa-agent-quality-guide.md를 먼저 읽고 시나리오를 선택하라.**
  
  ## 인증
  source ~/.arc-reactor-env
  TOKEN=$(curl -s -X POST http://localhost:18081/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"email\":\"'$ARC_REACTOR_AUTH_ADMIN_EMAIL'\",\"password\":\"'$ARC_REACTOR_AUTH_ADMIN_PASSWORD'\"}' \
    | jq -r '.token')
  
  ## 검증 (최소 5개 시나리오)
  가이드의 A/B/C/D 카테고리에서 골고루 선택하여 실제 API 호출:
  - A (단일 도구) 2개, B (다중 도구) 1개, C (도구 불필요) 1개, D (한글 변형) 1개
  - 매 Round 다른 시나리오를 선택한다 (이전 결과 참조)
  
  curl -s -X POST http://localhost:18081/api/chat \
    -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' \
    -d '{\"message\":\"질문\",\"sessionId\":\"qa-round-N\"}' | jq '{
      content: .content[:200],
      toolsUsed: .toolsUsed,
      grounded: .grounded,
      durationMs: .durationMs,
      blockReason: .blockReason
    }'
  
  ## 판정 기준
  각 시나리오별:
  - 도구 선택: PASS(정확) / FAIL(미사용/잘못된 도구)
  - 응답 품질: 1-5점 (가이드 3.2절 기준)
  - 응답 시간: durationMs 기록
  
  ## 보고 (정확한 수치 포함)
  - 시나리오별 결과표 (시나리오ID, 질문, 기대도구, 실제도구, 품질점수, durationMs)
  - 도구 정확도: X/Y (Z%)
  - 응답 품질 평균: N.N/5
  - FAIL 시나리오 상세 분석
")
```

### Agent 2: 시스템 프롬프트 + 도구 라우팅 개선

```
Agent(subagent_type: "general-purpose", model: "opus", prompt: "
  /Users/jinan/ai/arc-reactor에서 에이전트 응답 품질을 높이기 위한 코드 개선을 수행하라.
  
  **반드시 docs/qa-agent-quality-guide.md를 먼저 읽고 개선 포인트를 파악하라.**
  
  **개선 대상 (우선순위순):**
  
  1. **시스템 프롬프트 최적화**
     - 기존 시스템 프롬프트 확인 (AgentProperties, persona 관련 파일)
     - 도구별 한글 트리거 키워드 명시 여부 확인
     - 응답 구조 가이드 포함 여부 확인
     - Few-shot 예제 추가 여부 검토
  
  2. **도구 라우팅 강화**
     - ToolRoutingConfig 또는 관련 패턴 매칭 코드 확인
     - 한글 변형 패턴 누락 여부 (지라/컨플루/빗버킷/스웨거)
     - 간접 표현 패턴 (이슈 트래커, 위키, 코드 저장소)
  
  3. **의도 분류 개선**
     - IntentClassifier 관련 코드 확인
     - false positive/negative 패턴 보완
  
  4. **응답 품질 규칙**
     - ResponseFilter, QualityRule 관련 코드 확인
     - 구조화/인사이트/후속질문 규칙 강화 가능성
  
  **수정 규칙:**
  - 가장 영향력 있는 1-2개를 실제 수정
  - CLAUDE.md 규칙 준수 필수
  - 수정 후 ./gradlew compileKotlin compileTestKotlin 확인
  
  보고: 분석 결과 + 수정한 파일:라인 + 기대 효과
")
```

### Agent 3: 테스트 + 빌드 검증

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  /Users/jinan/ai/arc-reactor의 빌드 안정성과 테스트를 검증하라.
  
  **필수 검증:**
  1. 빌드: ./gradlew compileKotlin compileTestKotlin
  2. 테스트: ./gradlew test (전체)
  3. 서버 Health: curl -sf http://localhost:18081/actuator/health | jq .status
  4. MCP 서버 상태: 
     curl -sf http://localhost:8081/actuator/health  (swagger)
     curl -sf http://localhost:8086/actuator/health  (atlassian)
  
  **테스트 커버리지 gap 확인:**
  - 최근 수정된 파일 중 테스트가 없는 것
  - 도구 라우팅/의도 분류 관련 테스트 보강 필요 여부
  - 필요하면 테스트 1개 추가 (CLAUDE.md 테스트 규칙 준수)
  
  보고: BUILD(pass/fail), TEST(pass/fail, 총 수), HEALTH 상태, 추가한 테스트(있으면)
")
```

---

## Phase 2: 결과 종합 + 추가 수정

3개 에이전트 결과를 종합:
- Agent 1의 품질 측정 결과 확인
- Agent 2의 코드 개선 확인
- Agent 3의 빌드/테스트 결과 확인
- FAIL 시나리오가 있으면 원인 분석 후 즉시 수정 가능한 것은 수정

---

## Phase 3: 기록 + 커밋 + Push

1. `docs/qa-agent-quality-guide.md`의 "8. Round 검증 결과 기록"에 결과 추가:

```markdown
### Round N (YYYY-MM-DD HH:MM)
- 시나리오: [A1, B2, C1, D3, D5]
- 도구 정확도: X/Y (Z%)
- 응답 품질 평균: N.N/5
- 빌드: PASS/FAIL
- 테스트: PASS (NNNN개)
- 발견 이슈: ...
- 코드 수정: ...
```

2. 커밋 + push:
```bash
git add [수정된 소스/테스트 파일]
git commit -m "{접두사}: {변경 요약}"

git add docs/qa-agent-quality-guide.md
git commit -m "docs: QA Round N — 도구정확도 X%, 응답품질 N.N/5"

git push origin main
```

---

## 검증 기준 요약

| 지표 | PASS | WARN | FAIL |
|------|------|------|------|
| 도구 선택 정확도 | >= 90% | 70-90% | < 70% |
| 응답 품질 평균 | >= 4.0 | 3.0-4.0 | < 3.0 |
| grounding 비율 | >= 70% | 50-70% | < 50% |
| 빈 응답 | 0건 | 1건 (재시도 성공) | 2건+ |
| 빌드 | PASS | - | FAIL |
| 테스트 | 전량 통과 | - | 실패 있음 |
| 서버 Health | 3/3 UP | 2/3 UP | < 2/3 |

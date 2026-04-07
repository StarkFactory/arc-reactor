# Arc Reactor 에이전트 품질 가이드

> **목적**: QA 검증 루프가 참조하는 핵심 가이드. 응답 품질, 도구 정확도, 의도 분류 개선 기준.
> **최종 업데이트**: 2026-04-08

---

## 1. 현재 성능 기준선

| 지표 | 현재 값 | 목표 |
|------|---------|------|
| 도구 선택 정확도 | 89% | 95%+ |
| 한글 패턴 라우팅 | 67% -> 89% (개선됨) | 95%+ |
| 빈 응답 비율 | ~5% (재시도로 커버) | <1% |
| 평균 응답 시간 | 1.3초 | <2초 유지 |
| grounding 비율 | ~70% | 80%+ |
| 캐시 히트율 | 측정 필요 | 30%+ |

---

## 2. 도구 선택 정확도 (Tool Routing)

### 2.1 검증 시나리오 매트릭스

매 Round에서 최소 3개 시나리오를 선택하여 검증한다.

#### A. 단일 도구 호출 (기본)
| # | 질문 (한글) | 기대 도구 | PASS 기준 |
|---|------------|----------|----------|
| A1 | "JAR 프로젝트 이슈 현황 알려줘" | `jira_search_issues` | toolsUsed에 jira 포함 |
| A2 | "MFS 스페이스에서 API 문서 찾아줘" | `confluence_search` | toolsUsed에 confluence 포함 |
| A3 | "jarvis 리포 최근 PR 보여줘" | `bitbucket_list_pull_requests` | toolsUsed에 bitbucket 포함 |
| A4 | "API 스펙 조회해줘" | `swagger_*` | toolsUsed에 swagger 포함 |
| A5 | "오늘 업무 브리핑 해줘" | `work_briefing` 또는 다중 도구 | 업무 관련 정보 반환 |

#### B. 다중 도구 호출 (고급)
| # | 질문 | 기대 도구 | PASS 기준 |
|---|------|----------|----------|
| B1 | "JAR-123 이슈와 관련 문서 같이 찾아줘" | jira + confluence | 2개 이상 도구 사용 |
| B2 | "이번 스프린트 이슈랑 PR 연결해서 보여줘" | jira + bitbucket | 2개 이상 도구 사용 |
| B3 | "API 변경사항이랑 관련 Jira 티켓 확인" | swagger + jira | 2개 이상 도구 사용 |

#### C. 도구 불필요 (Negative)
| # | 질문 | 기대 | PASS 기준 |
|---|------|------|----------|
| C1 | "안녕하세요" | 도구 호출 없음 | toolsUsed 비어있음 |
| C2 | "코틀린에서 코루틴이 뭐야?" | 도구 호출 없음 | 일반 지식 답변 |
| C3 | "1+1은?" | 도구 호출 없음 | 직접 답변 |

#### D. 한글 변형 (라우팅 강건성)
| # | 질문 | 기대 도구 | 테스트 포인트 |
|---|------|----------|-------------|
| D1 | "지라에서 JAR 프로젝트 찾아봐" | jira | "지라" 한글 인식 |
| D2 | "컨플루언스 문서 검색" | confluence | "컨플루언스" 한글 |
| D3 | "빗버킷 PR 목록" | bitbucket | "빗버킷" 한글 |
| D4 | "스웨거 API 확인" | swagger | "스웨거" 한글 |
| D5 | "이슈 트래커에서 내 할당 이슈" | jira | 간접 표현 |
| D6 | "위키에서 문서 찾아줘" | confluence | "위키" = confluence |

### 2.2 도구 선택 실패 시 개선 방법

1. **패턴 추가**: `arc-core/.../tool/ToolRoutingConfig.kt`에 한글 패턴 추가
2. **시스템 프롬프트 강화**: 도구 설명에 한글 트리거 키워드 명시
3. **Few-shot 예제**: 시스템 프롬프트에 도구 선택 예제 추가
4. **의도 분류 규칙**: `IntentClassifier`에 새 패턴 등록

---

## 3. 응답 품질 기준

### 3.1 구조화된 응답

좋은 응답의 조건:
- **직접 답변**: 질문에 대한 명확한 답이 첫 문장에
- **구조화**: 목록, 표, 코드 블록 등 적절한 포맷
- **도구 결과 반영**: 도구에서 가져온 데이터가 답변에 실제로 포함
- **인사이트**: 단순 나열이 아닌 분석/요약/패턴 도출
- **후속 질문 제안**: "더 자세히 알고 싶으시면..."

### 3.2 응답 품질 채점 기준 (1-5)

| 점수 | 기준 |
|------|------|
| 5 | 완벽한 답변 + 인사이트 + 후속 제안 |
| 4 | 정확한 답변 + 구조화 |
| 3 | 답변은 했지만 불완전하거나 구조 부족 |
| 2 | 부분 답변 또는 도구 결과 미반영 |
| 1 | 무관한 답변 또는 빈 응답 |

### 3.3 빈 응답 / 불완전 응답 대응

현재 구현: `EmptyResponseRetryFilter`로 1회 재시도
추가 개선 포인트:
- 재시도 시 temperature 미세 조정
- 빈 응답 패턴 로깅 → 원인 분석
- fallback 메시지 품질 개선

---

## 4. 의도 분류 (Intent Classification)

### 4.1 의도 카테고리

| 의도 | 설명 | 기대 동작 |
|------|------|----------|
| TOOL_USE | 외부 도구 호출 필요 | ReAct 루프 진입 |
| KNOWLEDGE | 일반 지식 질문 | 직접 답변 (도구 불필요) |
| GREETING | 인사/잡담 | 간단 응답 |
| CLARIFICATION | 모호한 질문 | 명확화 요청 |
| MULTI_STEP | 복합 작업 | 계획 수립 후 순차 실행 |

### 4.2 의도 분류 실패 패턴

| 실패 유형 | 예시 | 원인 | 개선 |
|----------|------|------|------|
| False positive | "코루틴 설명해줘" → 도구 호출 | 키워드 과매칭 | negative 패턴 추가 |
| False negative | "이슈 현황" → 직접 답변 | 도구 트리거 누락 | 한글 패턴 보강 |
| 잘못된 도구 | "PR 확인" → jira 호출 | 도구 매핑 오류 | 라우팅 규칙 수정 |

---

## 5. 프롬프트 엔지니어링 (Gemini 2.5 Flash)

### 5.1 Gemini 특화 팁 (리서치 기반)

1. **시스템 프롬프트는 영어 유지**: Gemini 내부적으로 영어 표현을 먼저 처리 → 시스템 지시는 영어, 출력만 한국어
2. **명시적 지시 우선**: 암묵적 기대보다 명시적 지시가 훨씬 효과적
3. **구조화된 섹션 헤더**: `[Language Rule]`, `[Grounding Rules]` 등 대괄호 섹션이 Gemini에 최적
4. **도구 설명에 enum 사용**: description 대신 JSON schema의 enum으로 유효값 제한
5. **Few-shot 3-5개가 sweet spot**: 너무 많으면 오히려 정확도 하락. Negative 예제 2-3개 추가
6. **tool_config ANY 모드**: 워크스페이스 쿼리 첫 호출에 강제 도구 호출 → 도구 미사용 실패 제거
7. **ReAct에서 원래 질문 반복**: 매 iteration마다 원래 사용자 질문을 상기 → 530% 정확도 향상
8. **의도별 temperature 조절**: 사실 조회 0.0-0.1 / 분석 0.3-0.5 / 창작 0.5-0.7
9. **프롬프트 토큰 절약**: 비관련 섹션 조건부 스킵 → Flash 레이턴시+품질 향상
10. **Thinking 모드**: 복잡한 멀티스텝 추론에 Gemini thinking 모드 활용

### 5.2 한글 특화 개선

1. **한국어 동사 어미 정규화**: 검색해줘/검색해/검색하다/검색 모두 매칭되도록 어미 스트리핑
2. **조사 제거 후 임베딩**: 은/는/이/가/을/를 제거 → RAG 유사도 점수 향상
3. **간접 표현 패턴**: "이슈 트래커", "위키", "코드 저장소" 등 도구명 대신 역할 표현
4. **복합 요청 감지**: "같이", "함께", "연결해서", -고, -(으)며, -면서 등 접속 패턴

### 5.3 시스템 프롬프트 체크리스트

- [x] 도구별 한글 트리거 키워드 명시 (Round 1에서 보강)
- [x] 응답 구조 가이드 (appendResponseQualityInstruction 추가)
- [x] 도구 불필요 상황 명시 (인사, 일반 지식)
- [x] 한국어 응답 우선 지시
- [x] 인사이트/분석 추가 지시
- [x] 후속 질문 제안 지시
- [ ] 도구 선택 few-shot negative 예제 추가
- [ ] tool_config ANY 모드 (첫 호출 강제)
- [ ] ReAct iteration에서 원래 질문 반복
- [ ] 의도별 temperature 분리

---

## 6. 검증 API 호출 방법

### 6.1 인증

```bash
source ~/.arc-reactor-env
TOKEN=$(curl -s -X POST http://localhost:18081/api/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ARC_REACTOR_AUTH_ADMIN_EMAIL\",\"password\":\"$ARC_REACTOR_AUTH_ADMIN_PASSWORD\"}" \
  | jq -r '.token')
```

### 6.2 채팅 API

```bash
curl -s -X POST http://localhost:18081/api/chat \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message": "질문 내용",
    "sessionId": "qa-round-N"
  }' | jq '{
    content: .content,
    toolsUsed: .toolsUsed,
    grounded: .grounded,
    durationMs: .durationMs,
    blockReason: .blockReason
  }'
```

### 6.3 결과 판정

```
PASS: 기대 도구 사용 + 응답 품질 3점 이상
WARN: 기대 도구 사용했지만 응답 품질 2점
FAIL: 도구 미사용 또는 잘못된 도구 또는 빈 응답
```

---

## 7. 개선 이력

| 날짜 | 개선 | 영향 |
|------|------|------|
| 이전 | tool-routing 한글 패턴 보강 | 67% → 89% |
| 이전 | 빈 응답 자동 재시도 | 빈 응답 대폭 감소 |
| 이전 | 답변 품질 규칙 추가 | 구조화/인사이트/후속질문 |
| 2026-04-08 | QA 검증 루프 재가동 | 지속적 품질 모니터링 |

---

## 8. Round 검증 결과 기록

> 각 Round 결과를 아래에 추가한다. 형식:
> ### Round N (YYYY-MM-DD HH:MM)
> - 시나리오: [A1, B2, D3 등]
> - 도구 정확도: X/Y (Z%)
> - 응답 품질 평균: N.N/5
> - 발견 이슈: ...
> - 코드 수정: ...
> - 개선 효과: ...

### Round 1 (2026-04-08 01:30)
- 시나리오: [A1, A2, B1, C1, D1]
- 도구 정확도: 4/5 (80%) — B1 다중도구 FAIL (work_briefing 잘못 선택)
- 응답 품질 평균: 2.8/5
- 빌드: PASS | 테스트: 11건 실패 (RAG 분류기 4, 빈응답 2, 캐시 1, RAG주입 1, flaky 3)
- 서버: 3/3 UP | MCP: 2/2 CONNECTED (48 tools)
- 발견 이슈:
  - B1: "같이 찾아줘" 복합 요청 → work_briefing으로 잘못 라우팅
  - A2: 간헐적 unverified_sources 차단 (재시도 시 성공)
  - A1/D1: Jira API 호출 실패 (도구 선택은 정확)
- 코드 수정:
  - SystemPromptBuilder: 빗버킷/스웨거/이슈트래커 한글 힌트 + 응답 품질 지시 추가
  - tool-routing.yml: 한글 변형 패턴 4개소 보강
  - PersonaStore: DEFAULT_SYSTEM_PROMPT 한글 친근 톤으로 개편
  - V50 마이그레이션: DB 페르소나 Reactor로 영구화
- 페르소나 테스트: 인사 PASS, 농담 PASS, 끝말잇기 PASS, Jira 도구선택 PASS

### Round 2 (2026-04-08 02:20)
- 시나리오: [A3, A4, B1재검증, C2, D2, D6]
- 도구 정확도: 3/6 (50%) — A4/D2/D6 FAIL (도구 미호출)
- 응답 품질 평균: 2.0/5
- 빌드: PASS | 테스트: 37건 실패 (core 10, slack 27 — 회귀)
- 서버: 재시작 타이밍으로 일부 검증 불가
- 개선 확인:
  - B1 복합 질문: Round 1 FAIL → **Round 2 PASS** (jira+confluence 양쪽 호출)
  - C2 일반 지식: 4/5 품질 (구조화, 친근한 톤)
  - A3 Bitbucket: 도구 선택 정확
- 발견 이슈 (핵심):
  - D2/D6: 키워드 라우팅 매칭되지만 Gemini Flash가 짧은 쿼리에서 도구 호출 안 함
  - A4: "API 스펙 조회" → swagger 도구 미호출
  - 근본 원인: tool-routing promptInstruction이 LLM에게 전달되지만 강제력 부족
  - 권장: tool_config ANY 모드로 첫 호출 강제 (가이드 5.1 항목 6)
- 테스트 회귀: arc-slack 27건 신규 실패 (SlackMessagingService 관련)

### Round 3 (2026-04-08 대조 실험)
- 시나리오: [D2, D6, D2b, D6b, A5, B2, C3, FUN]
- 도구 정확도: 6/8 (75%)
- 응답 품질 평균: 2.9/5
- 서버: 4/4 UP | MCP: 2/2 CONNECTED
- **대조 실험 결과 (핵심)**:
  - D2 짧은 "컨플루언스 문서 검색" → FAIL (toolsUsed=[], blockReason=unverified_sources)
  - D6 짧은 "위키에서 문서 찾아줘" → FAIL (toolsUsed=[], blockReason=unverified_sources)
  - D2b 구체적 "컨플루언스에서 온보딩 관련 문서 검색해줘" → **PASS** (confluence_search_by_text 호출)
  - D6b 구체적 "위키에서 보안 정책 문서 찾아줘" → **PASS** (confluence_search_by_text 호출, grounded=true)
  - **결론: 짧은 쿼리(목적어 없음)에서 Gemini Flash가 도구 호출 안 함. 구체적 키워드 포함 시 정상 작동**
- 기타 결과:
  - A5 "오늘 업무 브리핑" → PASS (work_morning_briefing 호출, 단 output_too_short)
  - B2 "스프린트 이슈+PR" → WARN (jira_search_issues만 호출, bitbucket 미호출. 단일 도구만 사용)
  - C3 "1+1은?" → PASS (도구 없음, 직접 답변 "2입니다!")
  - FUN "끝말잇기 하자! 사과" → PASS (도구 없음, 친근한 응답 "과자")
- 근본 원인 분석:
  - Gemini Flash는 "무엇을 검색할지" 목적어가 없으면 도구 호출을 건너뜀
  - tool-routing 키워드 매칭은 정상이지만 LLM 단에서 호출 결정을 안 함
  - 권장 개선: (1) tool_config ANY 모드 (2) 짧은 쿼리 감지 시 명확화 요청 프롬프트 (3) 시스템 프롬프트에 "검색 대상 불명확해도 기본 검색 실행" 지시 추가

### Round 4 (2026-04-08 종합 재검증)
- 시나리오: [A1, A2, A4, B1, C1, C2, D2, D6, FUN1, FUN2] — 전체 카테고리
- 도구 정확도: 9/10 (90%) — B1만 WARN (confluence만 호출, jira 미호출)
- 응답 품질 평균: 3.3/5 (역대 최고)
- 서버: UP | MCP: CONNECTED
- **수정 효과 확인**:
  - A4 "API 스펙 조회": R2-3 FAIL → **R4 PASS** (spec_list 호출 성공)
  - D2 "컨플루언스 문서 검색": R2-3 FAIL → **R4 PASS** (confluence_search_by_text 호출)
  - D6 "위키에서 문서 찾아줘": R2-3 FAIL → **R4 PASS** (confluence_search_by_text 호출)
  - 짧은 쿼리 문제 → WorkContextDiscoveryPlanner 보강으로 해결됨
- 부분 회귀:
  - B1 "이슈와 관련 문서 같이 찾아줘": R2 PASS → R4 WARN (confluence만, jira 미호출. 명확화 요청으로 우회)
- 기타 결과:
  - A1/A2: 도구 선택 정확하나 API 오류로 결과 미반환 (인프라 이슈)
  - C1/C2/FUN1/FUN2: 모두 PASS, 친근한 톤 + 구조화 우수
  - grounded=true 비율 0/10 (도구 API 오류로 grounding 불가)
- 추세: 도구 정확도 80% → 50% → 75% → **90%** | 품질 2.8 → 2.0 → 2.9 → **3.3**
- 빌드: PASS | 테스트: 2건 실패 (R3: 5건 → R4: 2건, 60% 감소)
  - ScenarioAssumptionValidationTest: 출력 가드 메시지 변경 미반영
  - SystemPromptBuilderTest: compound question hint 기대값 불일치
- 잔여 개선: (1) 다중 도구 호출 일관성 (2) 도구 API 실패 시 에러 안내 개선 (3) grounding 비율 향상

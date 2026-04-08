# Arc Reactor 에이전트 품질 검증 루프 (15분 주기)

당신은 AI Agent 품질 엔지니어다. 4개 검증 영역을 병렬 실행하여
**응답 품질 4.5+ 유지 및 4.7~5.0 달성**을 목표로 검증하고 개선한다.

**현재 기준선 (R24 달성):**
- 도구 정확도: 100% (9연속)
- 응답 품질: 4.5/5
- grounded 비율: 80%
- Admin API: 100%

**핵심 원칙:**
- 매 Round 반드시 실제 채팅 API를 호출하여 품질을 측정한다
- **4.5 미만으로 떨어지면 원인 분석 + 즉시 수정** (보고서만 쓰지 않는다)
- `docs/qa-agent-quality-guide.md`를 참조 가이드로 사용한다
- push = 완료

---

## Phase 0: 준비

1. `docs/qa-agent-quality-guide.md`의 "8. Round 검증 결과 기록"에서 마지막 Round 번호 확인 → +1
2. 이전 Round에서 3점 이하 시나리오가 있었다면 재검증
3. 이전 Round에서 발견된 미수정 이슈 확인

---

## Phase 1: 인증 + 서버 상태 확인

```bash
curl -sf http://localhost:8080/actuator/health | python3 -c "import sys,json; print('arc-reactor:', json.load(sys.stdin)['status'])"
curl -sf http://localhost:8081/actuator/health | python3 -c "import sys,json; print('swagger-mcp:', json.load(sys.stdin)['status'])"
curl -sf http://localhost:8086/actuator/health | python3 -c "import sys,json; print('atlassian-mcp:', json.load(sys.stdin)['status'])"
curl -sf http://localhost:3001 > /dev/null && echo "admin: UP" || echo "admin: DOWN"

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@arc.io","password":"admin1234"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))")
```

서버가 DOWN이면 Phase 3으로 건너뛰고 상태만 기록한다.

---

## Phase 2: 4개 에이전트 동시 디스패치

**반드시 하나의 메시지에 4개 Agent 호출을 동시에 보낸다.**

### Agent 1: 응답 품질 + 도구 정확도 (10개 시나리오)

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  Arc Reactor 에이전트 품질 검증. QA Round N.
  
  ## 인증
  TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"email\":\"admin@arc.io\",\"password\":\"admin1234\"}' \
    | python3 -c \"import sys,json; print(json.load(sys.stdin).get('token',''))\")
  
  ## 시나리오 (10개 — 매 Round 다르게 구성)
  각 카테고리에서 골고루 선택. 이전 Round 3점 이하 시나리오 반드시 재검증.
  
  카테고리:
  A. Jira 이슈 조회 (3~4개): 프로젝트별 이슈, 마감 임박, 진행 현황, 백로그
  B. Confluence 문서 검색 (2~3개): 키워드 검색, 문서 찾기, 특정 스페이스
  C. Work 도구 (1개): 업무 브리핑, 스탠드업
  D. Swagger (1개): 스펙 조회, 엔드포인트
  E. 일반 지식 (1~2개): 기술 질문 (도구 불필요)
  F. 캐주얼 대화 (1개): 인사, 감사
  G. 이름 호칭 (1개): [이름] prefix 포함
  
  각 시나리오:
  curl -s -X POST http://localhost:8080/api/chat \
    -H 'Authorization: Bearer $TOKEN' -H 'Content-Type: application/json' \
    -d '{\"message\":\"질문\",\"sessionId\":\"qa-round-N-ID\"}' | python3 -c \"
import sys; raw=sys.stdin.buffer.read().decode('utf-8',errors='replace')
import json; d=json.loads(raw,strict=False)
print('tools:', d.get('toolsUsed',[]))
print('grounded:', d.get('grounded',''))
print('ms:', d.get('durationMs',''))
print('content:', str(d.get('content',''))[:600])
\"
  
  ## 엄격 5점 채점
  5: 핵심 먼저 + 구조화(그룹핑/표) + 인사이트(:bulb: 수량요약/추세/이상치/행동제안) + 출처 링크 + 후속 제안
  4: 정확 + 구조화 + 출처. 인사이트 부분적
  3: 답변 했지만 구조/인사이트 부족
  2: 부분 답변
  1: 빈 응답/에러
  
  ## 4.7 달성 체크포인트
  - 인사이트가 모든 도구 호출 시나리오에서 일관되는지
  - 출처 링크가 실제 클릭 가능한 Atlassian URL인지
  - 후속 제안이 구체적인지 (\"더 알려드릴까요?\" 아닌 구체적 제안)
  - Slack mrkdwn 포맷이 올바른지 (*볼드*, <url|텍스트>)
  - 캐주얼 응답에 샘플 질문 제안이 있는지
  
  보고: 결과표 + 도구 정확도 X/10 + 품질 평균 + 3점 이하 원인 분석
")
```

### Agent 2: 코드 개선 (품질 4.7~5.0 달성용)

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  /Users/stark/ai/arc-reactor에서 에이전트 응답 품질을 4.7~5.0으로 높이기 위한 분석/수정.
  
  docs/qa-agent-quality-guide.md를 먼저 읽고 최근 Round 결과에서 3~4점 시나리오 원인 확인.
  
  ## 개선 대상 (4.5→4.7 핵심)
  1. **인사이트 일관성**: 도구 결과 3건 이상이면 반드시 수량요약/추세/이상치/행동제안
     - tools.md, exemplars.md 점검
  2. **Swagger 상세 응답**: spec_list 후 spec_search/spec_detail 체인 안정화
  3. **Confluence 검색 품질**: 키워드 추출 정확도, 결과 요약 수준
  4. **Jira description/comments**: 아직 미구현. 이슈 본문/댓글이 없어서 상세 답변 불가
     - /Users/stark/ai/atlassian-mcp-server에서 JiraIssueInfo에 description 필드 추가 가능 여부
  5. **응답 시간 최적화**: 10초+ 응답 시나리오 원인 분석
  
  **가장 영향력 있는 1~2개만 실제 수정.**
  수정 후 ./gradlew compileKotlin compileTestKotlin 확인.
  보고: 분석 + 수정 파일:라인 + 기대 효과
")
```

### Agent 3: LLM 성능 + 빌드 검증

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  /Users/stark/ai/arc-reactor LLM 성능 + 빌드 검증. QA Round N.
  
  TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"email\":\"admin@arc.io\",\"password\":\"admin1234\"}' \
    | python3 -c \"import sys,json; print(json.load(sys.stdin).get('token',''))\")
  
  ## 1. 응답시간 (각 2회)
  - 단순: '안녕하세요' → durationMs
  - 도구: 'BB30 프로젝트 이슈 현황' → durationMs
  - 복합: '컨플루언스에서 보안 관련 최신 문서 요약' → durationMs
  
  ## 2. pgvector + RAG
  PGPASSWORD=arc psql -h localhost -U arc -d arcreactor -c 'SELECT extname, extversion FROM pg_extension WHERE extname=vector;'
  
  ## 3. 빌드 + 테스트
  ./gradlew compileKotlin compileTestKotlin
  
  ## 4. MCP 서버
  curl -sf http://localhost:8081/actuator/health
  curl -sf http://localhost:8086/actuator/health
  
  보고: 응답시간 + pgvector + 빌드 + MCP 한 줄 요약
")
```

### Agent 4: Admin 연동 + 설정 반영 검증

```
Agent(subagent_type: "general-purpose", model: "sonnet", prompt: "
  Arc Reactor Admin 연동 검증. QA Round N.
  
  TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"email\":\"admin@arc.io\",\"password\":\"admin1234\"}' \
    | python3 -c \"import sys,json; print(json.load(sys.stdin).get('token',''))\")
  
  ## Admin API (8개)
  1. curl -sf http://localhost:3001 > /dev/null && echo 'UP'
  2. curl -s http://localhost:8080/api/personas -H 'Authorization: Bearer $TOKEN'
  3. curl -s http://localhost:8080/api/mcp/servers -H 'Authorization: Bearer $TOKEN'
  4. curl -s http://localhost:8080/api/admin/platform/pricing -H 'Authorization: Bearer $TOKEN'
  5. curl -s http://localhost:8080/api/admin/input-guard/pipeline -H 'Authorization: Bearer $TOKEN'
  6. curl -s http://localhost:8080/api/prompt-templates -H 'Authorization: Bearer $TOKEN'
  7. curl -s 'http://localhost:8080/api/admin/audits?limit=3' -H 'Authorization: Bearer $TOKEN'
  8. curl -s 'http://localhost:8080/api/admin/sessions?page=0&size=3' -H 'Authorization: Bearer $TOKEN'
  
  ## 설정 반영 확인
  MCP CONNECTED → 실제 도구 호출 가능 확인
  
  보고: API X/8 PASS + 설정 반영 PASS/FAIL
")
```

---

## Phase 3: 결과 종합 + 추가 수정

4개 에이전트 결과를 종합:
- Agent 1: 품질 4.5 이상 유지 확인. 3점 이하 있으면 원인 분석
- Agent 2: 코드 개선 확인 (수정사항이 있으면 빌드 재검증)
- Agent 3: LLM/빌드 결과 확인
- Agent 4: Admin 연동 확인
- **품질이 4.5 미만이면 원인 분석 후 즉시 수정**
- **4.5 이상이면 4.7 달성을 위한 개선 포인트 기록**

---

## Phase 4: 기록 + 커밋 + Push

1. `docs/qa-agent-quality-guide.md`의 "8. Round 검증 결과 기록"에 결과 추가:

```markdown
### Round N (YYYY-MM-DD HH:MM)
- 시나리오: [카테고리별 구성]
- 도구 정확도: X/10 (Z%)
- 응답 품질 평균: N.N/5
- 5점: N건 | 4점: N건 | 3점 이하: N건
- grounded=true: X/10 (Z%)
- Admin 연동: X/8 PASS
- LLM 응답시간: 단순 Xms / 도구 Yms / 복합 Zms
- 빌드: PASS/FAIL
- 코드 수정: (있으면)
- 발견 이슈: ...
- 4.7 달성 과제: ...
```

2. 커밋 + push:
```bash
git add [수정된 파일]
git commit -m "{접두사}: {변경 요약}"
git add docs/qa-agent-quality-guide.md
git commit -m "docs: QA Round N — 도구 X%, 품질 N.N/5"
git push origin main
```

---

## 검증 기준 요약

| 지표 | PASS | WARN | FAIL |
|------|------|------|------|
| 도구 선택 정확도 | >= 95% | 90-95% | < 90% |
| 응답 품질 평균 | >= 4.5 | 4.0-4.5 | < 4.0 |
| 5점 비율 | >= 50% | 30-50% | < 30% |
| grounding 비율 | >= 80% | 60-80% | < 60% |
| 3점 이하 시나리오 | 0건 | 1건 | 2건+ |
| Admin 연동 | >= 90% | 70-90% | < 70% |
| 빌드 | PASS | - | FAIL |
| 서버 Health | 4/4 UP | 3/4 UP | < 3/4 |
| LLM 응답시간 (단순) | < 2초 | 2-5초 | > 5초 |
| LLM 응답시간 (도구) | < 10초 | 10-15초 | > 15초 |

## 4.7~5.0 달성 로드맵

### 인사이트 일관성 (4.5 → 4.6)
- 모든 도구 호출 시나리오에서 :bulb: 인사이트 필수
- 3건 이상 결과 → 수량요약 + 이상치 강조 + 행동 제안

### Jira description/comments 추가 (4.6 → 4.7)
- JiraIssueInfo에 description 필드 추가 → "이 이슈가 뭐야?" 질문에 본문 제공
- issue comments READ 도구 추가 → "최근 업데이트?" 질문에 댓글 포함

### Confluence 계층 탐색 (4.7 → 4.8)
- page children/ancestors API → "하위 문서 보여줘" 지원
- page comments API → 결정사항 검색

### 응답 시간 최적화 (4.8 → 4.9)
- 10초+ 시나리오 원인 분석 → 병렬 도구 호출 또는 캐시

### 출처 인용 정밀도 (4.9 → 5.0)
- 인라인 출처 + 하단 출처의 일관성
- Slack Block Kit context 블록 최적화

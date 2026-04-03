# Arc Reactor 기능 인벤토리

> 마지막 업데이트: 2026-04-03
> 기준: 현재 저장소 코드 경로 점검
> 범위: `arc-reactor` 백엔드/플랫폼 기능 전체

---

## 1. 이 문서의 목적

이 문서는 Arc Reactor를 “기능 설명서”로 읽기 쉽게 다시 묶은 문서다.

- 무엇이 사용자 기능인지
- 무엇이 운영/admin 기능인지
- 무엇이 Slack/MCP 같은 연동 기능인지
- 무엇이 기본 활성이고, 무엇이 opt-in인지
- 비슷해 보여도 실제로는 다른 기능이 무엇인지

세부 감사 문서는 아래를 함께 보면 된다.

- [RAG / Vector 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/rag-vector-audit.md)
- [Slack 통합 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/slack-integration-audit.md)
- [MCP 통합 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/mcp-integration-audit.md)

---

## 2. 한눈에 보는 기능 지도

| 영역 | 핵심 질문 | 대표 기능 | 기본 성격 |
|------|-----------|-----------|-----------|
| 사용자 대화 | 사용자는 무엇을 할 수 있나 | 채팅, 스트리밍, 파일 첨부, 세션 조회/삭제 | 핵심 기능 |
| 실행 엔진 | 답변은 어떻게 만들어지나 | Guard, Hook, ReAct 루프, 도구 실행, 도구 선택 | 핵심 기능 |
| 지식/기억 | 과거 대화/문서를 어떻게 쓰나 | 세션 메모리, user memory, RAG, 문서 API | 일부 조건부 |
| 안전/정책 | 무엇을 막고 통제하나 | approval, tool policy, output guard, MCP security | 일부 조건부 |
| 품질/비용 | 품질/비용을 어떻게 관리하나 | cache, semantic cache, budget, model routing, prompt lab | 일부 조건부 |
| 운영/admin | 운영자는 무엇을 할 수 있나 | 감사 로그, 플랫폼 관리, 테넌트 분석, 스케줄러 | 운영 기능 |
| 외부 연동 | 외부 시스템과 어떻게 붙나 | MCP, Slack, webhook, A2A agent card | 선택적 통합 |

---

## 3. 역할별 관점

### 3.1 최종 사용자 관점

최종 사용자가 직접 체감하는 기능은 다음이다.

- `/api/chat`, `/api/chat/stream`으로 질문하고 답변 받기
- 파일 첨부가 허용되면 `/api/chat/multipart`로 멀티모달 대화하기
- `sessionId`를 붙여 이전 대화를 이어가기
- 내 세션 목록, 세션 상세, 세션 export, 세션 삭제
- 사용 가능한 모델 목록 조회
- 인증이 켜진 환경에서는 회원가입, 로그인, 비밀번호 변경, 로그아웃
- 일부 환경에서는 자기 자신의 `user memory` 조회/수정/삭제

### 3.2 운영자 / 관리자 관점

운영자는 다음을 다룬다.

- Prompt Template, Persona, Intent, Prompt Lab
- approval 대기열, tool policy, output guard rules
- 문서 업로드, 문서 검색, RAG ingestion 후보 승인/거부
- MCP 서버 등록/수정/연결/보안 정책/프록시 admin API
- Slack proactive channel, 감사 로그, capabilities, ops dashboard
- 스케줄러 작업 생성/수정/실행/드라이런/실행 이력
- 플랫폼 사용자/역할, 테넌트, 가격 정책, 알림 규칙, 테넌트 분석

### 3.3 플랫폼 / 통합 관점

플랫폼 기능은 다음을 제공한다.

- 외부 MCP 서버를 런타임에 붙여 도구 surface를 확장
- Slack을 사용자 진입 채널로 사용
- webhook으로 실행 결과를 외부 시스템에 전달
- A2A agent card로 다른 에이전트가 이 서버 능력을 발견

---

## 4. 사용자 기능

### 4.1 채팅과 응답 형식

| 기능 | 설명 | 대표 진입점 | 주의점 |
|------|------|-------------|--------|
| 일반 채팅 | 한 번에 최종 답변 반환 | `POST /api/chat` | 가장 기본 엔드포인트 |
| 스트리밍 채팅 | SSE로 `message/tool_start/tool_end/error/done` 전송 | `POST /api/chat/stream` | 스트림 취소 가능 |
| 멀티파트 채팅 | 파일과 메시지를 함께 전송 | `POST /api/chat/multipart` | `multimodal.enabled` 필요, 파일 수/크기 제한 있음 |
| 모델 지정 | 요청별 모델 지정 | `ChatRequest.model` | provider/model 라우팅 대상 |
| system prompt 지정 | 요청별 system prompt 오버라이드 | `ChatRequest.systemPrompt` | persona/template와 우선순위 조합이 있음 |
| persona 지정 | 저장된 persona를 system prompt로 사용 | `ChatRequest.personaId` | 운영자가 미리 persona를 만들어야 함 |
| prompt template 지정 | 활성 버전의 template를 system prompt로 사용 | `ChatRequest.promptTemplateId` | 버전 관리 대상 |
| 구조화 응답 | JSON/TEXT 등 응답 형식 제어 | `responseFormat`, `responseSchema` | 스키마를 강제하려면 응답 모델 지원 필요 |

### 4.2 세션과 모델

| 기능 | 설명 | 대표 진입점 | 주의점 |
|------|------|-------------|--------|
| 세션 기반 문맥 유지 | `sessionId` 기준으로 이전 대화 이어가기 | 요청 metadata | 단기 대화 문맥용 |
| 세션 목록 조회 | 내 세션 목록 조회 | `GET /api/sessions` | 인증 사용자 기준 |
| 세션 상세 조회 | 특정 세션 메시지 확인 | `GET /api/sessions/{id}` | 소유권 검사 |
| 세션 export | JSON 또는 Markdown export | `GET /api/sessions/{id}/export?format=` | 관리자는 모든 세션 접근 가능 |
| 세션 삭제 | 세션과 요약 데이터 정리 | `DELETE /api/sessions/{id}` | 진행 중 요약 작업도 취소 |
| 사용 가능 모델 조회 | 현재 노출된 LLM 모델 목록 확인 | `GET /api/models` | 실제 provider 등록 상태 영향 |

### 4.3 인증

| 기능 | 설명 | 대표 진입점 | 주의점 |
|------|------|-------------|--------|
| 회원가입 | 계정 생성 후 JWT 발급 | `POST /api/auth/register` | self-registration 허용 시에만 |
| 로그인 | 이메일/비밀번호 인증 후 JWT 발급 | `POST /api/auth/login` | 구성된 인증 제공자에 따라 동작 |
| 내 정보 조회 | 현재 로그인 사용자 정보 확인 | `GET /api/auth/me` | JWT 필요 |
| 비밀번호 변경 | 현재 비밀번호 검증 후 변경 | `POST /api/auth/change-password` | 기본 auth provider 기준 |
| 로그아웃 | 현재 JWT 폐기 | `POST /api/auth/logout` | revocation store 사용 |

### 4.4 사용자 메모리

| 기능 | 설명 | 대표 진입점 | 주의점 |
|------|------|-------------|--------|
| 장기 메모리 조회 | facts/preferences/recentTopics 조회 | `GET /api/user-memory/{userId}` | 기본 노출 아님, 본인만 접근 |
| fact 수정 | 단일 fact 업데이트 | `PUT /api/user-memory/{userId}/facts` | `UserMemoryManager` 빈 필요 |
| preference 수정 | 단일 preference 업데이트 | `PUT /api/user-memory/{userId}/preferences` | 자기 메모리만 수정 가능 |
| 전체 메모리 삭제 | 사용자 장기 메모리 삭제 | `DELETE /api/user-memory/{userId}` | 세션 메모리와 별개 |

---

## 5. 지식, 기억, 검색

### 5.1 세션 메모리 vs 사용자 메모리

- `session memory`
  - 대화 흐름을 이어가기 위한 단기 문맥
  - `sessionId` 기준으로 저장
  - 세션 export / 삭제 대상
- `user memory`
  - 사용자별 장기 사실/선호 저장
  - facts, preferences, recentTopics 구조
  - 별도 manager/store가 있을 때만 노출

두 기능은 같은 것이 아니다.

### 5.2 RAG / 문서 / 벡터

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| RAG retrieval | 질문 관련 문서를 검색해 프롬프트에 주입 | 내부 | 모든 질문이 RAG를 타는 건 아님 |
| 문서 업로드 | 텍스트를 청킹 후 벡터 저장소에 적재 | `POST /api/documents` | 관리자 기능 |
| 문서 일괄 업로드 | 여러 문서를 한 번에 적재 | `POST /api/documents/batch` | 운영 적재용 |
| 문서 유사도 검색 | 저장소 검색 상태 검증 | `POST /api/documents/search` | 사용자 채팅과는 별도 |
| 문서 삭제 | 문서/청크 삭제 | `DELETE /api/documents` | 관리자 기능 |
| 청킹 | 긴 문서를 chunk로 분할 | 내부 | RAG 품질 핵심 |
| query transform | HyDE / decomposition 류 질의 보강 | 내부 | 조건부 |
| adaptive query routing | 검색 강도 조절 또는 retrieval 생략 | 내부 | 조건부 |
| parent document retrieval | 청크 주변 문맥 확장 | 내부 | 조건부 |
| context compression | 검색 문맥 압축 | 내부 | 조건부 |
| hybrid retrieval | vector + BM25 혼합 검색 | 내부 | 신규 문서 BM25 증분 반영은 별도 확인 필요 |

### 5.3 Q&A 기반 RAG 적재

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| ingestion policy | 어떤 Q&A를 후보로 저장할지 정책 제어 | `/api/rag-ingestion/policy` | 동적 정책 opt-in |
| candidate 저장 | 사용자 Q&A를 후보로 보관 | 내부 hook | 기본은 자동 학습 아님 |
| candidate 승인/거부 | 후보를 검토해 실제 적재 여부 결정 | `/api/rag-ingestion/candidates/*` | 관리자 리뷰 흐름 |
| 승인 시 벡터화 | 승인된 후보를 문서로 바꿔 `VectorStore.add(...)` | 내부 + 관리자 API | 여기서 실제 vector화 |

정확한 동작은 [RAG / Vector 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/rag-vector-audit.md) 참고.

### 5.4 대화 요약

| 기능 | 설명 | 기본 성격 | 주의점 |
|------|------|-----------|--------|
| conversation summary | 긴 대화를 LLM으로 요약 저장 | opt-in | `memory.summary.enabled=true` 필요 |
| 요약 저장소 | in-memory 또는 JDBC | opt-in | datasource 있으면 JDBC 우선 가능 |

---

## 6. 에이전트 실행 엔진

### 6.1 기본 파이프라인

Arc Reactor의 기본 실행 구조는 아래 순서다.

1. Guard
2. Hook before start
3. ReAct 루프 또는 completion 실행
4. Tool orchestration
5. Hook after complete
6. 최종 응답 반환

### 6.2 핵심 엔진 기능

| 기능 | 설명 | 기본 성격 | 주의점 |
|------|------|-----------|--------|
| Guard pipeline | 입력 검증, rate limit, injection 탐지, 정책 검사 | 핵심 | fail-close |
| Hook system | 실행 전후 확장 포인트 | 핵심 | fail-open |
| ReAct loop | LLM이 도구를 호출하며 단계적으로 해결 | 핵심 | `maxToolCalls`, timeout 중요 |
| Tool call orchestration | 로컬 도구/MCP 도구를 공통 방식으로 실행 | 핵심 | metadata, approval, sanitizer 영향 |
| Tool selection | 사용 후보 도구를 줄여 비용과 노이즈 절감 | 핵심 | 기본 배선은 `AllToolSelector` 또는 `SemanticToolSelector`이며, semantic selector 내부에 fast-path가 있다 |
| MCP tool availability precheck | 죽은 MCP 도구를 루프 전에 걸러냄 | 보조 기능 | 고스트 도구 호출 방지 |
| Tool dependency validation | 도구 의존 관계/라우팅 초기 검증 | 운영 보조 | 설정 품질 확보 |

### 6.3 안전/정책 기능

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| Tool approval | 위험 도구 호출을 실행 전 승인/거부 | `/api/approvals*` | approval enabled 필요, 현재 문서상 인자 수정 기능처럼 이해하면 안 됨 |
| Tool policy | 채널/상황별 write tool 허용/차단 정책 | `/api/tool-policy` | approval과 별도 계층, 동적 policy opt-in |
| Write tool block hook | 정책상 금지된 write tool 차단 | 내부 hook | fail-open hook이 아니라 정책 엔진 기반 |
| Output guard | 모델 결과와 경계 재시도 결과에 민감 정보/금칙 패턴 적용 | 내부 + `/api/output-guard/rules*` | 프레임워크 차원 opt-in이지만 이 저장소 기본 설정은 활성 |
| Dynamic output guard rules | 정규식 규칙 CRUD, audit, simulate | 운영 기능 | dynamic rules opt-in |
| Tool output sanitizer | 도구 출력 정제 | 내부 | 도구 출력 불신 원칙 |
| Canary token | 시스템 프롬프트 유출 탐지 | 조건부 | output guard와 결합 시 더 강함 |

Guard와 Hook은 둘 다 확장 포인트처럼 보이지만 역할이 다르다.

- Guard는 fail-close 보안 계층이다.
- Hook은 확장/관찰 계층이지만, before 계열은 실제로 실행을 거부할 수도 있다.
- output guard는 citation 부착이나 일부 response filter보다 앞선 단계에서 적용된다.

---

## 7. 품질, 비용, 복원력

### 7.1 캐시와 비용 절감

| 기능 | 설명 | 기본 성격 | 주의점 |
|------|------|-----------|--------|
| Response cache | 동일 요청 응답 재사용 | opt-in | user/session/tenant/systemPrompt/tool set까지 반영한 키를 사용하고, 저온도 요청만 저장하며, hit 이후에도 finalizer/output guard를 다시 통과 |
| Semantic response cache | 의미가 비슷한 요청도 재사용 | opt-in | `cache.semantic.enabled`와 Redis/EmbeddingModel 존재 시 시도되며, Redis probe 실패 시 Caffeine으로 폴백, RAG 아님 |
| Prompt caching | Anthropic prompt caching 적용 | opt-in | Anthropic classpath에서만 의미 있음 |
| Budget tracking | 요청/월간 비용 추적 | opt-in | 월간 예산 tracker 제공 |
| Model routing | 비용/품질 기반 동적 모델 라우팅 | opt-in | router bean 교체 가능 |

### 7.2 안정성 / 관측 / 하드닝

| 기능 | 설명 | 기본 성격 | 주의점 |
|------|------|-----------|--------|
| Checkpoint | ReAct 중간 상태 저장 | opt-in | 기본은 in-memory |
| Tool idempotency | 중복 tool call 방지 | opt-in | TTL/maxSize 기반 |
| Prompt drift | 입력/출력 분포 변화 감지 | opt-in | after-complete hook |
| Cost anomaly | 비용 이상치 탐지 | opt-in | sliding window 기반 |
| SLO alert | 지연/오류율 위반 감지 | opt-in | 기본 notifier는 로그 |
| Circuit breaker / fallback | LLM 호출 실패 시 회로 차단과 단순 모델 폴백 | 일부 기본/조건부 | fallback은 전체 ReAct 재실행이 아니라 plain LLM fallback에 가깝다 |
| Tracing / metrics | 실행 단계 span과 metric 기록 | 핵심 | 운영 분석 기반 |
| Runtime preflight | PostgreSQL, auth, health probe 등 시작 전 검증 | 기본 | 잘못된 운영 설정을 시작 시 차단 |

---

## 8. 운영 / 관리자 기능

### 8.1 운영 표면

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| capabilities | 현재 노출된 관리자 API 경로 목록 | `GET /api/admin/capabilities` | raw request mapping dump 성격 |
| admin audits | 관리자 작업 이력 조회 | `GET /api/admin/audits` | proactive/MCP/security 변경 추적 |
| ops dashboard | MCP, 스케줄러, 승인, trust, metric 스냅샷 | `GET /api/ops/dashboard` | 운영 요약 |
| metrics names | 운영 메트릭 이름 목록 | `GET /api/ops/metrics/names` | 대시보드 wiring 보조 |

### 8.2 플랫폼 admin

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| platform health | 플랫폼 헬스 대시보드 | `/api/admin/platform/health` | `admin.enabled=true` 필요 |
| cache stats | 응답 캐시 상태 확인 | `/api/admin/platform/cache/stats` | 품질 자체를 증명하진 않음 |
| vectorstore stats | vector store 존재 중심 상태 확인 | `/api/admin/platform/vectorstore/stats` | 검색 품질 검증 아님 |
| 사용자 조회/역할 변경 | 사용자 조회와 role 변경 | `/api/admin/platform/users/*` | 자기 자신 권한 축소 제한 |
| 테넌트 CRUD | 생성, 조회, suspend, activate | `/api/admin/platform/tenants*` | 플랫폼 운영 기능 |
| 가격 정책 | 모델별 가격 CRUD | `/api/admin/platform/pricing` | 비용 계산 기반 |
| alert rules | 규칙 CRUD, evaluate, resolve | `/api/admin/platform/alerts*` | 운영 경보 |

### 8.3 테넌트 admin

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| overview | 요청, 성공률, APDEX, SLO, 비용 | `/api/admin/tenant/overview` | admin enabled 필요 |
| usage | 시계열, 채널 분포, 상위 사용자 | `/api/admin/tenant/usage` | time range 지원 |
| quality | 지연 백분위, 오류 분포 | `/api/admin/tenant/quality` | |
| tools | 도구 랭킹, 느린 도구 | `/api/admin/tenant/tools` | |
| cost | 모델별 비용 | `/api/admin/tenant/cost` | |
| slo | tenant SLO 상태 | `/api/admin/tenant/slo` | tenant config 영향 |
| alerts | 활성 알림 목록 | `/api/admin/tenant/alerts` | |
| quota | 월간 quota vs usage | `/api/admin/tenant/quota` | |
| CSV export | execution/tool export | `/api/admin/tenant/export/*` | 일부는 full ADMIN 필요 |

### 8.4 메트릭 수집

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| MCP health event 수집 | 외부 MCP 상태 이벤트 수집 | `/api/admin/metrics/ingest/mcp-health` | 버퍼 가득 차면 503/drop |
| tool call event 수집 | 도구 호출 이벤트 수집 | `/api/admin/metrics/ingest/tool-call` | 외부 소스 연계 가능 |
| eval result event 수집 | 평가 결과 수집 | `/api/admin/metrics/ingest/eval-result` | 분석 데이터로 사용 |

### 8.5 운영 실험 / 개선

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| Prompt Template | 버전 관리되는 system prompt | `/api/prompt-templates*` | 버전 activate/archive 흐름 |
| Persona | 이름 붙은 system prompt 프로필 | `/api/personas*` | template 연결 가능 |
| Intent | intent 정의와 프로필 관리 | `/api/intents*` | `intent.enabled=true` 필요 |
| Prompt Lab | prompt 실험 생성, 실행, 취소, 분석, 리포트, activate, 자동 최적화 | `/api/prompt-lab*` | 비동기 실행, 동시성 제한 있음 |
| Feedback | 사용자 평가 수집, 조회, export, runId 기반 메타데이터 보강 | `/api/feedback*` | `feedback.enabled=true` 필요, Slack reaction 저장과도 연결 가능 |

### 8.6 자동화 / 스케줄러

| 기능 | 설명 | 대표 API | 주의점 |
|------|------|----------|--------|
| scheduled MCP tool run | 스케줄에 따라 MCP 도구 실행 | `/api/scheduler/jobs*` | `jobType=MCP_TOOL` |
| scheduled agent run | 스케줄에 따라 전체 agent 실행 | `/api/scheduler/jobs*` | `jobType=AGENT` |
| 즉시 실행 | 수동 trigger | `/api/scheduler/jobs/{id}/trigger` | 실행 결과 반환 |
| dry-run | 부작용 없는 실행 시뮬레이션 | `/api/scheduler/jobs/{id}/dry-run` | 상태 기록/알림 제외 |
| 실행 이력 조회 | 최근 실행 결과 확인 | `/api/scheduler/jobs/{id}/executions` | 운영 디버깅용 |
| Slack/Teams sink | 실행 결과를 Slack/Teams로 발송 | job 설정 | 실행 문맥 자체가 Slack/Teams가 되진 않음 |

---

## 9. 외부 연동 기능

### 9.1 MCP

MCP는 Arc Reactor가 외부 툴 서버를 런타임에 붙여 tool surface를 확장하는 기능이다.

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| 서버 등록/수정/삭제 | 런타임 MCP 서버 관리 | `/api/mcp/servers*` | `HTTP` transport는 거부, `SSE/STDIO` 중심 |
| 연결/해제 | connect / disconnect 제어 | `/api/mcp/servers/{name}/*` | 상태는 `PENDING/CONNECTING/CONNECTED/FAILED/DISCONNECTED` |
| 보안 정책 | allowlist, max output length 관리 | `/api/mcp/security` | 정책 변경 시 manager에 즉시 재적용 |
| access-policy 프록시 | upstream MCP admin policy 프록시 | `/api/mcp/servers/{name}/access-policy` | upstream admin token/HMAC 필요 |
| preflight 프록시 | upstream readiness 체크 | `/api/mcp/servers/{name}/preflight` | 자체 health가 아니라 upstream admin API 호출 |
| swagger catalog 프록시 | Swagger source lifecycle 관리 | `/api/mcp/servers/{name}/swagger/sources*` | upstream이 admin contract를 구현해야 함 |
| startup restore | 저장된 서버 복원과 auto-connect | 내부 | security allowlist 영향 |
| auto reconnect | 실패 서버 백오프 재시도 | 내부 | opt-in 성격의 운영 보조 |
| health pinger | CONNECTED 서버 주기 점검 | 내부 | 도구 없음 상태를 끊김으로 간주 |

정확한 동작과 제약은 [MCP 통합 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/mcp-integration-audit.md) 참고.

### 9.2 Slack

Slack은 이 프로젝트의 가장 중요한 사용자 채널 중 하나다. 상세는 [Slack 통합 감사 문서](/Users/jinan/ai/arc-reactor/docs/ko/reference/slack-integration-audit.md) 참고.

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| Events API | mention/thread/event 기반 대화 | `POST /api/slack/events` | signature verify, dedup, tracked thread 영향 |
| Slash commands | 명령 기반 질의 | `POST /api/slack/commands` | 이벤트 경로와 응답 흐름이 다름 |
| Socket Mode | WebSocket 기반 ingress | 내부 | interactive envelope은 제한적 |
| proactive channels | 선제 응답 허용 채널 관리 | `/api/proactive-channels*` | in-memory store 기본 |
| reminder / reaction feedback | 사용자 편의 기능 | 내부 | 일부 경로는 best-effort/in-memory |
| Slack Tools | 에이전트가 Slack API를 직접 호출 | 로컬 도구 | Slack chat ingress와 별개 opt-in |

### 9.3 기타 연동

| 기능 | 설명 | 대표 표면 | 주의점 |
|------|------|-----------|--------|
| webhook notification | 실행 결과를 외부 URL로 POST | after-complete hook | opt-in |
| A2A agent card | `/.well-known/agent-card.json` 노출 | 공개 엔드포인트 | `a2a.enabled=true` 및 provider 필요 |

---

## 10. 저장소 / 데이터 관점

| 데이터 | 저장 주체 | 대표 저장소 | 설명 |
|--------|-----------|-------------|------|
| 세션 대화 이력 | `ConversationManager` / `MemoryStore` | InMemory 또는 JDBC | `sessionId` 기준 단기 문맥 |
| 세션 요약 | `ConversationSummaryStore` | InMemory 또는 JDBC | 긴 세션 압축 요약 |
| 사용자 장기 메모리 | `UserMemoryStore` | 구현체 의존 | facts/preferences/recentTopics |
| 벡터 문서 | `VectorStore` | PGVector 등 | RAG 문서 저장소 |
| RAG ingestion 후보 | `RagIngestionCandidateStore` | JDBC 중심 | 승인 전 후보 큐 |
| 승인 대기 요청 | `PendingApprovalStore` | 메모리 또는 영속 저장소 | HITL 큐 |
| 피드백 | `FeedbackStore` | 메모리 또는 JDBC | 평가 데이터 |
| MCP 서버 정의 | `McpServerStore` | 메모리 또는 JDBC | 런타임 서버 설정 |
| MCP 보안 정책 | `McpSecurityPolicyStore` | 메모리 또는 JDBC | allowlist / output length |
| Prompt Lab 실험 | `ExperimentStore` | 구현체 의존 | 실험 기록 |
| 스케줄 이력 | `ScheduledJobExecutionStore` | 구현체 의존 | 예약 작업 실행 결과 |

---

## 11. 대표 활성화 조건

| 기능 | 대표 조건 |
|------|-----------|
| RAG | `arc.reactor.rag.enabled=true` + `VectorStore` 빈 |
| RAG ingestion | `arc.reactor.rag.ingestion.enabled=true` |
| RAG ingestion 동적 정책 | `arc.reactor.rag.ingestion.dynamic.enabled=true` |
| Multimodal chat | `arc.reactor.multimodal.enabled=true` |
| User memory API | `UserMemoryManager` 빈 존재 |
| Memory summary | `arc.reactor.memory.summary.enabled=true` |
| Approval | `arc.reactor.approval.enabled=true` |
| Tool policy 동적 관리 | `arc.reactor.tool-policy.dynamic.enabled=true` |
| Output guard | `arc.reactor.output-guard.enabled=true` |
| Feedback | `arc.reactor.feedback.enabled=true` |
| Prompt Lab | `arc.reactor.prompt-lab.enabled=true` |
| Intent | `arc.reactor.intent.enabled=true` |
| Budget | `arc.reactor.budget.enabled=true` |
| Checkpoint | `arc.reactor.checkpoint.enabled=true` |
| Model routing | `arc.reactor.model-routing.enabled=true` |
| Prompt caching | `arc.reactor.llm.prompt-caching.enabled=true` + Anthropic classpath |
| Semantic cache | `arc.reactor.cache.semantic.enabled=true` + Redis/EmbeddingModel classpath와 빈. Redis probe 실패 시 local cache로 폴백 가능 |
| Tool idempotency | `arc.reactor.tool-idempotency.enabled=true` |
| Prompt drift | `arc.reactor.prompt-drift.enabled=true` |
| Canary token | `arc.reactor.guard.canary-token-enabled=true` |
| Cost anomaly | `arc.reactor.cost-anomaly.enabled=true` |
| SLO alert | `arc.reactor.slo.enabled=true` |
| Multi-agent | `arc.reactor.multi-agent.enabled=true` |
| Scheduler | `arc.reactor.scheduler.enabled=true` |
| Slack | `arc.reactor.slack.enabled=true` + 토큰/adapter |
| Slack Tools | `arc.reactor.slack.tools.enabled=true` |
| A2A agent card | `arc.reactor.a2a.enabled=true` 계열 설정 |
| MCP health pinger | `arc.reactor.mcp.health.enabled=true` |
| Platform admin | `arc.reactor.admin.enabled=true` |

---

## 12. 자주 헷갈리는 점

- `session memory`와 `user memory`는 다르다.
- `RAG`, `vector`, `embedding`, `semantic cache`, `semantic tool discovery`는 모두 다른 기능이다.
- 사용자 Q&A는 기본적으로 자동 vector화되지 않는다.
- `multipart chat`은 파일 첨부 대화이지, 자동 문서 학습이 아니다.
- `rag.enabled=true`가 곧 모든 요청이 RAG를 탄다는 뜻은 아니다.
- `tool policy`와 `tool approval`은 같은 기능이 아니다. 정책 차단이 먼저 일어날 수 있고, 승인 기능은 그와 별개다.
- Slack 채널에서 들어왔다고 해서 Slack API tool이 자동으로 켜지는 것은 아니다.
- MCP admin 프록시는 Arc Reactor가 upstream 기능을 대신 구현하는 것이 아니라, upstream admin API를 대신 호출해 주는 표면이다.
- MCP admin proxy URL 검증은 runtime SSE URL 검증보다 좁다. 특히 `adminUrl` 설정 검증 범위는 별도 주의가 필요하다.
- 동적 MCP security policy는 allowlist와 output length 중심이며, `allowedStdioCommands`까지 완전히 동일한 수준으로 관리하지는 않는다.
- `/api/admin/platform/vectorstore/stats`는 이름과 달리 검색 품질을 보장하지 않는다.
- Prompt caching은 범용 기능이 아니라 현재 Anthropic 전용 caching integration이다.
- 스케줄러의 Slack 전송은 결과 sink일 뿐, 실행 문맥 자체가 Slack 대화가 되는 것은 아니다.
- `maxToolCalls` 초과는 항상 hard fail이 아니다. 구현은 도구를 비우고 final answer를 유도하는 쪽에 가깝다.
- assistant/tool 응답은 message-pair 무결성을 전제로 저장된다.

---

## 13. 대표 근거 파일

### 13.1 사용자/API

- `arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/MultipartChatController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/SessionController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/AuthController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/UserMemoryController.kt`

### 13.2 지식/기억

- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorRagConfiguration.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/DocumentController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionCandidateController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionPolicyController.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/UserMemoryConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/MemorySummaryConfiguration.kt`

### 13.3 실행 엔진 / 품질

- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/AgentExecutionCoordinator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/StreamingExecutionCoordinator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/agent/impl/ToolCallOrchestrator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorPreflightConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorOutputGuardConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/BudgetConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/CheckpointConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ModelRoutingConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorSemanticCacheConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/PromptCachingConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/PromptDriftConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/ArcReactorCanaryConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/CostAnomalyConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/SloAlertConfiguration.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/autoconfigure/MultiAgentConfiguration.kt`

### 13.4 운영 / admin

- `arc-web/src/main/kotlin/com/arc/reactor/controller/ApprovalController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/FeedbackController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/PromptTemplateController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/PersonaController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/IntentController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/PromptLabController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/OpsDashboardController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/AdminCapabilitiesController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/AdminAuditController.kt`
- `arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/PlatformAdminController.kt`
- `arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/TenantAdminController.kt`
- `arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/MetricIngestionController.kt`

### 13.5 연동 / 확장

- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpServerController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpSecurityController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpAccessPolicyController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpPreflightController.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/mcp/McpManager.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/mcp/McpConnectionSupport.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/mcp/McpReconnectionCoordinator.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/mcp/McpHealthPinger.kt`
- `arc-web/src/main/kotlin/com/arc/reactor/controller/AgentCardController.kt`
- `arc-core/src/main/kotlin/com/arc/reactor/hook/impl/WebhookNotificationHook.kt`
- `arc-slack/src/main/kotlin/com/arc/reactor/slack/gateway/SlackSocketModeGateway.kt`

---

## 14. 문서 유지 원칙

- 기능 이름만 적지 말고 “누가 체감하는지”를 같이 적는다.
- 기본 활성인지 opt-in인지 반드시 적는다.
- API 표면과 내부 기능을 섞지 말고 구분한다.
- RAG / vector / embedding / semantic cache / semantic tool selection은 항상 분리 설명한다.
- Slack / MCP처럼 표면이 넓은 기능은 중앙 문서에서 요약하고, 별도 감사 문서로 상세를 분리한다.

# Arc Reactor Notion 문서 생성 루프 (notion-docs-loop)

> **이 루프는 Arc Reactor 프로젝트의 모든 실제 구현된 기능을 Notion 페이지로 나눠 문서화한다.**
> 매 iteration = **정확히 1 페이지 완성**. 순서대로 진행하며, 사용자는 언제든 중단/재개 가능.
> 대상 독자는 사내 회사측 전달용 **기업 가이드 문서**이며, README와는 다른 보고서 형식이다.
> 이 루프를 실행할 때는 기본적으로 Sonnet 모델을 쓴다 (Opus는 복잡한 리팩토링에만 예외).

---

## §0 이 루프의 목표

1. **완전성** — Arc Reactor의 실제로 구현된 모든 기능을 빠짐없이 문서화
2. **정확성** — 추측 금지. 모든 기술 주장은 실제 코드 파일 경로 + 줄 번호로 뒷받침
3. **가독성** — 기술자(개발자)와 비기술자(PM/운영자/경영진) 양쪽이 이해 가능한 설명
4. **한국어** — 모든 페이지는 한국어로 작성 (프로젝트의 KDoc 스타일과 일치)
5. **Notion 네이티브** — callout, toggle, code block, table, divider 등 Notion 요소를 적극 활용하여 "예쁘게"
6. **점진 완성** — 매 iteration 1 페이지씩 완성. 중간 실패 시 다음 iteration에서 재개
7. **제품 포지셔닝 반영** — 모든 페이지에 "기업 직원에게 어떤 가치인가" 관점 필수

---

## §1 Notion 부모 페이지

- **페이지 이름**: `Reactor (AI Agent)`
- **페이지 ID**: `340072b5-48c3-8028-8aa0-d529dcaa9795`
- **URL**: <https://www.notion.so/340072b548c380288aa0d529dcaa9795>
- **부모 계층**: `내 워크스페이스 홈 → 아슬란 → Reactor (AI Agent)`
- **상태**: 초기 빈 페이지. **모든 하위 페이지는 이 ID 아래에 생성한다.**
- **절대 금지**: 이 parent 밖으로 페이지 생성 금지. 중복 제목 페이지 생성 금지.

---

## §2 제품 포지셔닝 — 모든 페이지에 스며들어야 할 맥락

**Arc Reactor는 코딩 에이전트가 아니다.** 아래 포지셔닝을 모든 페이지 설명에 반영한다.

### 2.1 정체성

- **기업 전용 AI 비서 프레임워크** — 사내 직원이 일상 업무에서 사용하는 assistant
- **Slack / Web UI**로 대화하며, 흩어진 사내 업무/정보를 모아 답변
- **목표**: 직원이 여러 툴 사이를 왔다갔다 하지 않아도 **한 곳에서 질문 → 답변**
- **대상 사용자**: 사내 직원 300+명 (PM, 개발자, QA, 운영, 경영진 모두)
- **대상 운영자**: IT/보안/DevOps 팀

### 2.2 핵심 차별점

- **Read-only MCP connector 기반** — MCP를 통해 사내 모든 서비스를 read-only로 연결
- connector만 추가하면 새 사내 시스템이 자동으로 에이전트에 노출됨
- 쓰기 작업은 `ToolApprovalPolicy` + `PendingApprovalStore`를 통한 **Human-in-the-loop 승인 경로**로만 수행
- **Spring/Kotlin 진영의 사실상 유일한 진지한 OSS 기업 에이전트 runtime**
- **한국어 first 로컬라이제이션** (한국어 JQL 변환 등)

### 2.3 비교 대상

| 유형 | 예시 |
|---|---|
| 상용 SaaS 경쟁 | Glean, Moveworks, Atlassian Rovo, Microsoft Copilot Studio |
| OSS 경쟁 | Onyx (Danswer), Dify, Khoj, Flowise, LangChain/LangGraph |
| Spring/JVM 진영 | **사실상 Arc Reactor가 유일** |

### 2.4 현재 연결된 MCP 서버 (필수 문서화 대상)

#### atlassian-mcp-server
- **로컬 경로**: `/Users/jinan/ai/atlassian-mcp-server`
- **연결 용도**: 이슈 조회, 문서 검색, PR 상태, **한국어 → JQL 변환**
- **서비스**: Jira, Confluence, Bitbucket
- **별도 레포** — 이 Notion 문서에서 해당 MCP의 README를 참조하여 자체 페이지 작성

#### swagger-mcp-server
- **로컬 경로**: `/Users/jinan/ai/swagger-mcp-server`
- **연결 용도**: **OpenAPI/Swagger spec을 MCP 도구로 동적 노출**
- **서비스**: 사내 REST API 전반 (spec 등록된 모든 서비스)
- **별도 레포** — 동일 방식으로 자체 페이지 작성

#### 미래 확장 (문서에 언급)
- Naver Works, KakaoWork, Jandi, Dooray
- 사내 위키, HR 시스템, 인사 시스템, 일정 시스템
- **원칙**: MCP connector만 추가하면 에이전트는 자동으로 새 시스템을 인식하고 질의 가능

### 2.5 모든 페이지에 반드시 포함할 것

> **"이 기능이 기업 직원에게 어떤 가치를 주는가?"**
> 1~2줄이라도 **모든 페이지**에 명시한다. 기술적 설명만 있으면 불완전한 페이지로 간주.

---

## §3 페이지 목록 (53개) — 진행 상태 트래커

각 페이지의 **Status**와 **Notion Page ID**는 iteration마다 업데이트한다.
- Status 값: `TODO | IN_PROGRESS | DONE`
- Page ID: skeleton 생성 후 채움. 완성 후에도 ID는 유지.

### 🏛️ Phase 1 — 기초 & 제품 포지셔닝 (5 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 1 | 프로젝트 소개 & 제품 포지셔닝 | DONE | `340072b5-48c3-8177-a936-e1fe111d37a4` |
| 2 | 아키텍처 & 모듈 구조 | DONE | `340072b5-48c3-8185-af4c-fd6267614588` |
| 3 | 기술 스택 & 의존성 | DONE | `340072b5-48c3-815c-9d4d-d7bf8a1c7438` |
| 4 | 빠른 시작 & 빌드 방법 | DONE | `340072b5-48c3-815f-a3ed-fc33822b574e` |
| 5 | 사용 사례 & 직원 가치 시나리오 | DONE | `340072b5-48c3-8111-877d-f7f1bb97a431` |

### ⚙️ Phase 2 — Agent 실행 엔진 (7 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 6 | Agent 실행 아키텍처 개요 | DONE | `340072b5-48c3-812d-a8ce-e2bee6aeaac9` |
| 7 | ReAct 실행 루프 (overview) | DONE | `340072b5-48c3-81b9-83c5-d981c6043095` |
| 7a | ├ ReAct — 기본 흐름 & 루프 단계 | DONE | `340072b5-48c3-8121-9525-ce60f41c0f21` |
| 7b | ├ ReAct — Budget 추적 & 소진 처리 | DONE | `340072b5-48c3-81f8-b3fc-c23ec1350d97` |
| 7c | └ ReAct — Tool 에러 & 재시도 | DONE | `340072b5-48c3-817c-ade7-f834ca2b9e2a` |
| 8 | Streaming 실행 경로 | DONE | `340072b5-48c3-8198-9bd8-e5b1b20bc7ad` |
| 9 | Plan-Execute 모드 | DONE | `340072b5-48c3-8192-8706-dd377dcd9257` |
| 10 | Tool 시스템 & 병렬 실행 | DONE | `340072b5-48c3-810e-b8b0-c634aba58662` |
| 11 | Tool 선택 & 의도 분류 (Intent) | IN_PROGRESS | _pending_ |
| 12 | Cost & Budget 관리 | TODO | _pending_ |

### 🧠 Phase 3 — 지식 & 메모리 (4 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 13 | RAG Pipeline (Hybrid 검색) | TODO | _pending_ |
| 14 | Document Ingestion 파이프라인 | TODO | _pending_ |
| 15 | Conversation Memory (계층적) | TODO | _pending_ |
| 16 | Semantic Response Cache | TODO | _pending_ |

### 🛡️ Phase 4 — 안전 & 거버넌스 (4 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 17 | Guard Pipeline (5단계 입력 가드) | TODO | _pending_ |
| 18 | Output Guard & PII 마스킹 | TODO | _pending_ |
| 19 | Prompt Injection Detection | TODO | _pending_ |
| 20 | Approval Workflow (Human-in-the-loop) | TODO | _pending_ |

### 📊 Phase 5 — 관측성 & 비용 (3 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 21 | Hooks 시스템 (4 hook points) | TODO | _pending_ |
| 22 | Metrics & Tracing | TODO | _pending_ |
| 23 | Doctor Diagnostics & Health | TODO | _pending_ |

### 🔌 Phase 6 — MCP 생태계 (4 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 24 | MCP 통합 아키텍처 & McpManager | TODO | _pending_ |
| 25 | **atlassian-mcp-server** (별도 레포) | TODO | _pending_ |
| 26 | **swagger-mcp-server** (별도 레포) | TODO | _pending_ |
| 27 | MCP Connector 확장 가이드 — 사내 신규 서비스 추가 | TODO | _pending_ |

### 🌐 Phase 7 — API & 인터페이스 (3 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 28 | REST API 개요 (arc-web, 34 controllers) | TODO | _pending_ |
| 29 | Chat & Streaming Endpoint | TODO | _pending_ |
| 30 | Slack 어댑터 & 멀티 워크스페이스 | TODO | _pending_ |

### 🎛️ Phase 8 — Admin 심층 (16 페이지) ← **가장 자세히 작성**

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 31 | Admin 개요 & 기업 거버넌스 철학 | TODO | _pending_ |
| 32 | Platform Admin 백엔드 (arc-admin) 아키텍처 | TODO | _pending_ |
| 33 | Admin UI 개요 & 내비게이션 (React 19 + Vite) | TODO | _pending_ |
| 34 | Admin UI — Dashboard & Issues | TODO | _pending_ |
| 35 | Admin UI — Sessions & Traces (대화 감사) | TODO | _pending_ |
| 36 | Admin UI — MCP Servers 관리 | TODO | _pending_ |
| 37 | Admin UI — Personas & Prompt Studio | TODO | _pending_ |
| 38 | Admin UI — Safety Rules (Input/Output Guard, Tool Policy) | TODO | _pending_ |
| 39 | Admin UI — Approvals (승인 워크플로) | TODO | _pending_ |
| 40 | Admin UI — RBAC & Audit Log | TODO | _pending_ |
| 41 | Admin UI — RAG Cache & Documents | TODO | _pending_ |
| 42 | Admin UI — Scheduler & Proactive Channels | TODO | _pending_ |
| 43 | Admin UI — Evaluation (Eval 러너 & 결과) | TODO | _pending_ |
| 44 | Admin UI — Usage, Performance, Feedback | TODO | _pending_ |
| 45 | Admin UI — Platform Admin (Tenant, Pricing, Alert) | TODO | _pending_ |
| 46 | Admin UI — Integrations & Tenant Admin | TODO | _pending_ |

### 🚢 Phase 9 — 운영 (5 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 47 | 배포 & Docker Compose | TODO | _pending_ |
| 48 | Configuration Reference (`arc.reactor.*` 전체) | TODO | _pending_ |
| 49 | Flyway Migration & DB 스키마 | TODO | _pending_ |
| 50 | Testing 전략 & TDD | TODO | _pending_ |
| 51 | QA Verification Loop & 9-Point 로드맵 | TODO | _pending_ |

### 📚 Phase 10 — 개발 기록 (1 페이지)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 52 | Self-Development 이력 & R-Round 시스템 | TODO | _pending_ |

### 🌟 종합 (1 페이지, **맨 마지막**)

| # | 제목 | Status | Page ID |
|---|---|---|---|
| 53 | 🌟 Arc Reactor 종합 안내 (전체 Index + FAQ) | TODO | _pending_ |

---

## §4 페이지별 필수 섹션 템플릿

아래 11개 섹션을 모든 페이지에 포함한다. 해당 없는 섹션은 생략 가능하지만 **명시적으로 건너뛴 이유를 페이지 하단에 적는다**.

| # | 섹션 | 내용 |
|---|---|---|
| 1 | 🎯 **한 줄 요약** | callout 블록. 이 페이지가 다루는 것을 1문장으로 |
| 2 | 📍 **어디에 있나** | 모듈, 주요 파일 경로 (줄 번호 포함) |
| 3 | 🔧 **무엇을 하나** | 기능 설명 (비기술자도 이해 가능) |
| 4 | 💡 **기업 직원에게 어떤 가치인가** | 1~2줄. §2.5 참조. **모든 페이지 필수** |
| 5 | 🧩 **핵심 컴포넌트** | 클래스/인터페이스 목록 + 역할 (표 형식 권장) |
| 6 | ⚙️ **동작 흐름** | 단계별 설명 또는 다이어그램 텍스트 |
| 7 | 💻 **주요 코드** | toggle block 안에. 핵심 스니펫 20~50줄 + 해설 |
| 8 | 🔗 **관련 설정** | `arc.reactor.*` 프로퍼티 또는 환경변수 (표) |
| 9 | 🧪 **테스트 방법** | 관련 테스트 파일 + `./gradlew ...` 실행 명령 |
| 10 | ⚠️ **주의사항 & Gotcha** | CLAUDE.md의 "Critical Gotchas" 중 관련 항목 |
| 11 | 📎 **관련 페이지** | 다른 Notion 페이지 링크 (id 포함) |

### Phase 8 (Admin) 추가 섹션 (의무)

Admin 심층 페이지는 위 11개 외 **추가로 4개 섹션**:

| # | 섹션 | 내용 |
|---|---|---|
| 12 | 🔐 **권한 & RBAC** | 어떤 role이 이 기능에 접근 가능한가 |
| 13 | 📝 **감사 로그** | 어떤 action이 audit_logs에 기록되는가 |
| 14 | 🏛️ **기업 정책 연결** | SOC2, GDPR 등 규정 연결 (해당 시) |
| 15 | 🖥️ **화면 흐름** | 탭, 버튼, 사용자가 보게 되는 UI 순서 |

### Phase 6 MCP 서버 페이지 추가 섹션 (의무)

`atlassian-mcp-server`, `swagger-mcp-server` 페이지는:

| # | 섹션 | 내용 |
|---|---|---|
| 16 | 🔌 **어떤 서비스를 연결하는가** | 외부 제품/API 이름 |
| 17 | 🧰 **노출되는 MCP 도구 목록** | 도구 이름 + 입력/출력 스키마 요약 |
| 18 | 📞 **Arc Reactor에서 어떻게 사용되나** | 호출 예시, 트리거 프롬프트 |
| 19 | 🔒 **Read-only 원칙 재확인** | 쓰기 차단 여부, 승인 경로 |

---

## §5 Notion 스타일 가이드

매 페이지에 **최소 3종 이상의 시각 요소**를 사용한다. 단순 텍스트만 있는 페이지는 불합격.

### 5.1 시각 요소

| 요소 | 언제 |
|---|---|
| **Callout** (`> 💡 tip` / `> ⚠️ 주의` / `> 📌 핵심`) | 중요 개념, 경고, 기업 가치 |
| **Toggle block** | 긴 코드 스니펫, 상세 설명, 고급 내용 |
| **Code block** (언어 지정: `kotlin`, `yaml`, `bash`, `json`) | 스니펫 |
| **Table** | 설정 프로퍼티, 비교표, 컴포넌트 목록 |
| **Divider** (`---`) | 섹션 구분 |
| **Numbered list** | 동작 순서, 절차 |
| **Bullet list** | 속성, 특징 |
| **Heading 1/2/3** | 섹션 계층 |
| **Inline code** | 클래스명, 파일명, 프로퍼티 |
| **Bold** | 중요 용어 첫 등장 |

### 5.2 페이지 아이콘

Phase별 이모지로 통일 (페이지 생성 시 `icon` 파라미터):

| Phase | 아이콘 |
|---|---|
| Phase 1 기초 | 🏛️ |
| Phase 2 에이전트 | ⚙️ |
| Phase 3 지식/메모리 | 🧠 |
| Phase 4 안전 | 🛡️ |
| Phase 5 관측 | 📊 |
| Phase 6 MCP | 🔌 |
| Phase 7 API | 🌐 |
| Phase 8 Admin | 🎛️ |
| Phase 9 운영 | 🚢 |
| Phase 10 개발 기록 | 📚 |
| 종합 | 🌟 |

### 5.3 페이지 제목 형식

`{번호}. {제목}` 형태. 예: `34. Admin UI — Dashboard & Issues`

### 5.4 Notion Markdown 주의

Notion MCP 도구는 Notion-flavored Markdown을 쓴다. 일반 GitHub Markdown과 차이:
- **먼저 `notion://docs/enhanced-markdown-spec` 리소스를 fetch하여 스펙을 확인**
- Toggle은 `> {아이콘} 제목\n> 본문` 패턴이 아니라 별도 문법 사용
- 추측 금지. 스펙 먼저 확인.

---

## §6 매 iteration 규칙

### 6.1 준비 단계

1. 이 파일 `.claude/prompts/notion-docs-loop.md`를 Read
2. §3 페이지 목록에서 **가장 빠른 TODO 항목**을 선택 (순서 유지)
3. 단, `IN_PROGRESS` 상태인 항목이 있으면 **그것부터 재개** (TODO보다 우선)
4. 선택한 페이지의 Phase와 주제에 해당하는 arc-reactor 코드 경로를 파악
5. Notion enhanced markdown 스펙을 `notion-fetch`로 먼저 확인:
   ```
   notion-fetch id=notion://docs/enhanced-markdown-spec
   ```

### 6.2 작업 단계

6. 해당 기능의 **실제 코드를 반드시 읽는다** (Glob + Read + Grep 조합)
   - **헛소리 금지. 추측 금지.** 코드 파일 경로 + 줄 번호 없으면 해당 주장 삭제
   - 관련 `arc-core`/`arc-web`/`arc-slack`/`arc-admin` 코드 최소 3개 이상 확인
   - Admin 페이지의 경우 `arc-reactor-admin` 프론트엔드 코드도 확인
7. Notion에서 해당 페이지 생성 또는 업데이트
   - skeleton이 있으면 → `notion-update-page` (`command: "replace_content"`)
   - 없으면 → `notion-create-pages` (parent: page_id=§1 parent ID)
8. §4 템플릿의 모든 섹션 포함
9. Phase 8 페이지는 §4의 추가 4개 섹션까지 포함
10. Phase 6 MCP 서버 페이지는 §4의 MCP 전용 4개 섹션까지 포함
11. §5 스타일 가이드 준수 (최소 3종 시각 요소)
12. 관련 다른 페이지 링크는 Notion URL 형태로 삽입 (`<page url="...">`)

### 6.3 마무리 단계

13. 이 파일 §3 해당 행의 Status를 `DONE`으로 변경, Page ID 기록
14. 다음 TODO 항목이 있으면 `IN_PROGRESS`로 마킹 (다음 iteration 예고)
15. git add + commit
    - 메시지 형식: `docs: Notion 문서 #N — {제목}`
16. 사용자에게 간단히 보고 (완성 페이지 URL + 다음 예정)

### 6.4 한 iteration의 크기

- **1 페이지 = 1 iteration** — 한 번에 정확히 1 페이지만 완성
- 페이지 내용은 **2000~4000자** 분량 (Admin 페이지는 3000~5000자)
- 너무 길어지면 sub-section을 toggle block으로 접는다
- 코드 grep/read는 해당 페이지의 범위 내로 제한 (불필요한 탐색 금지)

---

## §7 코드 인용 규칙

### 7.1 허용 패턴 ✅

- 파일 경로 + 줄 번호: `arc-core/src/main/kotlin/.../SpringAiAgentExecutor.kt:123`
- 핵심 스니펫 (toggle block 안에): 20~50줄 이내
- 클래스명/함수명 inline code: `ManualReActLoopExecutor.execute()`
- KDoc 요약 인용: "이 클래스는 ~를 담당한다" (한국어로 paraphrase)

### 7.2 금지 패턴 ❌

- **파일 경로 없는 주장** (예: "Arc Reactor는 ~를 한다") ← 즉시 제거 대상
- **확인 안 한 기능 언급** (hallucination) — 코드 grep 없이 서술 금지
- **너무 긴 코드 덤프** (100줄 이상) ← toggle로 접거나 요약
- **실제 데이터 인용** — API key, secret, 운영 이슈 키, 실제 사용자 식별자
- **영문 코멘트 삽입** — 모든 설명은 한국어

### 7.3 스니펫 원칙

- **의도 우선** — 구현 디테일보다 "왜" 이 방식인지 설명
- **한글 주석 포함** — 원본 KDoc이 한국어면 그대로, 영어면 한국어 요약 병기
- **줄임표 사용** — 불필요한 부분은 `...` 또는 `// ... (생략)`로

---

## §8 중단 / 재개 규칙

### 8.1 중단 시점

- **정상 중단**: 페이지 1개 완성 후 자연 중단 (iteration 경계)
- **오류 중단**: Notion API 오류, 인증 만료 → 즉시 중단. 현재 페이지 Status는 `IN_PROGRESS` 유지
- **사용자 중단**: 수동 중단 요청 → 즉시 중단. Status 그대로.

### 8.2 재개 방식

- 이 파일 §3 페이지 목록을 먼저 확인
- `IN_PROGRESS`가 있으면 → **그것부터 재개** (TODO보다 우선)
- 없으면 → 다음 `TODO`부터 시작
- `DONE` 된 페이지는 **절대 건드리지 않는다** (사용자가 명시적으로 rewrite 요청하지 않는 한)

### 8.3 재작성 요청

사용자가 특정 페이지 재작성을 요청하면:
1. 해당 페이지 Status를 `TODO`로 되돌림
2. 기존 Notion Page ID는 유지 (덮어쓰기)
3. 다음 iteration에서 해당 페이지만 작업 (`notion-update-page command="replace_content"`)
4. 다른 DONE 페이지는 건드리지 않음

---

## §9 한 iteration 체크리스트

```
[ ]  1. notion-docs-loop.md Read
[ ]  2. notion-fetch id=notion://docs/enhanced-markdown-spec (스펙 확인)
[ ]  3. §3에서 IN_PROGRESS 항목 확인 → 없으면 다음 TODO 선택
[ ]  4. Status를 IN_PROGRESS로 변경
[ ]  5. 관련 코드 경로 Glob으로 파악
[ ]  6. 주요 파일 Read (파일 경로 + 줄 번호 기록)
[ ]  7. §16 분리 여부 판단 (기능 3개 이상 또는 5000자 이상 예상이면 분리)
[ ]  8. §4 템플릿 11개 섹션 기준 페이지 내용 작성
[ ]  9. (Phase 8이면) 추가 4 섹션 포함
[ ] 10. (Phase 6 MCP 서버면) 추가 4 섹션 포함
[ ] 11. §5 스타일 가이드 최소 3종 시각 요소 확인
[ ] 12. §7 코드 인용 규칙 준수 (파일:줄번호 빠짐없이)
[ ] 13. "기업 직원에게 어떤 가치" 섹션 포함 확인
[ ] 14. Notion 페이지 생성 또는 업데이트 (notion-create-pages 또는 notion-update-page)
[ ] 15. 생성된 경우 §3에 Page ID 기록 (하위 페이지면 38a/38b 형식)
[ ] 16. **§15 검증 프로세스 실행** — 모든 사실적 주장 Read/Glob/Grep으로 검증
[ ] 17. **검증 실패 시 최대 3회 수정** (notion-update-page command="update_content")
[ ] 18. **검증 로그 생성** (claims_checked / verified / failed / final_status)
[ ] 19. §3 해당 행 Status를 DONE (통과) 또는 VERIFICATION_FAILED (3회 실패) 로 변경
[ ] 20. 다음 항목을 IN_PROGRESS로 예고
[ ] 21. git add .claude/prompts/notion-docs-loop.md
[ ] 22. git commit -m "docs: Notion 문서 #N — 제목" + verification 로그 body
[ ] 23. 사용자에게 완성 페이지 URL + 검증 결과 + 다음 예정 보고
```

---

## §10 금지 사항

1. **Hallucination 금지** — 확인하지 않은 기능 서술 금지. 코드 기반 evidence 필수
2. **중복 페이지 생성 금지** — 같은 제목 페이지를 두 번 만들지 않음
3. **부모 페이지 밖으로 생성 금지** — 반드시 §1 parent ID 아래에만
4. **실제 데이터 인용 금지** — API key, secret, 사내 이슈 키, 실제 사용자 식별자 금지
5. **과도한 병행 금지** — 한 iteration에 2개 페이지 쓰지 않음
6. **DONE 페이지 건드리기 금지** — 사용자 명시적 rewrite 요청 없으면 절대 수정 금지
7. **영문 페이지 작성 금지** — 모든 페이지는 한국어로 (코드만 영어)
8. **외부 비교 서술의 날짜/시장 점유율 포함 금지** — 빨리 낡는 정보는 피한다

---

## §11 특별 지시 — Admin 페이지 강조 (Phase 8)

§3의 **Phase 8 Admin 심층 페이지 16개는 다른 페이지보다 1.5배 상세**하게 작성한다.

### 이유
- Admin은 Arc Reactor를 **기업에서 운영 가능하게 만드는 핵심 축**
- 운영자(IT/보안/DevOps) 관점에서 "무엇이 가능한지"를 완전히 보여야 함
- 기업 구매 결정에 직접 영향

### 강화 요구사항

| 관점 | 포함 내용 |
|---|---|
| **화면 흐름** | 어떤 탭 → 어떤 버튼 → 어떤 다이얼로그 순서 |
| **권한 구조 (RBAC)** | 어떤 role이 이 기능에 접근 가능한가 (ADMIN, ADMIN_MANAGER, ADMIN_DEVELOPER, USER) |
| **감사 로그** | 어떤 action이 `admin_audit_logs`에 기록되는가 |
| **기업 정책 연결** | SOC2/GDPR/ISO27001 요구사항과 어떻게 연결되는가 (해당 시) |
| **운영자 가이드** | 일상 운영 시나리오 (예: "매일 아침 대시보드 확인 후 ...") |
| **실패 복구** | 만약 이 기능이 고장나면 어떻게 진단/복구하는가 |

### 주요 Admin 소스 경로 (반드시 참조)

- Backend: `arc-admin/src/main/kotlin/com/arc/reactor/admin/**`
- Backend REST: `arc-web/src/main/kotlin/com/arc/reactor/controller/**` (admin 접두 엔드포인트)
- Frontend: `/Users/jinan/ai/arc-reactor-admin/src/features/**` (각 feature별 폴더)
- Frontend 페이지: `/Users/jinan/ai/arc-reactor-admin/src/pages/**`
- Frontend 라우팅: `/Users/jinan/ai/arc-reactor-admin/src/router.tsx`
- Frontend i18n (UI 라벨): `/Users/jinan/ai/arc-reactor-admin/src/shared/i18n/ko.json`

---

## §12 특별 지시 — MCP 서버 문서화 (Phase 6)

§3의 **#25 `atlassian-mcp-server`**, **#26 `swagger-mcp-server`**는 별도 레포의 MCP 서버다. Arc Reactor가 이들을 **read-only connector**로 연결한다.

### 12.1 각 페이지 필수 포함

1. **어떤 서비스/제품을 연결하는가** (Jira/Confluence/Bitbucket 또는 사내 API 전반)
2. **노출되는 MCP 도구 목록** — 도구 이름 + 용도 + 입력/출력 스키마 요약
3. **Arc Reactor 쪽에서 어떻게 사용되는가**
   - `McpManager`에 등록되는 방식
   - 호출 예시 (사용자 프롬프트 → 에이전트 선택 → MCP 호출 경로)
4. **권한 및 보안** — read-only 원칙 재확인, 쓰기 시 어떻게 막히는가
5. **기업 직원에게 어떤 가치인가** — Slack에서 Jira 검색하는 시나리오 등

### 12.2 코드 참조

- `atlassian-mcp-server` 레포의 README, tool 정의 (`/Users/jinan/ai/atlassian-mcp-server`)
- `swagger-mcp-server` 레포의 README, tool 정의 (`/Users/jinan/ai/swagger-mcp-server`)
- Arc Reactor 측: `arc-core/src/main/kotlin/com/arc/reactor/mcp/**` 전체
- 특히 `McpManager.kt`, `McpToolCallback.kt`, `McpSecurityPolicyStore.kt`
- 한국어 JQL 변환: `WorkContextJiraPlanner.kt`

### 12.3 미래 확장 페이지 (#27)

`MCP Connector 확장 가이드` 페이지는 **사내 신규 서비스를 MCP connector로 추가하는 방법**을 담는다:

- 어떤 기준으로 새 connector 후보를 고르는가
- 기존 MCP spec 따르는 법
- Arc Reactor의 `mcp_security_policy` 테이블에 등록하는 법
- 테스트/검증 절차
- **미래 후보 목록 (예시)**: Naver Works, KakaoWork, 사내 위키, HR 시스템

---

## §13 루프 종료 조건 & 주기

### 13.1 실행 주기
- **자동 실행**: `/loop 20m`로 등록되어 **20분 주기**로 iteration 1회씩 자동 실행
- 1 iteration = 1 페이지 완성 (또는 §16에 따라 1 하위 페이지)
- 20분 안에 페이지가 완성되지 않으면 `IN_PROGRESS`로 유지, 다음 iteration이 이어서 재개
- 사용자가 `/cancel-ralph` 또는 수동 중단 요청 시 즉시 중단

### 13.2 종료 조건
- §3의 53개 페이지(+ 분리된 하위 페이지)가 모두 `DONE` 상태가 되면 루프 종료
- `VERIFICATION_FAILED`로 표시된 페이지는 DONE으로 간주하지 않음 (수동 검토 필요)
- 마지막 페이지인 `#53 Arc Reactor 종합 안내`는 **다른 모든 페이지가 DONE인 후에만** 작성

### 13.3 종합 페이지 (#53) 필수 포함
- 하이라이트 (5~10개 핵심 기능)
- 전체 페이지 Index (53개 + 하위 페이지 링크 + 한 줄 요약)
- FAQ (예: "어디부터 읽어야 하나?", "기술자 아닌데 뭘 먼저?")
- 주요 시나리오 (예: "Slack에서 Jira 검색 질문이 답변되기까지")
- 운영자를 위한 첫 30일 체크리스트

### 13.4 종료 시 보고
- 총 페이지 수 (parent + child 구분)
- 총 참조 파일 수
- Phase별 분량
- 검증 통과율 / `VERIFICATION_FAILED` 목록
- 주요 gotcha 요약

---

## §14 참고 — Notion MCP 도구 호출 가이드

### 14.1 주로 사용할 도구

| 도구 | 용도 | 주의사항 |
|---|---|---|
| `notion-search` | 이미 있는 페이지 중복 방지 확인 | `query_type: "internal"`, `filters: {}` |
| `notion-fetch` | 기존 페이지 내용 확인, enhanced-markdown-spec 조회 | |
| `notion-create-pages` | skeleton 또는 신규 페이지 생성 | `parent: {page_id: "340072b548c380288aa0d529dcaa9795"}` |
| `notion-update-page` | 기존 페이지 업데이트 | `command: "replace_content"` (전체 교체) 또는 `"update_content"` (부분) |

### 14.2 생성 시 필수 파라미터

```json
{
  "parent": {"type": "page_id", "page_id": "340072b548c380288aa0d529dcaa9795"},
  "pages": [{
    "properties": {"title": "{#번호}. {제목}"},
    "icon": "🎛️",
    "content": "... Notion enhanced markdown ..."
  }]
}
```

### 14.3 enhanced-markdown-spec 먼저 확인

**매 iteration 시작 시** `notion-fetch id="notion://docs/enhanced-markdown-spec"`로 스펙 확인. Notion은 일반 Markdown과 다른 부분이 있음 (toggle, database, callout 문법 등).

---

## §15 Content Verification Process (작성 후 검증 필수)

> 📌 **이 섹션은 DONE 마킹 전에 반드시 실행되는 hard gate.** 작성과 검증은 분리된 단계.

페이지 내용을 Notion에 쓴 직후, Status를 `DONE`으로 바꾸기 전에 **반드시 검증**한다. 검증은 hallucination 감지 + 정확성 확인이 목적이다.

### 15.1 검증 대상 — 모든 사실적 주장

페이지에 적힌 모든 사실적 주장을 아래 기준으로 검증해야 한다. 단순 의견/서술(예: "기업 직원에게 편리하다")은 검증 대상이 아니지만, **코드에 묶인 주장**은 100% 검증한다.

| 검증 항목 | 검증 방법 |
|---|---|
| 파일 경로 `path/to/File.kt` | Glob으로 존재 확인 |
| 파일:줄번호 `File.kt:123` | Read로 해당 줄 실제 내용 확인 |
| 클래스/함수/인터페이스 이름 | Grep으로 정의 위치 확인 |
| `arc.reactor.*` 프로퍼티 | `application.yml` 또는 `AgentProperties.kt` grep |
| 환경변수 `ARC_REACTOR_*` | `application.yml` 또는 `.claude/commands/start-stack.md` grep |
| 테스트 파일/메서드 | Read로 존재 확인, 실제 `@Test` 어노테이션 확인 |
| Flyway migration 번호 (`V42` 등) | `arc-core/src/main/resources/db/migration/` Glob |
| REST 엔드포인트 경로 (`/api/admin/...`) | 컨트롤러 파일에서 `@GetMapping`/`@PostMapping`/`@RequestMapping` grep |
| DB 테이블 이름 | migration SQL grep |
| 의존성 버전 (Spring Boot 3.5 등) | `build.gradle.kts` grep |
| 라운드 번호 (`R347` 등) | `docs/reports/rounds/` Glob + git log grep |
| Admin UI 페이지 라우트 (`/sessions` 등) | `arc-reactor-admin/src/router.tsx` grep |
| i18n 키 (`nav.ragCache` 등) | `arc-reactor-admin/src/shared/i18n/ko.json` grep |

### 15.2 검증 절차

1. 페이지 Notion에 쓴 직후 **Status는 `IN_PROGRESS` 유지**
2. 페이지 전체 텍스트를 스캔하여 **사실적 주장 목록을 추출** (클래스명, 파일 경로, 프로퍼티 이름 등)
3. 각 주장을 §15.1 기준으로 1건씩 검증 (Read / Glob / Grep)
4. 검증 실패 시:
   - 해당 주장을 페이지에서 **삭제** 또는 **정정** (대체 사실로)
   - `notion-update-page command="update_content"`로 **해당 부분만 패치**
   - 해당 주장만 재검증
5. **최대 3회 수정 시도**. 3회 후에도 검증 실패면:
   - 페이지 Status를 `VERIFICATION_FAILED`로 표시
   - 페이지 맨 하단에 다음 callout 추가:
     ```
     > ⚠️ 일부 주장이 3회 시도 후에도 검증되지 않았습니다. 수동 검토가 필요합니다.
     > 실패 주장: (목록)
     ```
   - 다음 페이지로 이동 (블로킹하지 않음)
6. 모든 주장이 검증 통과하면 **그때야** Status를 `DONE`으로 변경

### 15.3 검증 금지 사항

- ❌ **검증 없이 DONE 마킹** — IN_PROGRESS → 검증 → DONE 순서 고정
- ❌ **일부만 검증하고 마킹** — 사실적 주장은 빠짐없이 검증
- ❌ **검증 실패를 조용히 넘기기** — `VERIFICATION_FAILED`로 명시 표시
- ❌ **LLM 판단만으로 검증** — 반드시 코드 read/grep 증거로 확인
- ❌ **"다음 iteration에서 수정하겠다" 미룸** — 검증은 같은 iteration 안에서 완료

### 15.4 검증 로그 형식

매 iteration 마지막 단계에서 다음 로그를 생성하고 **git commit 메시지에 포함**한다:

```
verification:
  page_number: {N}
  claims_checked: {총 검증 주장 수}
  claims_verified: {통과}
  claims_failed: {실패}
  fix_attempts: {정정 시도 횟수}
  final_status: DONE | VERIFICATION_FAILED
  failed_claims: (3회 이상 실패한 경우만 나열)
```

예시:
```
verification:
  page_number: 16
  claims_checked: 23
  claims_verified: 23
  claims_failed: 0
  fix_attempts: 1
  final_status: DONE
```

### 15.5 재검증 요청

사용자가 "이 페이지 사실 틀린 것 같아" 같은 피드백 주면:
1. 해당 페이지 Status를 `TODO`로 되돌림
2. 다음 iteration에서 재작성 + §15 검증 재실행
3. Notion Page ID는 유지 (덮어쓰기)

---

## §16 Sub-page Splitting Strategy (하위 페이지 분리 전략)

> 📌 **페이지는 기능별로 명확히 쪼개는 게 우선이다.** 임의 분량 분할은 금지.

일부 페이지는 기능이 밀집되어 있어 **Notion 하위 페이지(child page)로 분리**하는 게 더 명확하다. 분리 여부는 iteration 중에 판단한다.

### 16.1 분리 기준 (아래 중 하나라도 해당하면 분리)

- 본문이 **5000자 이상**이 될 것 같음
- **3개 이상의 독립된 기능/모드**가 한 페이지에 섞임
- 각 하위 기능이 **별도의 증거 체인** (다른 파일 set)을 가짐
- 목차를 기능별로 명확하게 쪼갤 수 있음

### 16.2 분리 방법

1. **Parent 페이지**는 "overview + 하위 페이지 index + 공통 개념"만 담는다
2. **하위 기능 각각을 child page**로 생성 — `notion-create-pages`의 `parent: {type: "page_id", page_id: <parent의 id>}` 사용
3. Parent 페이지에 하위 페이지 링크 (Notion TOC 자동 생성 + 수동 bullet 리스트)
4. 각 child 페이지는 §4 템플릿 11개 섹션 **그대로 적용**
5. 각 child 페이지도 §15 검증 대상 (parent보다 먼저 작성 가능, 각각 독립 iteration)

### 16.3 목차 작성 원칙

- **기능별(function)**, **모드별(mode)** 분리만 허용
- 임의 크기 분할, 분량 맞추기, 미학적 분할 **금지**
- 각 하위 페이지 제목은 **"무엇을 하는가"가 명확**해야 함

| ✅ 좋은 분리 | ❌ 나쁜 분리 |
|---|---|
| `Input Guard 규칙 관리` | `Safety Rules 1부` |
| `Output Guard 규칙 관리` | `Safety Rules 2부` |
| `Jira 도구 목록` | `MCP 전반부` |
| `Confluence 도구 목록` | `MCP 후반부` |
| `기본 ReAct 흐름` | `ReAct 개요 A` |
| `Budget 소진 시 동작` | `ReAct 개요 B` |

### 16.4 §3 테이블의 하위 페이지 추적

분리 시 §3 테이블에 추가 행을 삽입한다. 접미사는 `a`, `b`, `c` ...:

```
| 38 | Admin UI — Safety Rules (overview) | DONE | <parent_id> |
| 38a | ├ Input Guard 규칙 관리 | DONE | <sub_id_1> |
| 38b | ├ Output Guard 규칙 관리 | DONE | <sub_id_2> |
| 38c | └ Tool Policy 관리 | DONE | <sub_id_3> |
```

각 행의 Status는 독립적으로 관리. 하위 페이지도 1 iteration = 1 하위 페이지.

### 16.5 분리 권장 페이지 (힌트 — 강제 아님)

실제 작성 직전에 코드를 보고 판단하되, 아래 페이지는 분리 가능성이 높다:

| 원 페이지 | 분리 후보 |
|---|---|
| #7 ReAct 실행 루프 | 7a 기본 흐름 / 7b Budget 소진 / 7c Tool 실패 / 7d Parallel 실행 |
| #10 Tool 시스템 & 병렬 실행 | 10a ToolCallback / 10b Adapter / 10c Orchestrator / 10d Idempotency |
| #13 RAG Pipeline | 13a Vector 검색 / 13b BM25 / 13c RRF fusion / 13d Reranker |
| #24 MCP 통합 아키텍처 | 24a McpManager / 24b 재연결 / 24c 보안 정책 / 24d Payload Normalizer |
| #25 atlassian-mcp-server | 25a Jira 도구 / 25b Confluence 도구 / 25c Bitbucket 도구 |
| #28 REST API 개요 | 28a Chat / 28b Admin / 28c Tool Policy / 28d Intent |
| #32 Platform Admin 백엔드 | 32a Tenant / 32b Pricing / 32c Alert / 32d Audit |
| #33 Admin UI 개요 | 전체 내비게이션만, 각 탭은 #34~#46 페이지에서 |
| #38 Admin UI Safety Rules | 38a Input Guard / 38b Output Guard / 38c Tool Policy |
| #44 Admin UI Usage/Perf/Feedback | 44a Usage / 44b Performance / 44c Feedback |
| #48 Configuration Reference | 48a arc.reactor.cache / 48b rag / 48c guard / 48d agent / 48e tool |

**힌트이지 강제 아님.** 실제 내용 크기·밀도를 본 후 판단한다.

### 16.6 분리 안 하는 경우

- 내용이 자연스럽게 2000~4000자에 들어감
- 1~2개 기능만 다룸
- 분리하면 오히려 맥락이 잘림

### 16.7 하위 페이지 검증

하위 페이지 각각도 §15 검증 대상. parent 페이지는 자체 주장 + 하위 페이지 링크 정확성만 검증 (링크가 실제로 만들어진 child page를 가리키는지).

---

## 부록 A — 현재 저장된 진행 로그 (매 iteration 갱신)

```
시작 시각: 2026-04-13 (iteration 13 완료 — Tool 시스템 페이지)
현재 진행률: 10 / 53 (+ 하위 페이지 3/3 #7)
직전 완성: 10. Tool 시스템 & 병렬 실행
           (Page ID: 340072b5-48c3-810e-b8b0-c634aba58662)
다음 예정: 11. Tool 선택 & 의도 분류 (Intent)

verification 집계:
  total_claims_checked: 285
  total_claims_verified: 285
  total_claims_failed: 0
  fix_attempts_total: 1
  pages_done: 13 (main 10 + 하위 3)
  pages_verification_failed: 0
  create_retries: 1

Phase 1 완료 — 기초 & 제품 포지셔닝 5/5
Phase 2 진행 — Agent 실행 엔진 5/7 main (+ #7 하위 3개)
```

## 부록 B — 실수 방지 체크

- [ ] 매 iteration에 단 1 페이지(또는 1 하위 페이지)만 작업했는가?
- [ ] 페이지가 §1 parent (Reactor (AI Agent)) 아래에 생성/업데이트되었는가?
- [ ] 모든 주장에 파일:줄번호가 있는가?
- [ ] "기업 직원 가치" 섹션이 있는가?
- [ ] §5 스타일 가이드 최소 3종 시각 요소?
- [ ] **§15 검증이 실행되었는가?**
- [ ] **검증 로그가 commit 메시지에 포함되었는가?**
- [ ] **VERIFICATION_FAILED 시 callout 추가되었는가?**
- [ ] 이 md 파일의 §3 Status가 DONE 또는 VERIFICATION_FAILED로 갱신되었는가?
- [ ] Page ID가 §3에 기록되었는가? (하위 페이지면 접미사 a/b/c/...)
- [ ] git commit이 되었는가?
- [ ] §16 분리가 필요한지 판단했는가? (분리했다면 parent + 하위 페이지 목록 §3 반영)

## 부록 C — 관련 참조 문서 (Arc Reactor 내부)

- `CLAUDE.md` — 프로젝트 전체 개발 규칙
- `.claude/rules/kotlin-spring.md` — Kotlin/Spring 관례
- `.claude/rules/executor.md` — Agent Executor 규칙
- `.claude/rules/testing.md` — Testing 규칙
- `.claude/rules/architecture.md` — 아키텍처 규칙
- `.claude/prompts/qa-verification-loop.md` — QA 검증 루프 (이 문서와 자매 루프)
- `docs/production-readiness-report.md` — 프로덕션 준비도 보고
- `docs/agent-work-directive.md` — 작업 지시서

이들 문서는 Notion 페이지 작성 시 배경 정보로 참조한다.

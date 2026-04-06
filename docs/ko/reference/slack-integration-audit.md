# Arc Reactor Slack 통합 감사 문서

> 마지막 업데이트: 2026-04-03
> 기준: 현재 저장소 코드 경로 점검
> 범위: `arc-slack`, `arc-web`, `arc-core`, `arc-admin`에 걸친 Slack 연동 기능과 운영 표면

---

## 1. 핵심 요약

Arc Reactor의 Slack 연동은 단순 알림 봇이 아니라, Slack을 `주 사용자 진입 채널`로 삼아 에이전트를 실행하는 구조다.

코드 기준으로 Slack 관련 기능은 크게 네 덩어리다.

1. Slack에서 들어오는 사용자 질문 처리
2. Slack 전용 사용자 경험 기능
3. 운영자/관리자용 Slack 제어 표면
4. 에이전트가 Slack API를 도구로 직접 사용하는 Slack Tools 모듈

가장 중요한 점은 다음과 같다.

- Slack에서 들어온 질문도 결국 동일한 `AgentExecutor` 파이프라인을 탄다.
- 따라서 Guard, Hook, Tool 호출, MCP, 조건부 RAG, 승인 정책 같은 핵심 엔진 기능이 그대로 적용된다.
- Slack 연동과 Slack Tools는 같은 기능이 아니다.
- Slack 연동은 “Slack을 채팅 채널로 쓰는 기능”이고, Slack Tools는 “에이전트가 Slack API를 직접 도구처럼 쓰는 기능”이다.
- 둘 다 기본 비활성이다. 명시적 설정이 있어야 켜진다.

---

## 2. 사용자 입장에서 Slack에서 가능한 것

### 2.1 대표 사용 시나리오

| 사용자 행동 | 현재 동작 | 비고 |
|------|------|------|
| 봇을 채널에서 멘션한다 | 질문으로 인식하고 스레드에서 답변 | 가장 기본 진입 경로 |
| 봇이 연 스레드에 후속 답장을 단다 | 같은 세션으로 이어서 처리 | thread tracking 기반 |
| DM으로 메시지를 보낸다 | 설정에 따라 처리 가능 | `processDirectMessagesWithoutThread` 영향 |
| `/reactor ...` 같은 슬래시 명령을 사용한다 | 비동기 처리 후 스레드 또는 `response_url`로 응답 | help/remind/brief/my-work 포함 |
| 프로액티브 채널에 일반 메시지를 쓴다 | 봇이 관련 있다고 판단할 때만 개입 | 아니면 침묵 |
| 봇 답변에 `:+1:` / `:-1:` 반응을 단다 | 피드백으로 저장 가능 | reaction feedback 기능 |

### 2.2 사용자가 Slack에서 체감하는 기본 흐름

1. 사용자가 Slack에서 질문한다.
2. Arc Reactor가 Slack 이벤트를 수신한다.
3. Slack 메시지를 Arc Reactor 세션 ID로 매핑한다.
4. 동일한 에이전트 엔진이 실행된다.
5. 결과를 Slack 스레드나 `response_url`로 돌려준다.

즉, Slack은 “별도 간이 봇”이 아니라 웹 채팅과 같은 엔진을 다른 채널로 노출한 것에 가깝다.

### 2.3 사용자가 말을 해도 응답하지 않는 대표 경우

Slack에서는 모든 메시지에 답하는 구조가 아니다.

- 봇이 쓴 메시지
- Slack `subtype`이 붙은 메시지
- Arc Reactor가 시작하지 않은 스레드의 후속 메시지
- thread 없는 DM인데 `processDirectMessagesWithoutThread=false`인 경우
- 프로액티브 채널이 아니고, 봇 멘션도 아니고, 슬래시 명령도 아닌 일반 채널 메시지
- 프로액티브 채널이더라도 모델이 `[NO_RESPONSE]`를 반환한 경우

즉, “Slack에 설치했으니 채널의 모든 대화에 반응한다”는 이해는 틀리다.

---

## 3. 수신 방식: Events API vs Socket Mode

Slack 수신 방식은 두 가지다.

| 모드 | 설명 | 코드 표면 | 특징 |
|------|------|----------|------|
| `EVENTS_API` | Slack이 Arc Reactor HTTP 엔드포인트를 호출 | `POST /api/slack/events`, `POST /api/slack/commands` | 공개 콜백 URL 필요 |
| `SOCKET_MODE` | Arc Reactor가 Slack WebSocket에 직접 연결 | `SlackSocketModeGateway` | 공개 콜백 URL 불필요 |

기본 설정은 `SOCKET_MODE`다.

### 3.1 Events API 모드에서 기록해야 할 것

- `POST /api/slack/events`
  - Slack URL verification challenge 처리
  - 이벤트 콜백을 비동기로 `SlackEventProcessor`에 전달
  - Slack retry 헤더를 로깅
- `POST /api/slack/commands`
  - 슬래시 명령 form-urlencoded 페이로드 수신
  - 즉시 ACK 반환
  - 실제 처리는 `SlackCommandProcessor`가 비동기 수행

슬래시 명령 ACK는 상황에 따라 세 가지 메시지로 나뉜다.

- 정상 접수: `Processing...`
- 잘못된 payload: invalid payload 응답
- 포화 상태: busy 응답

이 ACK UX는 Events API 경로 기준이다. Socket Mode에서는 Slack envelope만 ACK하고, 사용자에게 동일한 `Processing...` ephemeral 응답을 별도로 보내는 구조는 아니다.

### 3.2 Socket Mode에서 기록해야 할 것

- 앱 시작 시 WebSocket 연결을 연다.
- 이벤트, 슬래시 명령, 인터랙티브 envelope를 수신한다.
- 이벤트/슬래시 명령은 즉시 ACK 후 내부 프로세서로 넘긴다.
- 인터랙티브 envelope는 현재 코드 기준 `ACK 후 무시`한다.

즉, 현재 저장소 기준으로 버튼/모달 같은 Slack interactive workflow는 본격 지원 표면으로 보이지 않는다. Events API 쪽도 `/api/slack/events`, `/api/slack/commands`만 있고 별도 interactivity 엔드포인트는 확인되지 않는다.

---

## 4. Slack에서 실제로 동작하는 사용자 기능

### 4.1 `@mention` 질문

가장 기본적인 Slack 대화 진입점이다.

- 현재 구현은 입력에서 모든 Slack `<@...>` 토큰을 제거한다.
- 즉, 봇 멘션뿐 아니라 본문 안의 다른 사용자 멘션도 에이전트에 전달되기 전에 사라질 수 있다.
- 스레드가 없으면 현재 메시지 timestamp를 루트 스레드로 사용한다.
- `sessionId = slack-{channelId}-{threadTs}` 형식으로 세션을 만든다.
- 같은 스레드 안의 후속 대화는 같은 세션으로 이어진다.
- 응답은 항상 Slack 스레드에 다시 단다.
- 멘션만 있고 내용이 비어 있으면 조용히 무시한다.

### 4.2 스레드 후속 대화

아무 스레드나 다 처리하는 구조가 아니다.

- 기본적으로 Arc Reactor가 시작한 스레드만 추적한다.
- 추적되지 않은 스레드의 일반 답글은 무시될 수 있다.
- 이 설계는 엉뚱한 스레드에 봇이 끼어드는 것을 막기 위한 것이다.

즉, Slack에서의 “대화 기억”은 단순히 채널 기준이 아니라 `채널 + 스레드` 기준이다.

### 4.3 DM 처리

DM은 별도 옵션의 영향을 받는다.

- `processDirectMessagesWithoutThread=false`이면 thread 없는 최상위 IM/MPIM을 막을 수 있다.
- `true`면 IM뿐 아니라 MPIM도 일반 질문처럼 처리 가능하다.

따라서 “슬랙 DM이면 무조건 답한다”가 기본값이라고 단정하면 안 된다.

### 4.4 슬래시 명령

슬래시 명령은 일반 메시지와 다르게 `명령 UX`가 있다.

현재 코드 기준 내장 인텐트는 다음과 같다.

- `help`
- `brief`
- `my-work`
- `remind <text>`
- `remind list`
- `remind done`
- `remind clear`
- 기타 자유 질문

주의할 점은 실제 문법이다.

- `remind add`라는 별도 서브커맨드는 없다.
- `remind` 뒤에 `list`/`clear`/`done <id>`가 아니면 전부 reminder text로 간주한다.
- bare `/reactor remind`는 추가가 아니라 목록 조회다.

#### 슬래시 명령의 실제 처리 방식

- 사용자가 `/reactor 질문`을 호출한다.
- Arc Reactor는 채널에 “사용자 질문” 메시지를 먼저 올려 스레드를 만든다.
- 그 스레드에 에이전트 답변을 단다.
- 채널 게시가 실패하면 `response_url`로 폴백한다.
- 입력이 비어 있으면 ephemeral 가이드만 반환하고 스레드는 만들지 않는다.
- `response_url` 폴백이 일어나면 one-off `slack-cmd-...` 세션이 생겨 thread continuity가 끊길 수 있다.

즉, 슬래시 명령도 최종적으로는 스레드형 대화 UX를 만들려는 설계다.

### 4.5 리마인더

슬래시 명령 계열에는 Slack 전용 리마인더 UX가 있다.

- 리마인더 추가
- 리마인더 목록 조회
- 특정 리마인더 완료 처리
- 전체 리마인더 삭제

리마인더 시각이 있으면 나중에 DM으로 다시 보낸다.

시간 파싱 범위도 좁다.

- 지원 형식은 trailing `at HH:mm` 또는 한국어 `N시 [M분]` 정도다.
- 그 외 표현은 조용히 일반 unscheduled reminder로 저장될 수 있다.
- 사용자당 최대 50개를 넘기면 가장 오래된 reminder부터 밀린다.

다만 현재 기본 구현은 인메모리 저장소 + 로컬 스케줄러다.

- 재시작 후 유지되지 않는다.
- 다중 인스턴스 환경에서 공유되지 않는다.

즉, Slack은 단순 Q&A뿐 아니라 개인 업무 보조 인터페이스로도 쓰이게 설계되어 있지만, 현재 기본 reminder는 HA/영속 저장 구조라고 보면 안 된다.

### 4.6 프로액티브 채널 모드

이 기능은 일반 봇 멘션과 다르다.

- 특정 채널을 “프로액티브 채널”로 등록한다.
- 그 채널에서는 일반 메시지에도 에이전트가 반응을 검토한다.
- 하지만 모든 메시지에 답하지 않는다.
- 도움이 되지 않는 경우 시스템 프롬프트 규칙상 정확히 `[NO_RESPONSE]`를 반환해야 하며, 이 경우 Slack에 아무 말도 하지 않는다.
- 한 번 프로액티브 응답이 달리면 그 원본 top-level 메시지는 추적된 스레드가 되고, 이후 그 스레드 답글은 일반 대화처럼 이어질 수 있다.

프로액티브가 침묵하는 조건은 `[NO_RESPONSE]`만이 아니다.

- blank input
- blank / failed agent output
- non-allowlisted channel
- DM/MPIM
- proactive concurrency limit 초과
- 실제 전송 실패

즉, 프로액티브 모드는 “늘 말하는 봇”이 아니라 “필요할 때만 끼어드는 봇”을 목표로 한다.

### 4.7 리액션 기반 피드백

Slack 답변에 대한 간단한 만족도 피드백 경로도 있다.

- `:+1:` 또는 `:thumbsup:` -> 긍정 피드백
- `:-1:` 또는 `:thumbsdown:` -> 부정 피드백

이때 아무 메시지 반응이나 수집하는 것은 아니다.

- Arc Reactor가 보낸 응답이 추적되어 있어야 한다.
- 추적된 봇 응답에 달린 리액션만 `sessionId`와 원질문에 연결된 피드백 레코드로 저장된다.
- 허용되는 reaction도 `+1`, `thumbsup`, `-1`, `thumbsdown` 계열뿐이다.
- `FeedbackStore`가 없으면 리액션은 조용히 무시된다.
- slash handler의 첫 스레드 응답이나 `response_url` 폴백 응답은 기본 event 경로 응답처럼 모두 추적되는 것은 아니다.

또 하나의 경계는 provenance 보존 범위다.

- Slack 모듈 안에서는 `sessionId`와 원질문 연결이 가능하다.
- 하지만 core/web feedback enrichment와 export 단계까지 가면 Slack `channelId`, `source`, Slack email 같은 메타데이터가 그대로 전부 남는 구조라고 보기는 어렵다.

---

## 5. Slack에서 실행될 때 같이 적용되는 Arc Reactor 공통 기능

Slack은 별도 에이전트가 아니라 동일 엔진의 다른 진입점이므로, 아래 기능이 그대로 적용된다.

| 공통 기능 | Slack 적용 여부 | 설명 |
|------|------|------|
| Guard | 적용 | rate limit, 정책, 입력 검사 등 |
| Hook | 적용 | before/after hook |
| ReAct 도구 호출 | 적용 | 모델이 필요한 도구 사용 |
| MCP 도구 사용 | 적용 | 연결된 MCP 서버 사용 가능 |
| 조건부 RAG | 적용 | Slack 질문도 RAG 분류를 통과하면 retrieval 가능 |
| 세션 메모리 | 적용 | Slack 스레드 세션 기반 |
| 사용자 장기 메모리 | 적용 가능 | 설정과 저장소가 있으면 system prompt에 주입 |
| Tool approval | 적용 가능 | 위험 도구면 승인 정책의 영향 가능 |

즉, Slack에서 질문했다고 해서 RAG가 빠지거나 Guard가 꺼지는 구조가 아니다.

---

## 6. Slack 대화에 추가로 실리는 문맥

Slack 경로에는 웹 채팅과 다른 메타데이터와 문맥 주입이 있다.

### 6.1 세션 ID 규칙

| 상황 | 세션 ID 형식 |
|------|--------------|
| 일반 스레드 대화 | `slack-{channelId}-{threadTs}` |
| 슬래시 명령 폴백 | `slack-cmd-{channelId}-{userId}-{timestamp}` |
| 프로액티브 메시지 | `slack-proactive-{channelId}-{ts}` |

### 6.2 메타데이터

Slack 실행 시 메타데이터에는 보통 다음 값이 들어간다.

- `source=slack`
- `channel=slack`
- `channelId`
- `sessionId`
- 슬래시 명령이면 `entrypoint=slash`
- 프로액티브면 `entrypoint=proactive`
- 슬래시 인텐트면 `intent`
- 가능하면 `requesterEmail`, `slackUserEmail`, `userEmail`

이 메타데이터가 모든 곳에서 같은 방식으로 쓰이는 것은 아니다.

- 일반 agent metrics와 실행 추적에서는 `channel=slack`, `sessionId` 같은 공통 값이 중요하다.
- 반면 RAG ingestion 쪽은 `channelId`를 우선 사용하므로, Slack origin 문서는 `"slack"`보다 실제 Slack 채널 ID로 태깅되는 경우가 많다.

### 6.3 이메일 해석

옵션이 켜져 있으면 Slack `users.info`로 요청자 이메일을 조회해 메타데이터에 넣는다.

이 값은 다음 용도로 중요하다.

- 사내 시스템의 사용자 매핑
- MCP/백엔드 도구가 이메일 기반 권한을 쓸 때
- 감사/추적
- response cache identity scope
- requester-aware tool parameter 자동 주입

### 6.4 사용자 장기 메모리

Slack에서도 사용자 장기 메모리 기능이 있으면 system prompt에 붙을 수 있다.

예를 들면 다음이 들어갈 수 있다.

- 팀
- 역할
- 선호도
- 최근 관심 주제

다만 메모리 회상 자체는 Slack email이 아니라 일반적으로 `userId`와 core memory 설정에 의해 움직인다.

즉, Slack은 단순 stateless webhook이 아니라 사용자 컨텍스트를 포함할 수 있는 채널이지만, email metadata가 곧바로 user-memory key가 되는 구조라고 보면 안 된다.

---

## 7. 관리자와 운영자가 기록해야 하는 Slack 관련 표면

Slack은 운영/API 표면이 꽤 많다. 특히 사용자가 Slack으로 들어오더라도 실제 운영은 admin API와 스케줄러, health, metrics에 걸쳐 있다.

### 7.1 프로액티브 채널 관리 API

관리자가 제어하는 가장 직접적인 Slack 기능이다.

| API | 설명 |
|-----|------|
| `GET /api/proactive-channels` | 등록된 프로액티브 채널 목록 조회 |
| `POST /api/proactive-channels` | 채널 추가 |
| `DELETE /api/proactive-channels/{channelId}` | 채널 제거 |

특징은 다음과 같다.

- Slack이 켜져 있어야 노출된다.
- 변경 작업은 사실상 full `ADMIN` 권한이 필요하다.
- 추가/삭제는 admin audit에 best-effort로 기록된다.
- `arc-web`가 `arc-slack` 타입에 직접 의존하지 않도록 reflection bridge를 사용한다.
- 기본 저장소는 in-memory 구현이라, 관리자 API로 추가한 채널은 재시작 후 유지되지 않는다.

즉, 운영자가 “admin에서 한 번 추가했으니 영구 저장된다”고 보면 안 된다. 영속성이 필요하면 DB 기반 `ProactiveChannelStore` 구현이 추가로 필요하다.

### 7.2 스케줄러 결과의 Slack 전달

Slack은 실시간 채팅 채널일 뿐 아니라 스케줄 결과 전달 채널이기도 하다.

- 스케줄 작업 정의에 `slackChannelId`를 둘 수 있다.
- 작업 실행 후 결과를 Slack 채널로 보낼 수 있다.
- 이때 `SlackMessageSenderAdapter`가 스케줄러와 `SlackMessagingService`를 연결한다.
- 단, scheduled agent run 자체는 `channel=scheduler` 문맥으로 실행되고 Slack은 실행 후 결과를 보내는 sink로만 등장한다.

즉, 운영 보고, 브리핑, 예약 에이전트 실행 결과를 Slack으로 보내는 경로가 이미 있다.

### 7.3 Slack 관련 메트릭

코드상 Slack 모듈은 별도 메트릭을 기록한다.

- `arc.slack.inbound.total`
- `arc.slack.duplicate.total`
- `arc.slack.dropped.total`
- `arc.slack.handler.duration`
- `arc.slack.api.duration`
- `arc.slack.api.retry.total`
- `arc.slack.response_url.total`

운영자가 봐야 할 핵심 포인트는 다음이다.

- inbound는 들어오는데 handler duration만 길어지는지
- duplicate가 비정상적으로 많은지
- dropped가 증가하는지
- Slack API retry가 늘어나는지
- response_url 실패가 있는지

Slack Tools를 같이 운영한다면 별도 지표도 보는 편이 맞다.

- `slack_api_calls_total`
- `slack_api_latency`
- `slack_api_attempts_total`
- `slack_api_error_code_total`
- `slack_api_retries_total`
- `mcp_tool_invocations_total`
- `mcp_tool_latency`

### 7.4 Slack Tools Health

Slack Tools는 별도 health 표면이 있다.

- Slack bot token으로 `auth.test`를 호출한다.
- 필요한 scope가 빠졌는지 확인한다.
- API/scope 드리프트 계열은 optional integration이라 `UNKNOWN` 쪽으로 표현한다.
- readiness health는 별도로 등록된 `LocalTool` 개수를 보고, 도구가 하나도 없으면 `DOWN`으로 본다.
- 이 health 표면 자체도 `arc.reactor.slack.tools.enabled=true`일 때만 의미가 있다.

즉, “Slack 연결은 되는데 어떤 도구는 안 보인다” 같은 문제를 scope 부족으로 진단할 수 있게 설계되어 있다.

### 7.5 감사와 정책 관점

Slack 자체 설정 화면이 arc-admin에 깊게 박혀 있다기보다, 다음 운영 기능과 교차한다.

- 프로액티브 채널 변경은 admin audit 대상
- Slack에서 시작된 에이전트 실행도 일반 실행 정책의 영향을 받음
- Tool approval, output guard, MCP access policy가 Slack 경로에도 간접 영향
- 프로액티브 변경 감사는 `/api/admin/audits`에서 다시 조회 가능
- `/api/admin/capabilities`는 활성화된 Slack controller가 있을 때 그 경로들을 raw request-mapping 기준으로 보여준다.

감사도 운영 관점에서는 caveat가 있다.

- `recordAdminAudit()`는 실패를 삼키는 best-effort 기록이다.
- 기본 `AdminAuditStore`는 in-memory라 JDBC store override가 없으면 재시작 후 유지되지 않는다.

즉, Slack은 별도 독립 정책 체계가 아니라 플랫폼 공통 거버넌스 아래에 있다.

---

## 8. Slack Tools 모듈: Slack 채팅 연동과는 별개의 기능

이 부분은 매우 자주 헷갈린다.

### 8.1 Slack 연동 vs Slack Tools

| 구분 | 의미 |
|------|------|
| Slack 연동 | 사용자가 Slack에서 Arc Reactor에게 질문하는 기능 |
| Slack Tools | Arc Reactor가 Slack API를 직접 호출하는 도구 기능 |

예를 들어, 사용자가 웹 UI에서 질문하더라도 에이전트는 Slack Tools를 써서 Slack 채널을 읽거나 메시지를 보낼 수 있다.

### 8.2 활성화 조건

- `arc.reactor.slack.enabled`와 별개로
- `arc.reactor.slack.tools.enabled=true`가 필요하다.
- bot token이 필요하다.
- Canvas 도구는 추가로 `arc.reactor.slack.tools.canvas.enabled=true`가 필요하다.

### 8.3 현재 코드상 도구 목록

| 도구 | 역할 |
|------|------|
| `send_message` | 채널에 메시지 전송 |
| `reply_to_thread` | 스레드 답글 전송 |
| `list_channels` | 채널 목록 조회 |
| `find_channel` | 채널 검색 |
| `read_messages` | 채널 메시지 읽기 |
| `read_thread_replies` | 스레드 답글 읽기 |
| `add_reaction` | 이모지 반응 추가 |
| `get_user_info` | 사용자 상세 조회 |
| `find_user` | 사용자 검색 |
| `search_messages` | Slack 검색 |
| `upload_file` | 파일 업로드 |
| `create_canvas` | Canvas 생성 |
| `append_canvas` | Canvas 추가 작성 |

### 8.4 Scope-aware 노출

Slack Tools는 단순히 토큰만 있으면 다 노출되지 않는다.

- `scopeAwareEnabled=true`일 때만 현재 bot token에 부여된 OAuth scope를 해석한다.
- 그 상태에서 정상적으로 scope를 읽어오면 필요한 scope가 있는 도구만 에이전트에 노출한다.
- 예를 들면 `chat:write`, `channels:read`, `channels:history`, `users:read`, `files:write`, `canvases:write` 등을 본다.
- 기본값은 `scopeAwareEnabled=false`다.
- 또 scope-aware를 켠 뒤에도 기본값은 `failOpenOnScopeResolutionError=true`라서, scope 조회 실패나 빈 scope 집합에서는 전체 도구가 노출될 수 있다.

즉, 도구가 “존재하는 코드”와 “실제로 에이전트에 노출되는가”는 다를 수 있다.

### 8.5 Slack Tools 운영 특성

- write idempotency 지원
- timeout / circuit breaker 지원
- Canvas 도구는 별도 옵션과 ownership 정책이 있음
- scope resolution 실패 시 fail-open 여부도 설정 가능
- readiness/health 표면이 별도로 있어 scope 드리프트와 도구 미등록을 구분해 볼 수 있음

---

## 9. Slack 보안, 안정성, 하드닝 포인트

Slack은 외부 공개 표면이므로 기능 설명만큼 방어 로직 기록도 중요하다.

### 9.1 서명 검증

Events API 모드에서는 Slack 서명 검증 필터가 붙는다.

- 적용 대상은 `/api/slack*`
- HMAC-SHA256 기반
- timestamp tolerance 적용
- 실패 시 403 JSON 응답

즉, 외부에서 임의로 Slack callback을 흉내 내는 요청을 막는다.

### 9.2 이벤트 중복 제거

Slack Events API는 재전송이 있을 수 있으므로 `event_id` 기반 dedup이 들어 있다.

- TTL과 최대 엔트리 수가 있다.
- duplicate는 별도 메트릭으로 센다.
- 기본 구현은 인메모리라 replica 간 공유 dedup은 아니다.

주의할 점은 dedup이 이벤트 경로 중심이라는 것이다.

- slash command 경로에는 동일 수준의 ingress dedup이 보이지 않는다.
- 따라서 Slack이 슬래시 명령을 중복 전달하면 이중 응답이나 이중 게시가 생길 여지가 있다.

### 9.3 스레드 추적

추적되지 않은 스레드를 무시하는 설계는 UX 규칙이면서 동시에 안전장치다.

- 엉뚱한 스레드 개입 방지
- 불필요한 비용 방지
- 잘못된 컨텍스트 연결 방지

### 9.4 백프레셔와 포화 처리

Slack 이벤트/슬래시 명령 처리에는 동시성 제한이 있다.

- `maxConcurrentRequests`
- `failFastOnSaturation`
- `notifyOnDrop`

포화 시에는 즉시 거부하고, 설정에 따라 Slack에 busy 메시지를 보내기도 한다.

### 9.5 사용자별 레이트 리밋

옵션 기능으로 사용자별 분당 요청 수 제한이 있다.

이는 한 사용자가 Slack 경로를 과도하게 점유하는 상황을 막기 위한 것이다.

### 9.6 Slack API 호출 안정성

아웃바운드 Slack API 호출에도 방어 로직이 있다.

- `sendMessage` 경로에는 per-channel 1 req/sec 클라이언트 측 레이트 제한
- 429/5xx 재시도
- `response_url` 대상 호스트 allowlist
- `response_url` 요청 자체는 10초 timeout 적용

주의할 점도 있다.

- `SlackResponseUrlRetrier` 유틸리티와 테스트는 존재한다.
- 하지만 현재 기본 `SlackMessagingService.sendResponseUrl()` 경로에는 그 재시도 유틸리티가 직접 연결되어 있지 않다.
- `addReaction`, `response_url`, Slack Tools API 클라이언트는 같은 형태의 per-channel rate limit을 공유하지 않는다.

즉, 일반 Slack Web API 일부 호출은 재시도되지만, `response_url` 콜백은 현재 기본 경로 기준 단발성 전송에 가깝고, “모든 Slack outbound 호출이 동일한 rate limit 정책을 쓴다”고 이해하면 틀리다.

특히 `response_url`은 host allowlist로 제한되며 `hooks.slack.com`, `slack.com` 계열만 허용한다. 다만 이것은 host 검증 중심 설명이지, 전체 URL 하드닝을 모두 보장한다고 표현하면 과장이다.

---

## 10. 지금 코드 기준으로 “있는 것”과 “없는 것”

### 10.1 분명히 있는 것

- Slack Events API 수신
- Slack Socket Mode 수신
- `@mention` 질의응답
- 스레드 기반 후속 대화
- DM 옵션 처리
- 슬래시 명령과 리마인더
- 프로액티브 채널
- 리액션 피드백
- 이메일 해석과 사용자 메모리 주입
- 스케줄러 결과의 Slack 전송
- Slack Tools와 scope-aware 노출

### 10.2 현재 코드 기준으로 주의해서 말해야 하는 것

- Slack interactive workflow는 본격 구현 표면이 보이지 않는다.
  - Socket Mode에서는 interactive envelope를 ACK 후 무시한다.
  - 현재 저장소에서 버튼/모달 액션 처리 전용 경로나 Events API interactivity endpoint는 확인되지 않았다.
- Slack 연동이 켜져 있다고 Slack Tools까지 자동 활성화되는 것은 아니다.
- Slack Tools의 scope-aware 필터링도 기본값으로는 꺼져 있다.
- 프로액티브 채널 목록은 기본 구현상 재시작 간 유지되지 않는다.
- reminder도 기본 구현상 인메모리이며 다중 인스턴스 공유가 없다.
- Slack 질문이 항상 RAG를 쓰는 것도 아니다.
  - Slack도 일반 채팅과 동일하게 조건부 RAG다.
- 사용자 Slack 대화가 자동으로 바로 RAG 지식베이스에 들어가는 것도 아니다.
  - RAG ingestion 정책과 승인 흐름이 따로 있다.
- `response_url` 재시도 유틸리티는 있지만 기본 전송 경로에 자동 접속되어 있다고 단정하면 안 된다.
- Slack Tools의 scope-aware 노출은 기본값이 permissive fail-open이므로, scope 조회 실패 시 전체 도구가 보일 수 있다.
- slash command는 이벤트처럼 별도 dedup이 기본 내장돼 있다고 보면 안 된다.
- `arc.reactor.slack.user-memory-enabled` 프로퍼티는 존재하지만 현재 코드상 실질적인 gating으로 연결돼 있다고 보기 어렵다.

---

## 11. 운영자가 실제로 이해해야 하는 활성화 조건

### 11.1 Slack 채팅 연동 자체를 켜려면

- `arc.reactor.slack.enabled=true`
- 적절한 `transportMode`
- `botToken`
- Socket Mode면 `appToken`
- Events API면 보통 `signingSecret`
- 다만 `signatureVerificationEnabled=false`면 서명 필터 없이도 기동은 가능하다. 운영 관점에서는 기본값 유지가 맞다.

### 11.2 Slack Tools를 켜려면

- `arc.reactor.slack.tools.enabled=true`
- `arc.reactor.slack.tools.bot-token`
- 필요한 OAuth scope
- 진짜 scope-aware 노출까지 원하면 `scopeAwareEnabled=true`

### 11.3 프로액티브 채널이 실제로 동작하려면

- `proactiveEnabled=true`
- 채널이 proactive channel list에 있어야 함
- 또는 설정의 `proactiveChannelIds`에 포함돼 있어야 함
- 단, 기본 store는 in-memory라 runtime 추가분은 재시작 후 사라진다.

### 11.4 스케줄 결과를 Slack으로 보내려면

- Slack만 켜는 것으로 끝나지 않는다.
- `arc.reactor.scheduler.enabled=true`가 필요하다.
- 작업 정의에 `slackChannelId`가 있어야 한다.
- sender 빈이 없거나 전송이 실패하면 job 자체를 실패로 바꾸지 않고 warning만 남기는 best-effort side effect다.

### 11.5 Slack 관련 admin/ops 표면을 이해할 때

- 모든 Slack 운영 표면이 `arc.reactor.admin.enabled=true`에 묶여 있는 것은 아니다.
- 예를 들어 프로액티브 채널 API와 감사 조회 API는 `arc-web` 쪽에서도 노출된다.
- 반면 platform health, alerts, tenant analytics, pricing 같은 `arc-admin` 표면은 `arc.reactor.admin.enabled=true`가 필요하다.
- 그리고 그중 일부는 실질적으로 `DataSource` 기반 JDBC 빈이 있어야 의미 있는 데이터를 낸다.
- 또 built-in admin alert notifier는 곧바로 Slack으로 보내는 구조가 아니라 기본적으로 로그 출력 계열이다.

### 11.6 Slack에서도 RAG를 쓰려면

- Slack만 켜서는 안 된다.
- 별도로 RAG가 켜져 있어야 한다.
- VectorStore가 있어야 한다.
- 질문이 RAG 분류를 통과해야 한다.

---

## 12. 코드와 테스트 위치 지도

### 12.1 대표 코드 파일

| 영역 | 대표 파일 |
|------|-----------|
| Slack 설정 | `arc-slack/.../config/SlackProperties.kt` |
| Slack 자동구성 | `arc-slack/.../config/SlackAutoConfiguration.kt` |
| Events API 엔드포인트 | `arc-slack/.../controller/SlackEventController.kt` |
| Slash 엔드포인트 | `arc-slack/.../controller/SlackCommandController.kt` |
| Socket Mode | `arc-slack/.../gateway/SlackSocketModeGateway.kt` |
| 이벤트 처리기 | `arc-slack/.../processor/SlackEventProcessor.kt` |
| 슬래시 처리기 | `arc-slack/.../processor/SlackCommandProcessor.kt` |
| 기본 이벤트 핸들러 | `arc-slack/.../handler/DefaultSlackEventHandler.kt` |
| 기본 슬래시 핸들러 | `arc-slack/.../handler/DefaultSlackCommandHandler.kt` |
| 메시지 전송 | `arc-slack/.../service/SlackMessagingService.kt` |
| 서명 검증 | `arc-slack/.../security/SlackSignatureWebFilter.kt` |
| 프로액티브 채널 API | `arc-web/.../controller/ProactiveChannelController.kt` |
| 스케줄러 Slack 브리지 | `arc-slack/.../adapter/SlackMessageSenderAdapter.kt` |
| Slack Tools 설정 | `arc-slack/.../tools/config/SlackToolsProperties.kt` |
| Slack Tools scope 필터 | `arc-slack/.../tools/config/SlackScopeAwareLocalToolFilter.kt` |

### 12.2 대표 테스트 범주

현재 저장소에는 Slack 관련 테스트가 비교적 넓게 있다.

- AutoConfiguration 테스트
- Controller 테스트
- Processor 테스트
- Gateway 테스트
- Signature 검증 테스트
- Metrics 테스트
- Reminder / Thread Tracker / Response Tracker 테스트
- Use case / Tool 테스트
- User journey / cross-tool E2E 성격 테스트

대표 파일까지 보면 다음처럼 읽으면 된다.

- 사용자 흐름: `SlackUserJourneyScenarioTest.kt`, `DefaultSlackEventHandlerTest.kt`, `DefaultSlackCommandHandlerTest.kt`
- HTTP ingress / ACK / 바인딩: `SlackEventControllerTest.kt`, `SlackCommandControllerTest.kt`, `SlackCommandControllerWebBindingTest.kt`
- 프로세서/포화/레이트 리밋: `SlackEventProcessorTest.kt`, `SlackCommandProcessorTest.kt`, `SlackEventProcessorUserRateLimitTest.kt`, `SlackCommandProcessorRateLimitTest.kt`
- 프로액티브: `SlackEventProcessorProactiveTest.kt`, `DefaultSlackEventHandlerProactiveTest.kt`, `SlackCrossToolAndProactiveE2ETest.kt`
- 보안/하드닝: `SlackSignatureVerifierTest.kt`, `SlackSignatureWebFilterTest.kt`, `SlackEventDeduplicatorEdgeCaseTest.kt`, `SlackEventProcessorUserRateLimitTest.kt`, `SlackCommandProcessorRateLimitTest.kt`, `ToolInputValidationTest.kt`
- Socket Mode / 미지원 표면: `SlackSocketModeGatewayTest.kt`, `SlackEventProcessorReactionTest.kt`
- admin/ops / 관측성: `SlackApiHealthIndicatorTest.kt`, `SlackMetricsRecorderTest.kt`, `SlackEventControllerConcurrencyTest.kt`
- Slack Tools: `SlackToolsAutoConfigurationTest.kt`, `SlackScopeAwareLocalToolFilterTest.kt`, `ToolExposureResolverTest.kt`, `SlackApiClientTest.kt`, `SendMessageToolTest.kt`, `UploadFileToolTest.kt`, `UseCaseDelegationTest.kt`
- 아웃바운드 메시징/재시도 공백 확인: `SlackMessagingServiceTest.kt`, `SlackMessagingServiceGapTest.kt`, `SlackResponseUrlRetrierTest.kt`, `SlackMessageSenderAdapterTest.kt`
- 스케줄러 Slack 전달: `DynamicSchedulerServiceTest.kt`, `DynamicSchedulerServiceCoverageGapTest.kt`

즉, Slack은 “코드는 있는데 검증이 빈약한 모듈”보다는, 비교적 운영을 염두에 두고 테스트가 깔린 모듈에 가깝다.

---

## 13. 문서 작성 시 추천 표현

다른 문서나 보고에서 Slack 기능을 설명할 때는 다음처럼 구분해서 쓰는 것이 가장 덜 헷갈린다.

- `Slack 채팅 채널 연동`
  - 사용자가 Slack에서 Arc Reactor와 대화하는 기능
- `Slack 운영 연동`
  - 프로액티브 채널, 스케줄 결과 전송, 메트릭/헬스/감사
- `Slack API 도구 연동`
  - 에이전트가 Slack API를 읽고 쓰는 도구 기능

이 세 가지를 섞어 쓰면 기능 범위가 금방 흐려진다.

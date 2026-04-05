# 멀티 봇 페르소나 설계

## 개요

1개의 Arc Reactor 인스턴스에 여러 Slack 봇을 연결하여, 각 봇이 다른 페르소나(전문 분야)로 동작하는 구조.

## 사용 시나리오

```
유저: @hr-helper 연차 며칠 남았어?
HR봇: 연차 잔여일수는 인사팀(인재경영실)에서 확인하실 수 있습니다.
      사내 인트라넷 > 인사 > 연차조회에서 직접 확인하실 수도 있습니다.

유저: @it-support VPN 연결이 안돼요
IT봇: VPN 연결 문제를 해결해 드리겠습니다.
      1. VPN 클라이언트 버전 확인 (최신: v4.2.1)
      2. 네트워크 설정 초기화: 설정 > 네트워크 > 재설정
      문제가 지속되면 IT지원팀 Slack #it-helpdesk로 문의해 주세요.

유저: @edu-guide 이번달 교육 일정 알려줘
교육봇: 이번달 교육 일정입니다.
       • 4/10 리더십 워크숍 (14:00~17:00)
       • 4/15 신입사원 OJT (09:00~12:00)
       (Confluence 교육운영1팀 스페이스에서 확인)
```

## 아키텍처

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│ HR봇 (Slack) │  │ IT봇 (Slack) │  │ 교육봇(Slack)│
│ xoxb-hr-... │  │ xoxb-it-... │  │ xoxb-edu-.. │
│ xapp-hr-... │  │ xapp-it-... │  │ xapp-edu-.. │
└──────┬──────┘  └──────┬──────┘  └──────┬──────┘
       │                │                │
       ▼                ▼                ▼
┌──────────────────────────────────────────────┐
│           Arc Reactor (단일 인스턴스)           │
│                                              │
│  SlackMultiBotManager                        │
│  ┌────────┐ ┌────────┐ ┌────────┐           │
│  │ Bot #1 │ │ Bot #2 │ │ Bot #3 │           │
│  │persona │ │persona │ │persona │           │
│  │= "hr"  │ │= "it"  │ │= "edu" │           │
│  └────┬───┘ └────┬───┘ └────┬───┘           │
│       │          │          │                │
│       ▼          ▼          ▼                │
│  ┌──────────────────────────────┐            │
│  │    AgentExecutor (공유)       │            │
│  │    MCP 도구 (공유)            │            │
│  │    Guard Pipeline (공유)      │            │
│  └──────────────────────────────┘            │
└──────────────────────────────────────────────┘
```

## 데이터 모델

### DB: `slack_bot_instances` 테이블 (신규)

```sql
CREATE TABLE IF NOT EXISTS slack_bot_instances (
    id              VARCHAR(36)     PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL UNIQUE,
    bot_token       VARCHAR(255)    NOT NULL,
    app_token       VARCHAR(255)    NOT NULL,
    persona_id      VARCHAR(36)     NOT NULL REFERENCES personas(id),
    channel_id      VARCHAR(100),           -- 기본 채널 (선택)
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 설정 (application.yml)

```yaml
arc:
  reactor:
    slack:
      # 기존 단일 봇 설정 (하위 호환)
      enabled: true
      bot-token: ${SLACK_BOT_TOKEN:}
      app-token: ${SLACK_APP_TOKEN:}

      # 멀티 봇 설정 (DB 기반)
      multi-bot:
        enabled: ${ARC_REACTOR_SLACK_MULTI_BOT_ENABLED:false}
        # DB에서 slack_bot_instances 로드
```

### Persona 예시 (DB)

```
id: "hr-persona"
name: "HR 전문가"
systemPrompt: |
  당신은 휴넷의 HR 전문 AI 어시스턴트입니다.
  인사, 노무, 연차, 급여, 복리후생, 채용 관련 질문에 특화되어 있습니다.
  인사 관련 Confluence 문서를 우선 검색하여 근거 기반으로 답변합니다.
  민감한 개인 정보(급여 등)는 DM이나 인사팀 직접 문의를 안내합니다.

id: "it-persona"
name: "IT 지원 전문가"
systemPrompt: |
  당신은 휴넷의 IT 지원 AI 어시스턴트입니다.
  계정, VPN, 이메일, 장비, 소프트웨어 설치, 네트워크 문제에 특화되어 있습니다.
  문제 해결 단계를 구체적으로 안내하고, 해결 불가 시 IT지원팀 연결을 안내합니다.

id: "edu-persona"
name: "교육 코디네이터"
systemPrompt: |
  당신은 휴넷의 교육 코디네이터 AI 어시스턴트입니다.
  교육 일정, 수강 신청, 학습 자료, 교육 이수 현황에 특화되어 있습니다.
  교육운영팀 Confluence 스페이스를 우선 검색합니다.
```

## 구현 설계

### 1. SlackBotInstance 모델

```kotlin
data class SlackBotInstance(
    val id: String,
    val name: String,
    val botToken: String,
    val appToken: String,
    val personaId: String,
    val channelId: String? = null,
    val enabled: Boolean = true
)
```

### 2. SlackMultiBotManager

```kotlin
class SlackMultiBotManager(
    private val botInstanceStore: SlackBotInstanceStore,
    private val personaStore: PersonaStore,
    private val agentExecutor: AgentExecutor,
    private val mcpManager: McpManager
) {
    // 봇 인스턴스별 독립 WebSocket 연결
    private val activeConnections = ConcurrentHashMap<String, SlackSocketModeGateway>()

    fun startAll() {
        val instances = botInstanceStore.listEnabled()
        for (instance in instances) {
            startBot(instance)
        }
    }

    fun startBot(instance: SlackBotInstance) {
        val persona = personaStore.get(instance.personaId)
        val gateway = SlackSocketModeGateway(
            botToken = instance.botToken,
            appToken = instance.appToken
        )
        val handler = DefaultSlackEventHandler(
            agentExecutor = agentExecutor,
            personaStore = personaStore,  // 이 봇의 페르소나 사용
            // ... 공유 의존성
        )
        gateway.connect(handler)
        activeConnections[instance.id] = gateway
    }

    fun stopBot(instanceId: String) {
        activeConnections.remove(instanceId)?.disconnect()
    }
}
```

### 3. 이벤트 핸들링 흐름

```
Slack WebSocket 이벤트 수신
  ↓
어떤 봇의 연결인지 식별 (botToken으로)
  ↓
해당 봇의 personaId로 페르소나 로드
  ↓
페르소나 systemPrompt + 시스템 고정 규칙(파일) 조합
  ↓
AgentExecutor.execute() (도구/Guard/MCP는 공유)
  ↓
해당 봇의 botToken으로 Slack 응답
```

### 4. Admin API

```
POST   /api/admin/slack-bots           — 봇 인스턴스 등록
GET    /api/admin/slack-bots           — 목록 조회
PUT    /api/admin/slack-bots/{id}      — 수정 (페르소나 변경 등)
DELETE /api/admin/slack-bots/{id}      — 삭제
POST   /api/admin/slack-bots/{id}/start  — 개별 시작
POST   /api/admin/slack-bots/{id}/stop   — 개별 중지
```

## 구현 순서

1. **DB 마이그레이션**: `slack_bot_instances` 테이블 생성
2. **SlackBotInstance 모델 + Store**: CRUD
3. **SlackMultiBotManager**: 멀티 봇 연결 관리
4. **DefaultSlackEventHandler 수정**: personaId를 봇 인스턴스에서 받도록
5. **Admin API 컨트롤러**: 봇 CRUD + 시작/중지
6. **Admin UI 연동**: 봇 관리 화면

## 구현 대상 파일

| 파일 | 작업 |
|------|------|
| `V44__create_slack_bot_instances.sql` | 신규 — DB 마이그레이션 |
| `SlackBotInstance.kt` | 신규 — 모델 + Store 인터페이스 |
| `JdbcSlackBotInstanceStore.kt` | 신규 — JDBC 구현 |
| `SlackMultiBotManager.kt` | 신규 — 멀티 봇 연결 관리 |
| `SlackMultiBotAutoConfiguration.kt` | 신규 — 자동 설정 |
| `SlackBotAdminController.kt` | 신규 — Admin API |
| `DefaultSlackEventHandler.kt` | 수정 — 봇별 페르소나 분기 |
| `SlackAutoConfiguration.kt` | 수정 — 멀티 봇 모드 분기 |

## 하위 호환

- `multi-bot.enabled=false` (기본값): 기존 단일 봇 동작 유지
- `multi-bot.enabled=true`: DB 기반 멀티 봇 활성화
- 기존 `SLACK_BOT_TOKEN` / `SLACK_APP_TOKEN` 환경변수는 단일 봇 모드에서 계속 사용

## 봇 등록 시 필요한 것 (Slack 앱 설정)

각 봇마다 별도의 Slack 앱을 생성해야 합니다:

1. https://api.slack.com/apps → **Create New App**
2. 앱 이름 설정 (예: "HR Helper", "IT Support")
3. Bot Token Scopes 설정 (기존과 동일)
4. Socket Mode 활성화 + App Token 생성
5. Event Subscriptions → `app_mention` 구독
6. Install to Workspace
7. Bot Token (`xoxb-...`) + App Token (`xapp-...`) 복사
8. Arc Reactor Admin에서 봇 인스턴스 등록 + 페르소나 연결

## 비용 고려

- 봇 N개 × LLM 호출 = 비용 N배 가능
- 각 봇의 사용량 모니터링 필요
- 봇별 rate limit 설정 가능하도록 설계
- 페르소나에 `maxToolCalls` 설정으로 도구 호출 제한 가능

## 보안

- Bot Token은 DB에 암호화 저장 권장 (AES-256)
- Admin API는 ADMIN 권한 필수
- 각 봇의 접근 가능 채널/스페이스를 제한 가능

# Arc Reactor 기능 가이드 (통합 기능 카탈로그)

작성일: 2026-04-06  
목적: Arc Reactor 전체 생태계 기능을 엔지니어링/운영 관점에서 정리

## 0) 자동 분석 블록 (2분 간격 동기화)

이 섹션은 2분마다 cron으로 자동 재생성됩니다.

<!-- AUTO-ANALYSIS-BLOCK-START -->
- 마지막 동기화: 2026-04-06 08:24:21 +0900
- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
- 생성 방식: README/ECOSYSTEM.md 기반 정합성 스냅샷 + git 메타데이터

### 0) 작성 규칙(문체/근거/의사결정)

- 톤: 임원 보고용으로 한국어 존댓말(입니다/됩니다) 사용
- 증거 우선: 기능/관계/운영 포인트는 가능한 경우 구현 파일(경로·라인)로 뒷받침
- 미확인 항목 표기: “미확인(운영 연동/실행 검증 필요)”로 분리
- 반복 억제: 동일 주장 재사용을 피하고, 단락당 핵심 포인트 1개 이하 정렬
- 용어 정합성: Arc Reactor, MCP, IAM, ReAct, Slack, SSE를 일관된 표기 사용
- CTO 문서 반영 가이드: 각 섹션 말미에 ‘의사결정 포인트’ 1개 이상 삽입(리스크/기대효과/우선순위)

- 독자 가이드:
  - 임원/CTO: 지금 문서는 “왜 이 조합이 사업/조직에 필요한가”에 대한 판단 근거를 빠르게 찾을 수 있게 정리합니다.
  - 기획/PO: 사용자 가치, 업무 적용 시나리오, 운영 제약을 중심으로 읽을 수 있게 핵심 동선을 앞부분에 배치합니다.
  - 개발자: API/도구/연동 코어 근거를 코드 라인 단위로 추적 가능한 형태로 정리합니다.
  - 운영/보안: 배포·가드·모니터링·권한 관리 포인트를 점검 항목 형태로 정리해 추적하기 쉽게 합니다.

### 1) 한눈에 보는 핵심 요약(직무 공통)

- 이 문서는 “무엇이 있는지”와 “왜 쓸만한지”를 별도로 구분해 보여줍니다.
- Arc Reactor는 aslan-iam 인증 기반 + MCP 생태계 동적 등록 + 웹/슬랙 채널 확장 + 관리 콘솔로 이어지는 중앙형 플랫폼입니다.
- 실무 도입 시점에는 기능 존재 여부보다 “운영 연동, 정책 일관성, 비용/안전 통제”의 확인이 우선입니다.

### 2) 프로젝트 상태 스냅샷

| 프로젝트 | 경로 | 역할 | 상태 | 근거 README | README 제목 | 브랜치 | 커밋 | 워크트리 | 갱신 |
|---|---|---|---|---|---|---|---|---|---|
| arc-reactor | /Users/jinan/ai/arc-reactor | 핵심 런타임 | OK | /Users/jinan/ai/arc-reactor/README.ko.md | Arc Reactor | main | 1773c962 | dirty | 77 minutes ago |
| aslan-iam | /Users/jinan/ai/aslan-iam | 중앙 인증(IAM) | OK | /Users/jinan/ai/aslan-iam/README.md | aslan-iam | main | ee88eb3 | dirty | 3 days ago |
| swagger-mcp-server | /Users/jinan/ai/swagger-mcp-server | API 도구 MCP | OK | /Users/jinan/ai/swagger-mcp-server/README.md | Swagger MCP Server | main | 8964374 | clean | 2 weeks ago |
| atlassian-mcp-server | /Users/jinan/ai/atlassian-mcp-server | Jira/Confluence/Bitbucket MCP | OK | /Users/jinan/ai/atlassian-mcp-server/README.md | atlassian-mcp-server | main | 054a127 | clean | 2 weeks ago |
| clipping-mcp-server | /Users/jinan/ai/clipping-mcp-server | 클리핑·요약 MCP | OK | /Users/jinan/ai/clipping-mcp-server/README.md | clipping-mcp-server | main | 23abc52 | clean | 10 hours ago |
| arc-reactor-admin | /Users/jinan/ai/arc-reactor-admin | 운영/모니터링 UI | OK | /Users/jinan/ai/arc-reactor-admin/README.md | arc-reactor-admin | main | ca42355 | clean | 10 hours ago |
| arc-reactor/arc-web | /Users/jinan/ai/arc-reactor/arc-web | 채팅/REST 모듈(내부) | OK | - | - | main | 1773c962 | dirty | 77 minutes ago |
| arc-reactor/arc-slack | /Users/jinan/ai/arc-reactor/arc-slack | 슬랙 채널 모듈(내부) | OK | - | - | main | 1773c962 | dirty | 77 minutes ago |
| arc-reactor-web | /Users/jinan/ai/arc-reactor-web | 사용자 UI | OK | /Users/jinan/ai/arc-reactor-web/README.ko.md | Arc Reactor Web | main | e0d84a8 | clean | 9 hours ago |
| Aslan-Verse-Web | /Users/jinan/ai/Aslan-Verse-Web | 실험형 멀티 페르소나 조직 플랫폼 | OK | - | - | main | c20bbdb | clean | 20 hours ago |

### 3) 아슬란 세계관 관점 관계(문서 근거 기반)

- Arc Reactor의 인증 중심은 aslan-iam의 공개키 검증 흐름과 결합되어 JWT를 로컬 검증하고, 로그인·권한·토큰 발급은 aslan-iam에서 분리 운영합니다 (`aslan-iam/README.md`, `aslan-iam/README.md`의 API 항목).
- Arc Reactor는 MCP 서버를 실행 중 등록만으로 붙이기 때문에, swagger/atlassian/clipping MCP는 `POST /api/mcp/servers` 중심의 런타임 등록/프록시 구조로 관리됩니다 (`arc-reactor/README.ko.md` 및 `arc-reactor/ECOSYSTEM.md`).
- 관리자 채널(`arc-reactor-admin`)은 arc-reactor Admin API를 통해 MCP preflight, 정책, 스케줄러, 도구 정책을 검증/운영하는 데 사용됩니다 (`arc-reactor-admin/README.md`, `arc-reactor/ECOSYSTEM.md`).
- 웹 채널(`arc-reactor-web`)은 세션 채팅, SSE 스트리밍, Persona/Tool Policy/Output Guard/스케줄러 관리 화면을 제공합니다 (`arc-reactor-web/README.ko.md`).
- Slack 채널(`arc-slack`)은 Socket Mode 또는 Events API로 동일 ReAct 엔진을 채널 확장합니다 (`arc-reactor/README.ko.md`의 채널 구성).
- Aslan-Verse-Web은 실험형 시뮬레이션 좌표로서, 조직·페르소나·태스크 네트워크 구조를 Arc Reactor 페르소나/툴 정책 모델과 결합해 팀형 실험을 구성하는 대상군으로 정의합니다 (`Aslan-Verse-Web/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md`).

### 3-1) 아슬란 통합 철학(흩어진 업무의 수렴)

- 기본 철학: 업무 도메인(인증, 커뮤니티, 클리핑, 보고/요약, 협업 자동화 등)이 분산되어 있어도, Arc Reactor를 중심으로 인증·도구·채널·운영 정책을 통합해 하나의 운영 체계로 묶습니다.
- 현재 근거:
  - 인증은 `aslan-iam`이 담당하고 Reactor는 공개키 기반 인증 토폴로지로 정책 수용 경로를 둡니다.
  - 작업 도구는 `POST /api/mcp/servers` 기반의 런타임 등록으로 공통 패턴에 편입됩니다.
  - 채널(웹/Slack)과 관리(운영콘솔)는 동일 정책 언어(guard/hook/tool policy)로 구동되어 조직 단위의 일관성을 유지합니다.
- 확장 로드맵: Reactor Work/Workflow를 빌더로 제공해, 팀 단위의 업무 흐름(예: 아이디어 수집→요약→승인→실행→리포팅)을 템플릿화하면 서비스별 도메인 로직만 붙여 재사용할 수 있습니다.
- 아슬란 세계관 확장 방향(계획 반영): 향후 신규 서비스가 추가될 경우, 각 서비스는 먼저 "MCP 서버 등록 방식 + Persona/Policy 설계 + Reactor 관찰 지표"의 3단계로 결합하면 기존 생태계 규칙을 유지하면서 확장할 수 있습니다.
- 의사결정 포인트: 향후 확장 시 “새 서비스마다 개별 보안 스택”보다 “Reactor 기반 정책 표준화”를 우선할지, 아니면 초기 PoC는 레이어별 게이트를 분리 적용할지 판단이 필요합니다.

### 4) 연동 포인트 근거 추출

- arc-reactor MCP 등록 포트/예시: curl -X POST http://localhost:18081/api/mcp/servers  
- arc-reactor MCP allowlist 가드: ｜ ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES ｜ Comma-separated MCP server allowlist (e.g. atlassian,swagger) ｜ 
- MCP 방식 운영 포인트(REST 등록, 접근 정책/Preflight): POST /api/mcp/servers, PUT /api/mcp/servers/{name}로 등록하고 갱신합니다. 
- Aslan-iam 인증 키 기반 검증: JWT 토큰 발급의 유일한 권한을 가지며, 타 서비스는 공개키로 로컬 검증만 수행합니다. 
- 실험 플랫폼 연결 근거: 가상 휴넷(Virtual Human Network) — AI 페르소나들이 실제 회사처럼 역할을 맡아 협업하는 플랫폼. 사용자는 AI 팀원과 함께 실무를 진행하고, 조직 구조와 업무 현황을 시각화하여 모니터링한다. 

### 5) 주의/검증 항목(자동 보고)

- `arc-reactor`는 현재 문서 스냅샷 기준으로 동적 MCP 등록/관리 API와 스케줄러/가드 설정이 구성되어 있음이 확인됩니다.
- 프로젝트별 README 존재 유무만으로 상태를 판정합니다. 문서 기반 근거가 변경되면 다음 실행에 반영됩니다.
- 더 정확한 실시간 상태(헬스/연동 테스트)는 현재 cron 스크립트 범위 밖입니다. 필요 시 별도 운영 모니터링 Job에 연결하세요.

### 6) 코드/설정 정합성 근거(자동 수집)

### arc-reactor 코드/설정 근거
- 저장소: /Users/jinan/ai/arc-reactor
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/arc-reactor/examples/chatbot/ChatbotExample.kt:38: * @RestController
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt:34:@RestController
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt:29:@RestController
  - /Users/jinan/ai/arc-reactor/arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/TenantAdminController.kt:42:@RestController

- 근거 패턴: `class .*Guard`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/tool/idempotency/InMemoryToolIdempotencyGuard.kt:21:class InMemoryToolIdempotencyGuard(
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/guard/blockrate/GuardBlockRateHook.kt:26:class GuardBlockRateHook(
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/guard/blockrate/GuardBlockRateAnomaly.kt:8:enum class GuardAnomalyType {
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/guard/blockrate/GuardBlockRateAnomaly.kt:27:data class GuardBlockRateAnomaly(

- 근거 패턴: `class .*Hook`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/promptlab/hook/ExperimentCaptureHook.kt:50:class ExperimentCaptureHook(
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/hook/Hook.kt:41: * class AuditHook : AfterAgentCompleteHook {
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/hook/model/HookModels.kt:16:sealed class HookResult {
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/hook/model/HookModels.kt:25:    data class Reject(val reason: String) : HookResult()

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/promptlab/autoconfigure/PromptLabConfiguration.kt:185:    class SchedulerConfiguration {
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/promptlab/PromptLabScheduler.kt:26:class PromptLabScheduler(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt:57:class SchedulerController(
  - /Users/jinan/ai/arc-reactor/arc-core/src/test/kotlin/com/arc/reactor/promptlab/PromptLabSchedulerTest.kt:26:class PromptLabSchedulerTest {

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/approval/ApprovalModels.kt:20:data class ToolApprovalRequest(
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/approval/ApprovalModels.kt:50:data class ToolApprovalResponse(
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/approval/ToolApprovalPolicy.kt:12: * class DestructiveToolPolicy : ToolApprovalPolicy {
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/approval/ToolApprovalPolicy.kt:23: * class AmountPolicy : ToolApprovalPolicy {

- 근거 패턴: `maxToolCalls`
  - /Users/jinan/ai/arc-reactor/helm/arc-reactor/values-production.yaml:58:    maxToolCalls: "10"
  - /Users/jinan/ai/arc-reactor/helm/arc-reactor/values.yaml:162:    maxToolCalls: "10"
  - /Users/jinan/ai/arc-reactor/helm/arc-reactor/templates/configmap.yaml:50:  ARC_REACTOR_MAX_TOOL_CALLS: {{ .Values.config.agent.maxToolCalls ｜ quote }}
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/intent/model/IntentModels.kt:56: * @param maxToolCalls 최대 도구 호출 수 오버라이드

- 근거 패턴: `max-tools-per-request`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/resources/application.yml:56:    max-tools-per-request: 30
  - /Users/jinan/ai/arc-reactor/arc-core/src/test/resources/application.yml:19:    max-tools-per-request: 20

- 근거 패턴: `/api/mcp/servers`
  - /Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/AdminCapabilitiesControllerTest.kt:49:            RequestMappingInfo.paths("/api/mcp/servers/{name}/preflight").build() to mockk<HandlerMethod>()
  - /Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/AdminCapabilitiesControllerTest.kt:64:        assertTrue(body.paths.contains("/api/mcp/servers/{name}/preflight")) {
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt:49:@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpPreflightController.kt:37:@RequestMapping("/api/mcp/servers/{name}/preflight")

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/resources/application.yml:208:        allowed-server-names: ${ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES:atlassian,swagger}

- 근거 패턴: `SSE`
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/cache/CacheMetricsRecorder.kt:78:    private val missCounter: Counter = Counter.builder(METRIC_CACHE_MISSES)
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/cache/CacheMetricsRecorder.kt:112:        private const val METRIC_CACHE_MISSES = "arc.cache.misses"
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/model/McpModels.kt:12: * @param transportType 전송 프로토콜 유형 (STDIO, SSE, HTTP)
  - /Users/jinan/ai/arc-reactor/arc-core/src/main/kotlin/com/arc/reactor/mcp/model/McpModels.kt:13: * @param config 전송별 설정 (예: SSE의 "url", STDIO의 "command"/"args")

### aslan-iam 코드/설정 근거
- 저장소: /Users/jinan/ai/aslan-iam
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/aslan-iam/iam-api/src/main/kotlin/com/aslan/iam/api/exception/GlobalExceptionHandler.kt:18:@RestControllerAdvice
  - /Users/jinan/ai/aslan-iam/iam-admin-api/src/main/kotlin/com/aslan/iam/admin/exception/AdminExceptionHandler.kt:14:@RestControllerAdvice
  - /Users/jinan/ai/aslan-iam/iam-api/src/main/kotlin/com/aslan/iam/api/controller/TwoFactorController.kt:12:@RestController
  - /Users/jinan/ai/aslan-iam/iam-api/src/main/kotlin/com/aslan/iam/api/controller/AdminController.kt:19:@RestController

- 근거 패턴: `class .*Guard`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/aslan-iam/iam-api/src/main/kotlin/com/aslan/iam/config/InactiveAccountScheduler.kt:11:class InactiveAccountScheduler(

- 근거 패턴: `class .*Tool`
  - (해당 패턴 미발견)

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - (해당 패턴 미발견)

### swagger-mcp-server 코드/설정 근거
- 저장소: /Users/jinan/ai/swagger-mcp-server
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/AdminApiExceptionHandler.kt:14:@RestControllerAdvice(assignableTypes = [SpecSourceAdminController::class])
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/AdminPreflightController.kt:16:@RestController
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/SpecSourceAdminController.kt:25:@RestController
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/AdminAccessPolicyController.kt:18:@RestController

- 근거 패턴: `class .*Guard`
  - /Users/jinan/ai/swagger-mcp-server/src/test/kotlin/com/swagger/mcpserver/ArchitectureGuardTest.kt:8:class ArchitectureGuardTest {

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/catalog/SpecSourceScheduler.kt:16:class SpecSourceScheduler(
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/config/SchedulerConfig.kt:8:class SchedulerConfig {

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/tool/SpecSchemaTool.kt:9:class SpecSchemaTool(
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/tool/CatalogListRevisionsTool.kt:9:class CatalogListRevisionsTool(
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/tool/SpecLoadTool.kt:17:class SpecLoadTool(
  - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/tool/SpecRemoveTool.kt:11:class SpecRemoveTool(

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/swagger-mcp-server/build.gradle.kts:35:    // Spring AI MCP Server (SSE via WebFlux)

### atlassian-mcp-server 코드/설정 근거
- 저장소: /Users/jinan/ai/atlassian-mcp-server
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/AdminPreflightController.kt:27:@RestController
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/AdminAccessPolicyController.kt:24:@RestController

- 근거 패턴: `class .*Guard`
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/work/WorkPersonalInterruptGuardTool.kt:19:class WorkPersonalInterruptGuardTool(
  - /Users/jinan/ai/atlassian-mcp-server/src/test/kotlin/com/atlassian/mcpserver/tool/WorkPersonalInterruptGuardToolTest.kt:30:class WorkPersonalInterruptGuardToolTest {

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/bitbucket/BitbucketRepoTool.kt:19:class BitbucketRepoTool(
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/bitbucket/BitbucketPRTool.kt:23:class BitbucketPRTool(
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/confluence/ConfluenceSearchTool.kt:20:class ConfluenceSearchTool(
  - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/confluence/ConfluencePageTool.kt:26:class ConfluencePageTool(

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/atlassian-mcp-server/build.gradle.kts:29:    // Spring AI MCP Server (SSE via Servlet/WebMVC)

### clipping-mcp-server 코드/설정 근거
- 저장소: /Users/jinan/ai/clipping-mcp-server
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/config/SpaController.kt:10:@RestController
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/admin/CategoryRuleAdminController.kt:21:@RestController
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/admin/KeywordTrendAdminController.kt:13:@RestController
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/admin/UserCompetitorViewController.kt:21:@RestController

- 근거 패턴: `class .*Guard`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/service/SlackTokenValidationScheduler.kt:19:class SlackTokenValidationScheduler(
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/service/EmptyCategoryScheduler.kt:21:class EmptyCategoryScheduler(
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/service/SystemStatusService.kt:122:    data class SchedulerInfo(
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/config/SchedulerConfig.kt:19:class SchedulerConfig {

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/tool/ClipCollectAsyncTool.kt:9:class ClipCollectAsyncTool(private val asyncClipJobService: AsyncClipJobService) {
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/tool/ClipExportTool.kt:9:class ClipExportTool(private val clippingService: ClippingService) {
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/tool/CategoryListTool.kt:9:class CategoryListTool(private val categoryService: CategoryService) {
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/tool/ClipDigestTool.kt:9:class ClipDigestTool(private val clippingService: ClippingService) {

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/clipping-mcp-server/build.gradle.kts:38:    // Spring AI MCP Server (SSE via WebFlux)
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/service/UserAccountApprovalService.kt:272:                val code = if (e.message?.contains("상태") == true) "ALREADY_PROCESSED" else "INVALID_ROLE"
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/service/UserAccountApprovalService.kt:326:                val code = if (e.message?.contains("상태") == true) "ALREADY_PROCESSED" else "INVALID_ROLE"
  - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/admin/AdminUserRequestController.kt:137:                    is com.clipping.mcpserver.error.InvalidInputException -> "ALREADY_PROCESSED"

### arc-reactor-admin 코드/설정 근거
- 저장소: /Users/jinan/ai/arc-reactor-admin
- 근거 패턴: `@RestController|@Controller`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Guard`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Tool`
  - (해당 패턴 미발견)

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/arc-reactor-admin/pnpm-lock.yaml:1997:    resolution: {integrity: sha512-1to4zXBxmXHV3IiSSEInrreIlu02vUOvrhxJJH5vcxYTBDAx51cqZiKdyTxlecdKNSjj8EcxGBxNf6Vg+945gw==}

### arc-reactor/arc-web 코드/설정 근거
- 저장소: /Users/jinan/ai/arc-reactor/arc-web
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt:54:@RestController
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt:53:@RestController
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionCandidateController.kt:43:@RestController
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt:48:@RestController

- 근거 패턴: `class .*Guard`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt:61:class OutputGuardRuleController(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt:298:data class CreateOutputGuardRuleRequest(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt:320:data class UpdateOutputGuardRuleRequest(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt:332:data class OutputGuardSimulationRequest(

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt:57:class SchedulerController(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OpsDashboardController.kt:396:data class SchedulerOpsSummary(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/OpsDashboardController.kt:405:data class RecentSchedulerExecutionSummary(
  - /Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/controller/SchedulerControllerConditionalTest.kt:19:class SchedulerControllerConditionalTest {

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt:41:class ToolPolicyController(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt:122:data class ToolPolicyStateResponse(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt:129:data class ToolPolicyResponse(
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt:140:data class UpdateToolPolicyRequest(

- 근거 패턴: `maxToolCalls`
  - /Users/jinan/ai/arc-reactor/arc-web/src/test/kotlin/com/arc/reactor/integration/IntentControllerJdbcIntegrationTest.kt:106:                        "maxToolCalls" to 5,

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt:49:@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpPreflightController.kt:37:@RequestMapping("/api/mcp/servers/{name}/preflight")
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpAccessPolicyController.kt:42:@RequestMapping("/api/mcp/servers/{name}/access-policy")
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/McpServerController.kt:44: * - GET    /api/mcp/servers                    : 전체 서버 목록 및 상태 조회

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt:54: * - POST /api/chat/stream  : 스트리밍 응답 (SSE, 실시간 토큰 단위 전송)
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt:56: * ## SSE 이벤트 타입 (스트리밍)
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt:117:     * 스트리밍 채팅 -- 타입이 지정된 SSE 이벤트를 통한 실시간 응답.
  - /Users/jinan/ai/arc-reactor/arc-web/src/main/kotlin/com/arc/reactor/controller/ChatController.kt:119:     * SSE 이벤트 타입:

### arc-reactor/arc-slack 코드/설정 근거
- 저장소: /Users/jinan/ai/arc-reactor/arc-slack
- 근거 패턴: `@RestController|@Controller`
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt:34:@RestController
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt:29:@RestController

- 근거 패턴: `class .*Guard`
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/session/SlackThreadTrackerTest.kt:50:    inner class BlankInputGuard {

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/handler/SlackReminderScheduler.kt:27:class SlackReminderScheduler(
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/handler/SlackReminderSchedulerTest.kt:19:class SlackReminderSchedulerTest {

- 근거 패턴: `class .*Tool`
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/tools/tool/CreateCanvasTool.kt:9:open class CreateCanvasTool(
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/tools/observability/ToolObservabilityAspect.kt:27:class ToolObservabilityAspect(
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/tools/observability/ToolObservabilityAspect.kt:125:    private data class ToolOutcome(
  - /Users/jinan/ai/arc-reactor/arc-slack/src/main/kotlin/com/arc/reactor/slack/tools/tool/ReadThreadRepliesTool.kt:9:open class ReadThreadRepliesTool(private val readThreadRepliesUseCase: ReadThreadRepliesUseCase) : LocalTool {

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/SlackCrossToolAndProactiveE2ETest.kt:53:            McpServer(name = name, transportType = McpTransportType.SSE, config = emptyMap())
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/DefaultSlackEventHandlerProactiveTest.kt:63:            McpServer(name = name, transportType = McpTransportType.SSE, config = emptyMap())
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/handler/SlackHandlerSupportTest.kt:27:    /** 테스트용 SSE 기반 McpServer 팩토리. */
  - /Users/jinan/ai/arc-reactor/arc-slack/src/test/kotlin/com/arc/reactor/slack/handler/SlackHandlerSupportTest.kt:30:        transportType = McpTransportType.SSE,

### arc-reactor-web 코드/설정 근거
- 저장소: /Users/jinan/ai/arc-reactor-web
- 근거 패턴: `@RestController|@Controller`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Guard`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Tool`
  - (해당 패턴 미발견)

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - /Users/jinan/ai/arc-reactor-web/pnpm-lock.yaml:2044:    resolution: {integrity: sha512-1to4zXBxmXHV3IiSSEInrreIlu02vUOvrhxJJH5vcxYTBDAx51cqZiKdyTxlecdKNSjj8EcxGBxNf6Vg+945gw==}

### Aslan-Verse-Web 코드/설정 근거
- 저장소: /Users/jinan/ai/Aslan-Verse-Web
- 근거 패턴: `@RestController|@Controller`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Guard`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Hook`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Scheduler`
  - (해당 패턴 미발견)

- 근거 패턴: `class .*Tool`
  - (해당 패턴 미발견)

- 근거 패턴: `maxToolCalls`
  - (해당 패턴 미발견)

- 근거 패턴: `max-tools-per-request`
  - (해당 패턴 미발견)

- 근거 패턴: `/api/mcp/servers`
  - (해당 패턴 미발견)

- 근거 패턴: `ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES`
  - (해당 패턴 미발견)

- 근거 패턴: `SSE`
  - (해당 패턴 미발견)


### 7) API 구현 근거(Controller/Route)

### arc-reactor API 엔드포인트 근거
- 저장소: /Users/jinan/ai/arc-reactor
  - examples/chatbot/ChatbotExample.kt
    - 41: *     @PostMapping("/chat") -> 42: *     suspend fun chat(@RequestBody request: ChatRequest): ChatResponse {
      - 42: *     suspend fun chat(@RequestBody request: ChatRequest): ChatResponse {
  - arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/TenantAdminController.kt
    - 47:@RequestMapping("/api/admin/tenant")
      - 59:    @Operation(summary = "Get tenant overview dashboard")
    - 65:    @GetMapping("/overview") -> 66:    fun overview(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 66:    fun overview(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 80:    @GetMapping("/usage") -> 81:    fun usage(
      - 81:    fun usage(
    - 98:    @GetMapping("/quality") -> 99:    fun quality(
      - 99:    fun quality(
    - 116:    @GetMapping("/tools") -> 117:    fun tools(
      - 117:    fun tools(
    - 134:    @GetMapping("/cost") -> 135:    fun cost(
      - 135:    fun cost(
    - 153:    @GetMapping("/slo") -> 154:    fun slo(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 154:    fun slo(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 174:    @GetMapping("/alerts") -> 175:    fun alerts(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 175:    fun alerts(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 188:    @GetMapping("/quota") -> 189:    fun quota(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 189:    fun quota(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 213:    @GetMapping("/export/executions") -> 214:    fun exportExecutions(
      - 214:    fun exportExecutions(
    - 242:    @GetMapping("/export/tools") -> 243:    fun exportTools(
      - 243:    fun exportTools(
  - arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/PlatformAdminController.kt
    - 63:@RequestMapping("/api/admin/platform")
    - 89:    @GetMapping("/health") -> 90:    fun health(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 90:    fun health(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 115:    @GetMapping("/users/by-email") -> 116:    fun getUserByEmail(
      - 116:    fun getUserByEmail(
    - 139:    @PostMapping("/users/{id}/role") -> 140:    fun updateUserRole(
      - 140:    fun updateUserRole(
    - 175:    @GetMapping("/tenants") -> 176:    fun listTenants(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 176:    fun listTenants(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 188:    @GetMapping("/tenants/{id}") -> 189:    fun getTenant(
      - 189:    fun getTenant(
    - 206:    @PostMapping("/tenants") -> 207:    fun createTenant(
      - 207:    fun createTenant(
    - 242:    @PostMapping("/tenants/{id}/suspend") -> 243:    fun suspendTenant(
      - 243:    fun suspendTenant(
    - 271:    @PostMapping("/tenants/{id}/activate") -> 272:    fun activateTenant(
      - 272:    fun activateTenant(
    - 299:    @GetMapping("/tenants/analytics") -> 300:    fun tenantAnalytics(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 300:    fun tenantAnalytics(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 333:    @GetMapping("/pricing") -> 334:    fun listPricing(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 334:    fun listPricing(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 345:    @PostMapping("/pricing") -> 346:    fun upsertPricing(
      - 346:    fun upsertPricing(
    - 370:    @PostMapping("/cache/invalidate") -> 371:    fun invalidateResponseCache(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 371:    fun invalidateResponseCache(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 414:    @GetMapping("/alerts/rules") -> 415:    fun listAlertRules(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 415:    fun listAlertRules(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 426:    @PostMapping("/alerts/rules") -> 427:    fun saveAlertRule(
      - 427:    fun saveAlertRule(
    - 451:    @DeleteMapping("/alerts/rules/{id}") -> 452:    fun deleteAlertRule(
      - 452:    fun deleteAlertRule(
    - 478:    @GetMapping("/alerts") -> 479:    fun activeAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 479:    fun activeAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 490:    @PostMapping("/alerts/{id}/resolve") -> 491:    fun resolveAlert(
      - 491:    fun resolveAlert(
    - 514:    @PostMapping("/alerts/evaluate") -> 515:    fun evaluateAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 515:    fun evaluateAlerts(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 536:    @GetMapping("/cache/stats") -> 537:    fun cacheStats(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 537:    fun cacheStats(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 572:    @GetMapping("/vectorstore/stats") -> 573:    fun vectorStoreStats(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 573:    fun vectorStoreStats(exchange: ServerWebExchange): ResponseEntity<Any> {
  - arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/MetricIngestionController.kt
    - 39:@RequestMapping("/api/admin/metrics/ingest")
      - 50:    @Operation(summary = "Ingest MCP health event")
      - 57:    fun ingestMcpHealth(
    - 56:    @PostMapping("/mcp-health") -> 57:    fun ingestMcpHealth(
      - 57:    fun ingestMcpHealth(
    - 82:    @PostMapping("/tool-call") -> 83:    fun ingestToolCall(
      - 83:    fun ingestToolCall(
    - 111:    @PostMapping("/eval-result") -> 112:    fun ingestEvalResult(
      - 112:    fun ingestEvalResult(
    - 127:    @PostMapping("/eval-results") -> 128:    fun ingestEvalResults(
      - 128:    fun ingestEvalResults(
    - 189:    @PostMapping("/batch") -> 190:    fun ingestBatch(
      - 190:    fun ingestBatch(
  - arc-admin/src/main/kotlin/com/arc/reactor/admin/controller/AdminSessionController.kt
    - 44:@RequestMapping("/api/admin")
      - 52:    @Operation(summary = "대화 Overview 통계 조회")
      - 58:    fun getOverview(
    - 57:    @GetMapping("/sessions/overview") -> 58:    fun getOverview(
      - 58:    fun getOverview(
    - 76:    @GetMapping("/sessions") -> 77:    fun listSessions(
      - 77:    fun listSessions(
    - 111:    @GetMapping("/sessions/{sessionId}") -> 112:    fun getSessionDetail(
      - 112:    fun getSessionDetail(
    - 131:    @DeleteMapping("/sessions/{sessionId}") -> 132:    fun deleteSession(
      - 132:    fun deleteSession(
    - 161:    @GetMapping("/sessions/{sessionId}/export") -> 162:    fun exportSession(
      - 162:    fun exportSession(
    - 218:    @GetMapping("/users") -> 219:    fun listUsers(
      - 219:    fun listUsers(
    - 242:    @GetMapping("/users/{userId}/sessions") -> 243:    fun listUserSessions(
      - 243:    fun listUserSessions(
    - 277:    @PostMapping("/sessions/{sessionId}/tags") -> 278:    fun addTag(
      - 278:    fun addTag(
    - 300:    @DeleteMapping("/sessions/{sessionId}/tags/{tagId}") -> 301:    fun removeTag(
      - 301:    fun removeTag(
  - arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt
    - 35:@RequestMapping("/api/slack")
      - 39:@Tag(name = "Slack", description = "Slack 이벤트 웹훅 엔드포인트")
      - 45:    @Operation(summary = "Slack 이벤트 콜백 수신 (URL 검증 포함)")
      - 50:    suspend fun handleEvent(
    - 44:    @PostMapping("/events") -> 50:    suspend fun handleEvent(
      - 45:    @Operation(summary = "Slack 이벤트 콜백 수신 (URL 검증 포함)")
      - 50:    suspend fun handleEvent(
  - arc-slack/src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt
    - 30:@RequestMapping("/api/slack")
      - 34:@Tag(name = "Slack", description = "Slack 슬래시 명령 처리 엔드포인트")
    - 49:    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]) -> 55:    fun handleSlashCommandEndpoint(
      - 50:    @Operation(summary = "Slack 슬래시 명령 수신 및 처리")
      - 55:    fun handleSlashCommandEndpoint(
  - arc-web/src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt
    - 55:@RequestMapping("/api/scheduler/jobs")
      - 62:    @Operation(summary = "전체 예약 작업 목록 조회 (태그 필터 선택)")
      - 68:    fun listJobs(
    - 67:    @GetMapping -> 68:    fun listJobs(
      - 68:    fun listJobs(
    - 88:    @PostMapping -> 89:    fun createJob(
      - 89:    fun createJob(
    - 111:    @GetMapping("/{id}") -> 112:    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
      - 112:    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
    - 126:    @PutMapping("/{id}") -> 127:    fun updateJob(
      - 127:    fun updateJob(
    - 150:    @DeleteMapping("/{id}") -> 151:    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
      - 151:    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
    - 165:    @PostMapping("/{id}/trigger") -> 166:    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
      - 166:    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
    - 182:    @PostMapping("/{id}/dry-run") -> 183:    fun dryRunJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
      - 183:    fun dryRunJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
    - 199:    @GetMapping("/{id}/executions") -> 200:    fun getExecutions(
      - 200:    fun getExecutions(
  - arc-web/src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt
    - 54:@RequestMapping("/api/output-guard/rules")
    - 90:    @GetMapping -> 91:    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 91:    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 103:    @GetMapping("/audits") -> 104:    fun listAudits(
      - 104:    fun listAudits(
    - 122:    @PostMapping -> 123:    fun createRule(
      - 123:    fun createRule(
    - 165:    @PutMapping("/{id}") -> 166:    fun updateRule(
      - 166:    fun updateRule(
    - 210:    @DeleteMapping("/{id}") -> 211:    fun deleteRule(
      - 211:    fun deleteRule(
    - 236:    @PostMapping("/simulate") -> 237:    fun simulate(
      - 237:    fun simulate(
  - arc-web/src/main/kotlin/com/arc/reactor/controller/PersonaController.kt
    - 43:@RequestMapping("/api/personas") -> 55:    fun listPersonas(
      - 49:    @Operation(summary = "전체 페르소나 목록 조회 (관리자)")
      - 55:    fun listPersonas(
    - 54:    @GetMapping -> 55:    fun listPersonas(
      - 55:    fun listPersonas(
    - 71:    @GetMapping("/{personaId}") -> 72:    fun getPersona(@PathVariable personaId: String): ResponseEntity<Any> {
      - 72:    fun getPersona(@PathVariable personaId: String): ResponseEntity<Any> {
    - 85:    @PostMapping -> 86:    fun createPersona(
      - 86:    fun createPersona(
    - 115:    @PutMapping("/{personaId}") -> 116:    fun updatePersona(
      - 116:    fun updatePersona(
    - 143:    @DeleteMapping("/{personaId}") -> 144:    fun deletePersona(
      - 144:    fun deletePersona(
  - arc-web/src/main/kotlin/com/arc/reactor/controller/RagIngestionCandidateController.kt
    - 44:@RequestMapping("/api/rag-ingestion/candidates")
      - 57:    @Operation(summary = "RAG Ingestion 후보 목록 조회 (관리자)")
    - 62:    @GetMapping -> 63:    fun list(
      - 63:    fun list(
    - 83:    @PostMapping("/{id}/approve") -> 84:    fun approve(
      - 84:    fun approve(
    - 137:    @PostMapping("/{id}/reject") -> 138:    fun reject(
      - 138:    fun reject(
  - arc-web/src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt
    - 49:@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
      - 59:    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 목록 조회")
      - 66:    suspend fun listSources(
    - 65:    @GetMapping -> 66:    suspend fun listSources(
      - 66:    suspend fun listSources(
    - 87:    @GetMapping("/{sourceName}") -> 88:    suspend fun getSource(
      - 88:    suspend fun getSource(
    - 106:    @PostMapping -> 107:    suspend fun createSource(
      - 107:    suspend fun createSource(
    - 124:    @PutMapping("/{sourceName}") -> 125:    suspend fun updateSource(
      - 125:    suspend fun updateSource(
    - 144:    @PostMapping("/{sourceName}/sync") -> 145:    suspend fun syncSource(
      - 145:    suspend fun syncSource(
    - 163:    @GetMapping("/{sourceName}/revisions") -> 164:    suspend fun listRevisions(
      - 164:    suspend fun listRevisions(
    - 184:    @GetMapping("/{sourceName}/diff") -> 185:    suspend fun getDiff(
      - 185:    suspend fun getDiff(
    - 209:    @PostMapping("/{sourceName}/publish") -> 210:    suspend fun publishRevision(
      - 210:    suspend fun publishRevision(

### aslan-iam API 엔드포인트 근거
- 저장소: /Users/jinan/ai/aslan-iam
  - iam-api/src/main/kotlin/com/aslan/iam/api/exception/GlobalExceptionHandler.kt
    - (매핑 애노테이션 미발견)
  - iam-api/src/main/kotlin/com/aslan/iam/api/controller/TwoFactorController.kt
    - 13:@RequestMapping("/api/2fa") -> 19:    fun setup(@AuthenticationPrincipal userId: String): ResponseEntity<TwoFactorSetupResponse> {
      - 19:    fun setup(@AuthenticationPrincipal userId: String): ResponseEntity<TwoFactorSetupResponse> {
    - 18:    @PostMapping("/setup") -> 19:    fun setup(@AuthenticationPrincipal userId: String): ResponseEntity<TwoFactorSetupResponse> {
      - 19:    fun setup(@AuthenticationPrincipal userId: String): ResponseEntity<TwoFactorSetupResponse> {
    - 26:    @PostMapping("/enable") -> 27:    fun enable(
      - 27:    fun enable(
    - 35:    @PostMapping("/disable") -> 36:    fun disable(
      - 36:    fun disable(
  - iam-admin-api/src/main/kotlin/com/aslan/iam/admin/exception/AdminExceptionHandler.kt
    - (매핑 애노테이션 미발견)
  - iam-api/src/main/kotlin/com/aslan/iam/api/controller/AdminController.kt
    - 20:@RequestMapping("/api/admin") -> 30:    fun assignRole(
      - 30:    fun assignRole(
    - 29:    @PostMapping("/users/{userId}/roles") -> 30:    fun assignRole(
      - 30:    fun assignRole(
    - 40:    @DeleteMapping("/users/{userId}/roles") -> 41:    fun revokeRole(
      - 41:    fun revokeRole(
    - 51:    @DeleteMapping("/users/{userId}/sessions") -> 52:    fun forceLogout(
      - 52:    fun forceLogout(
  - iam-api/src/main/kotlin/com/aslan/iam/api/controller/AuthController.kt
    - 15:@RequestMapping("/api/auth") -> 26:    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<RegisterResponse> {
      - 26:    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<RegisterResponse> {
    - 25:    @PostMapping("/register") -> 26:    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<RegisterResponse> {
      - 26:    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<RegisterResponse> {
    - 35:    @PostMapping("/login") -> 36:    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<TokenResponse> {
      - 36:    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<TokenResponse> {
    - 57:    @PostMapping("/refresh") -> 58:    fun refresh(@Valid @RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> {
      - 58:    fun refresh(@Valid @RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> {
    - 69:    @PostMapping("/logout") -> 70:    fun logout(@AuthenticationPrincipal userId: String): ResponseEntity<Void> {
      - 70:    fun logout(@AuthenticationPrincipal userId: String): ResponseEntity<Void> {
    - 75:    @PostMapping("/change-password") -> 76:    fun changePassword(
      - 76:    fun changePassword(
    - 90:    @GetMapping("/public-key") -> 91:    fun getPublicKey(): ResponseEntity<PublicKeyResponse> {
      - 91:    fun getPublicKey(): ResponseEntity<PublicKeyResponse> {
  - iam-admin-api/src/main/kotlin/com/aslan/iam/admin/controller/AdminController.kt
    - 22:@RequestMapping("/api/admin")
    - 43:    @GetMapping("/users") -> 44:    fun getUsers(
      - 44:    fun getUsers(
    - 67:    @GetMapping("/users/{userId}") -> 68:    fun getUserDetail(
      - 68:    fun getUserDetail(
    - 85:    @PatchMapping("/users/{userId}/status") -> 86:    fun updateUserStatus(
      - 86:    fun updateUserStatus(
    - 97:    @DeleteMapping("/users/{userId}/2fa") -> 98:    fun forceDisable2FA(
      - 98:    fun forceDisable2FA(
    - 107:    @PostMapping("/users/{userId}/roles") -> 108:    fun assignRole(
      - 108:    fun assignRole(
    - 119:    @DeleteMapping("/users/{userId}/roles") -> 120:    fun revokeRole(
      - 120:    fun revokeRole(
    - 131:    @DeleteMapping("/users/{userId}/sessions") -> 132:    fun forceLogout(
      - 132:    fun forceLogout(
    - 143:    @PatchMapping("/users/bulk/status") -> 144:    fun bulkUpdateStatus(
      - 144:    fun bulkUpdateStatus(
    - 152:    @DeleteMapping("/users/bulk/sessions") -> 153:    fun bulkForceLogout(
      - 153:    fun bulkForceLogout(
    - 161:    @PostMapping("/users/bulk/roles") -> 162:    fun bulkAssignRole(
      - 162:    fun bulkAssignRole(
    - 172:    @GetMapping("/roles") -> 173:    fun getRoles(): ResponseEntity<List<RoleResponse>> {
      - 173:    fun getRoles(): ResponseEntity<List<RoleResponse>> {
    - 185:    @PostMapping("/roles") -> 186:    fun createRole(
      - 186:    fun createRole(
    - 202:    @PutMapping("/roles/{roleId}") -> 203:    fun updateRole(
      - 203:    fun updateRole(
    - 213:    @DeleteMapping("/roles/{roleId}") -> 214:    fun deleteRole(
      - 214:    fun deleteRole(
    - 223:    @GetMapping("/permissions") -> 224:    fun getPermissions(): ResponseEntity<List<PermissionResponse>> {
      - 224:    fun getPermissions(): ResponseEntity<List<PermissionResponse>> {
    - 233:    @GetMapping("/audit-logs") -> 234:    fun getAuditLogs(
      - 234:    fun getAuditLogs(
    - 275:    @GetMapping("/dashboard/stats") -> 276:    fun getDashboardStats(): ResponseEntity<DashboardStats> =
      - 276:    fun getDashboardStats(): ResponseEntity<DashboardStats> =
    - 279:    @GetMapping("/dashboard/login-trend") -> 280:    fun getLoginTrend(@RequestParam(defaultValue = "7") days: Int): ResponseEntity<List<LoginTrendEntry>> =
      - 280:    fun getLoginTrend(@RequestParam(defaultValue = "7") days: Int): ResponseEntity<List<LoginTrendEntry>> =
    - 285:    @GetMapping("/sessions") -> 286:    fun getSessions(
      - 286:    fun getSessions(
    - 314:    @DeleteMapping("/sessions") -> 315:    fun revokeAllSessions(@AuthenticationPrincipal adminId: String): ResponseEntity<Void> {
      - 315:    fun revokeAllSessions(@AuthenticationPrincipal adminId: String): ResponseEntity<Void> {
    - 323:    @GetMapping("/users/export") -> 324:    fun exportUsers(
      - 324:    fun exportUsers(
    - 361:    @GetMapping("/settings/system") -> 362:    fun getSystemSettings(): ResponseEntity<SystemSettingsResponse> {
      - 362:    fun getSystemSettings(): ResponseEntity<SystemSettingsResponse> {
    - 391:    @GetMapping("/security-policies") -> 392:    fun getSecurityPolicies(): ResponseEntity<SecurityPoliciesResponse> =
      - 392:    fun getSecurityPolicies(): ResponseEntity<SecurityPoliciesResponse> =

### swagger-mcp-server API 엔드포인트 근거
- 저장소: /Users/jinan/ai/swagger-mcp-server
  - src/main/kotlin/com/swagger/mcpserver/controller/AdminApiExceptionHandler.kt
    - (매핑 애노테이션 미발견)
  - src/main/kotlin/com/swagger/mcpserver/controller/AdminPreflightController.kt
    - 17:@RequestMapping("/admin/preflight") -> 27:    fun preflight(): ResponseEntity<Any> {
      - 25:    @Operation(summary = "Run production-readiness preflight checks for swagger MCP")
      - 27:    fun preflight(): ResponseEntity<Any> {
    - 26:    @GetMapping -> 27:    fun preflight(): ResponseEntity<Any> {
      - 27:    fun preflight(): ResponseEntity<Any> {
  - src/main/kotlin/com/swagger/mcpserver/controller/SpecSourceAdminController.kt
    - 26:@RequestMapping("/admin/spec-sources") -> 37:    fun listSources(): Mono<List<SpecSourceResponse>> = blocking {
      - 37:    fun listSources(): Mono<List<SpecSourceResponse>> = blocking {
    - 36:    @GetMapping -> 37:    fun listSources(): Mono<List<SpecSourceResponse>> = blocking {
      - 37:    fun listSources(): Mono<List<SpecSourceResponse>> = blocking {
    - 41:    @GetMapping("/{name}") -> 42:    fun getSource(@PathVariable name: String): Mono<SpecSourceResponse> = blocking {
      - 42:    fun getSource(@PathVariable name: String): Mono<SpecSourceResponse> = blocking {
    - 46:    @PostMapping -> 47:    fun createSource(@Valid @RequestBody request: CreateSpecSourceRequest): Mono<ResponseEntity<SpecSourceResponse>> = blocking {
      - 47:    fun createSource(@Valid @RequestBody request: CreateSpecSourceRequest): Mono<ResponseEntity<SpecSourceResponse>> = blocking {
    - 63:    @PutMapping("/{name}") -> 64:    fun updateSource(
      - 64:    fun updateSource(
    - 83:    @PostMapping("/{name}/sync") -> 84:    fun syncSource(@PathVariable name: String): Mono<SpecSyncResponse> = blocking {
      - 84:    fun syncSource(@PathVariable name: String): Mono<SpecSyncResponse> = blocking {
    - 88:    @GetMapping("/{name}/revisions") -> 89:    fun listRevisions(
      - 89:    fun listRevisions(
    - 96:    @GetMapping("/{name}/diff") -> 97:    fun getDiff(
      - 97:    fun getDiff(
    - 105:    @PostMapping("/{name}/publish") -> 106:    fun publishRevision(
      - 106:    fun publishRevision(
  - src/main/kotlin/com/swagger/mcpserver/controller/AdminAccessPolicyController.kt
    - 19:@RequestMapping("/admin/access-policy") -> 26:    fun get(): ResponseEntity<Any> = ResponseEntity.ok(toResponse(policyStore.get()))
      - 24:    @Operation(summary = "Get current swagger spec access policy")
      - 26:    fun get(): ResponseEntity<Any> = ResponseEntity.ok(toResponse(policyStore.get()))
    - 25:    @GetMapping -> 26:    fun get(): ResponseEntity<Any> = ResponseEntity.ok(toResponse(policyStore.get()))
      - 26:    fun get(): ResponseEntity<Any> = ResponseEntity.ok(toResponse(policyStore.get()))
    - 29:    @PutMapping -> 30:    fun put(@Valid @RequestBody request: UpdateSpecAccessPolicyRequest): ResponseEntity<Any> {
      - 30:    fun put(@Valid @RequestBody request: UpdateSpecAccessPolicyRequest): ResponseEntity<Any> {
    - 49:    @DeleteMapping -> 50:    fun clear(): ResponseEntity<Any> {
      - 50:    fun clear(): ResponseEntity<Any> {

### atlassian-mcp-server API 엔드포인트 근거
- 저장소: /Users/jinan/ai/atlassian-mcp-server
  - src/main/kotlin/com/atlassian/mcpserver/AdminPreflightController.kt
    - 28:@RequestMapping("/admin/preflight")
      - 43:    @Operation(
    - 47:    @GetMapping -> 48:    fun run(): ResponseEntity<Any> {
      - 48:    fun run(): ResponseEntity<Any> {
  - src/main/kotlin/com/atlassian/mcpserver/AdminAccessPolicyController.kt
    - 25:@RequestMapping("/admin/access-policy") -> 36:    fun get(): ResponseEntity<Any> {
      - 34:    @Operation(summary = "Get current access policy (allowlists)")
      - 36:    fun get(): ResponseEntity<Any> {
    - 35:    @GetMapping -> 36:    fun get(): ResponseEntity<Any> {
      - 36:    fun get(): ResponseEntity<Any> {
    - 59:    @PutMapping -> 60:    fun put(
      - 60:    fun put(
    - 88:    @DeleteMapping -> 89:    fun clear(httpRequest: HttpServletRequest): ResponseEntity<Any> {
      - 89:    fun clear(httpRequest: HttpServletRequest): ResponseEntity<Any> {
    - 116:    @PostMapping("/emergency-deny-all") -> 117:    fun emergencyDenyAll(httpRequest: HttpServletRequest): ResponseEntity<Any> {
      - 117:    fun emergencyDenyAll(httpRequest: HttpServletRequest): ResponseEntity<Any> {
    - 147:    @GetMapping("/audits") -> 148:    fun listAudits(
      - 148:    fun listAudits(

### clipping-mcp-server API 엔드포인트 근거
- 저장소: /Users/jinan/ai/clipping-mcp-server
  - src/main/kotlin/com/clipping/mcpserver/user/UserSetupSourceController.kt
    - 28:@RequestMapping("/api/user/setup/sources") -> 38:    fun searchKnownSources(
      - 38:    fun searchKnownSources(
    - 37:    @GetMapping("/known-sources") -> 38:    fun searchKnownSources(
      - 38:    fun searchKnownSources(
    - 61:    @GetMapping("/curated") -> 62:    fun listCuratedSources(): List<CuratedSourceDto> {
      - 62:    fun listCuratedSources(): List<CuratedSourceDto> {
    - 78:    @PostMapping -> 80:    fun create(
      - 80:    fun create(
    - 100:    @PostMapping("/validate-url") -> 101:    fun validateUrl(
      - 101:    fun validateUrl(
    - 118:    @PostMapping("/{id}/verify") -> 119:    fun verify(
      - 119:    fun verify(
    - 133:    @PostMapping("/{id}/approve") -> 134:    fun approve(
      - 134:    fun approve(
  - src/main/kotlin/com/clipping/mcpserver/config/SpaController.kt
    - 17:    @GetMapping(value = ["/", "/login", "/signup", "/admin/**", "/user/**"], produces = [MediaType.TEXT_HTML_VALUE]) -> 18:    fun spa(): Mono<String> = Mono.just(indexHtml)
      - 18:    fun spa(): Mono<String> = Mono.just(indexHtml)
  - src/main/kotlin/com/clipping/mcpserver/user/UserDeliveryLogController.kt
    - 16:@RequestMapping("/api/user/delivery-logs") -> 27:    fun getDeliveryLogs(
      - 27:    fun getDeliveryLogs(
    - 26:    @GetMapping -> 27:    fun getDeliveryLogs(
      - 27:    fun getDeliveryLogs(
  - src/main/kotlin/com/clipping/mcpserver/user/UserArticleHistoryController.kt
    - 22:@RequestMapping("/api/user/history") -> 31:    fun searchArticles(
      - 31:    fun searchArticles(
    - 30:    @GetMapping("/articles") -> 31:    fun searchArticles(
      - 31:    fun searchArticles(
    - 56:    @GetMapping("/articles/{summaryId}") -> 57:    fun getArticleDetail(
      - 57:    fun getArticleDetail(
    - 70:    @PostMapping("/articles/{summaryId}/bookmark") -> 71:    fun toggleBookmark(
      - 71:    fun toggleBookmark(
  - src/main/kotlin/com/clipping/mcpserver/user/UserClippingRequestController.kt
    - 27:@RequestMapping("/api/user/requests") -> 34:    fun list(authentication: Authentication): List<UserClippingRequestResponse> =
      - 34:    fun list(authentication: Authentication): List<UserClippingRequestResponse> =
    - 33:    @GetMapping -> 34:    fun list(authentication: Authentication): List<UserClippingRequestResponse> =
      - 34:    fun list(authentication: Authentication): List<UserClippingRequestResponse> =
    - 38:    @PostMapping -> 40:    fun create(
      - 40:    fun create(
    - 65:    @PostMapping("/{id}/withdraw") -> 66:    fun withdraw(
      - 66:    fun withdraw(
    - 73:    @PostMapping("/{id}/unsubscribe") -> 74:    fun unsubscribe(
      - 74:    fun unsubscribe(
    - 81:    @DeleteMapping("/{id}/remove") -> 83:    fun deleteRequest(
      - 83:    fun deleteRequest(
    - 94:    @PostMapping("/wizard-ownership") -> 96:    fun registerWizardOwnership(
      - 96:    fun registerWizardOwnership(
    - 116:    @PostMapping("/rss-sources") -> 118:    fun createAdditionalRssSources(
      - 118:    fun createAdditionalRssSources(
  - src/main/kotlin/com/clipping/mcpserver/user/UserDeliveryScheduleController.kt
    - 14:@RequestMapping("/api/user/delivery-schedule") -> 24:    fun getSchedule(authentication: Authentication): DeliveryScheduleResponse {
      - 24:    fun getSchedule(authentication: Authentication): DeliveryScheduleResponse {
    - 23:    @GetMapping -> 24:    fun getSchedule(authentication: Authentication): DeliveryScheduleResponse {
      - 24:    fun getSchedule(authentication: Authentication): DeliveryScheduleResponse {
    - 33:    @PutMapping -> 34:    fun updateSchedule(
      - 34:    fun updateSchedule(
  - src/main/kotlin/com/clipping/mcpserver/user/UserEventController.kt
    - 23:@RequestMapping("/api/user/events") -> 35:    fun trackEvents(
      - 35:    fun trackEvents(
    - 33:    @PostMapping -> 35:    fun trackEvents(
      - 35:    fun trackEvents(
  - src/main/kotlin/com/clipping/mcpserver/user/UserBriefingController.kt
    - 15:@RequestMapping("/api/user/briefing") -> 25:    fun getTodayBriefings(
      - 25:    fun getTodayBriefings(
    - 24:    @GetMapping("/today") -> 25:    fun getTodayBriefings(
      - 25:    fun getTodayBriefings(
  - src/main/kotlin/com/clipping/mcpserver/user/CompanySearchUserController.kt
    - 15:@RequestMapping("/api/user/companies") -> 27:    fun search(
      - 27:    fun search(
    - 26:    @GetMapping -> 27:    fun search(
      - 27:    fun search(
  - src/main/kotlin/com/clipping/mcpserver/user/UserAccountController.kt
    - 17:@RequestMapping("/api/user/account") -> 27:    fun selfWithdraw(
      - 27:    fun selfWithdraw(
    - 25:    @PostMapping("/withdraw") -> 27:    fun selfWithdraw(
      - 27:    fun selfWithdraw(
  - src/main/kotlin/com/clipping/mcpserver/admin/CategoryRuleAdminController.kt
    - 22:@RequestMapping("/api/admin/category-rules") -> 32:    fun getStats(@RequestParam(defaultValue = "7") days: Int): RuleStatsResponse {
      - 32:    fun getStats(@RequestParam(defaultValue = "7") days: Int): RuleStatsResponse {
    - 31:    @GetMapping("/stats") -> 32:    fun getStats(@RequestParam(defaultValue = "7") days: Int): RuleStatsResponse {
      - 32:    fun getStats(@RequestParam(defaultValue = "7") days: Int): RuleStatsResponse {
    - 54:    @GetMapping("/{categoryId}/excluded-items") -> 55:    fun getExcludedItems(
      - 55:    fun getExcludedItems(
    - 77:    @GetMapping("/{categoryId}") -> 78:    fun get(@PathVariable categoryId: String): CategoryRuleResponse =
      - 78:    fun get(@PathVariable categoryId: String): CategoryRuleResponse =
    - 84:    @PutMapping("/{categoryId}") -> 85:    fun update(
      - 85:    fun update(
  - src/main/kotlin/com/clipping/mcpserver/admin/KeywordTrendAdminController.kt
    - 14:@RequestMapping("/api/admin/keywords")
      - 27:    fun getKeywordTrend(
    - 26:    @GetMapping("/trend") -> 27:    fun getKeywordTrend(
      - 27:    fun getKeywordTrend(

### arc-reactor-admin API 엔드포인트 근거
- 저장소: /Users/jinan/ai/arc-reactor-admin
  - 컨트롤러 구현 파일 미발견(또는 테스트만 존재)

### arc-reactor/arc-web API 엔드포인트 근거
- 저장소: /Users/jinan/ai/arc-reactor/arc-web
  - src/main/kotlin/com/arc/reactor/controller/SchedulerController.kt
    - 55:@RequestMapping("/api/scheduler/jobs")
      - 62:    @Operation(summary = "전체 예약 작업 목록 조회 (태그 필터 선택)")
      - 68:    fun listJobs(
    - 67:    @GetMapping -> 68:    fun listJobs(
      - 68:    fun listJobs(
    - 88:    @PostMapping -> 89:    fun createJob(
      - 89:    fun createJob(
    - 111:    @GetMapping("/{id}") -> 112:    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
      - 112:    fun getJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
    - 126:    @PutMapping("/{id}") -> 127:    fun updateJob(
      - 127:    fun updateJob(
    - 150:    @DeleteMapping("/{id}") -> 151:    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
      - 151:    fun deleteJob(@PathVariable id: String, exchange: ServerWebExchange): ResponseEntity<Any> {
    - 165:    @PostMapping("/{id}/trigger") -> 166:    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
      - 166:    fun triggerJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
    - 182:    @PostMapping("/{id}/dry-run") -> 183:    fun dryRunJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
      - 183:    fun dryRunJob(@PathVariable id: String, exchange: ServerWebExchange): Mono<ResponseEntity<Any>> {
    - 199:    @GetMapping("/{id}/executions") -> 200:    fun getExecutions(
      - 200:    fun getExecutions(
  - src/main/kotlin/com/arc/reactor/controller/OutputGuardRuleController.kt
    - 54:@RequestMapping("/api/output-guard/rules")
    - 90:    @GetMapping -> 91:    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 91:    fun listRules(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 103:    @GetMapping("/audits") -> 104:    fun listAudits(
      - 104:    fun listAudits(
    - 122:    @PostMapping -> 123:    fun createRule(
      - 123:    fun createRule(
    - 165:    @PutMapping("/{id}") -> 166:    fun updateRule(
      - 166:    fun updateRule(
    - 210:    @DeleteMapping("/{id}") -> 211:    fun deleteRule(
      - 211:    fun deleteRule(
    - 236:    @PostMapping("/simulate") -> 237:    fun simulate(
      - 237:    fun simulate(
  - src/main/kotlin/com/arc/reactor/controller/RagIngestionCandidateController.kt
    - 44:@RequestMapping("/api/rag-ingestion/candidates")
      - 57:    @Operation(summary = "RAG Ingestion 후보 목록 조회 (관리자)")
    - 62:    @GetMapping -> 63:    fun list(
      - 63:    fun list(
    - 83:    @PostMapping("/{id}/approve") -> 84:    fun approve(
      - 84:    fun approve(
    - 137:    @PostMapping("/{id}/reject") -> 138:    fun reject(
      - 138:    fun reject(
  - src/main/kotlin/com/arc/reactor/controller/McpSwaggerCatalogController.kt
    - 49:@RequestMapping("/api/mcp/servers/{name}/swagger/sources")
      - 59:    @Operation(summary = "MCP admin API를 통해 Swagger 스펙 소스 목록 조회")
      - 66:    suspend fun listSources(
    - 65:    @GetMapping -> 66:    suspend fun listSources(
      - 66:    suspend fun listSources(
    - 87:    @GetMapping("/{sourceName}") -> 88:    suspend fun getSource(
      - 88:    suspend fun getSource(
    - 106:    @PostMapping -> 107:    suspend fun createSource(
      - 107:    suspend fun createSource(
    - 124:    @PutMapping("/{sourceName}") -> 125:    suspend fun updateSource(
      - 125:    suspend fun updateSource(
    - 144:    @PostMapping("/{sourceName}/sync") -> 145:    suspend fun syncSource(
      - 145:    suspend fun syncSource(
    - 163:    @GetMapping("/{sourceName}/revisions") -> 164:    suspend fun listRevisions(
      - 164:    suspend fun listRevisions(
    - 184:    @GetMapping("/{sourceName}/diff") -> 185:    suspend fun getDiff(
      - 185:    suspend fun getDiff(
    - 209:    @PostMapping("/{sourceName}/publish") -> 210:    suspend fun publishRevision(
      - 210:    suspend fun publishRevision(
  - src/main/kotlin/com/arc/reactor/controller/ChatController.kt
    - 68:@RequestMapping("/api/chat")
    - 104:    @PostMapping -> 105:    suspend fun chat(
      - 105:    suspend fun chat(
    - 153:    @PostMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE]) -> 154:    suspend fun chatStream(
      - 154:    suspend fun chatStream(
  - src/main/kotlin/com/arc/reactor/controller/DocumentController.kt
    - 51:@RequestMapping("/api/documents")
      - 64:    @Operation(summary = "벡터 스토어에 문서 추가 (관리자)")
    - 72:    @PostMapping -> 73:    fun addDocument(
      - 73:    fun addDocument(
    - 129:    @PostMapping("/batch") -> 130:    fun addDocuments(
      - 130:    fun addDocuments(
    - 181:    @PostMapping("/search") -> 182:    fun searchDocuments(@Valid @RequestBody request: SearchDocumentRequest): List<SearchResultResponse> {
      - 182:    fun searchDocuments(@Valid @RequestBody request: SearchDocumentRequest): List<SearchResultResponse> {
    - 213:    @DeleteMapping -> 214:    fun deleteDocuments(
      - 214:    fun deleteDocuments(
  - src/main/kotlin/com/arc/reactor/controller/ToolPolicyController.kt
    - 36:@RequestMapping("/api/tool-policy")
      - 49:    @Operation(summary = "Tool Policy 상태 조회 (적용 중 + 저장) (관리자)")
    - 54:    @GetMapping -> 55:    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 55:    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 76:    @PutMapping -> 77:    fun update(
      - 77:    fun update(
    - 104:    @DeleteMapping -> 105:    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 105:    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
  - src/main/kotlin/com/arc/reactor/controller/GlobalExceptionHandler.kt
    - (매핑 애노테이션 미발견)
  - src/main/kotlin/com/arc/reactor/controller/RagIngestionPolicyController.kt
    - 37:@RequestMapping("/api/rag-ingestion/policy")
      - 50:    @Operation(summary = "RAG Ingestion Policy 상태 조회 (적용 중 + 저장) (관리자)")
    - 55:    @GetMapping -> 56:    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 56:    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
    - 75:    @PutMapping -> 76:    fun update(
      - 76:    fun update(
    - 103:    @DeleteMapping -> 104:    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
      - 104:    fun delete(exchange: ServerWebExchange): ResponseEntity<Any> {
  - src/main/kotlin/com/arc/reactor/controller/McpPreflightController.kt
    - 37:@RequestMapping("/api/mcp/servers/{name}/preflight")
      - 47:    @Operation(summary = "MCP 서버 admin preflight 점검 실행 (프록시)")
    - 55:    @GetMapping -> 56:    suspend fun getPreflight(
      - 56:    suspend fun getPreflight(
  - src/main/kotlin/com/arc/reactor/controller/PromptLabController.kt
    - 63:@RequestMapping("/api/prompt-lab")
    - 94:    @PostMapping("/experiments") -> 95:    fun createExperiment(
      - 95:    fun createExperiment(
    - 129:    @GetMapping("/experiments") -> 130:    fun listExperiments(
      - 130:    fun listExperiments(
    - 147:    @GetMapping("/experiments/{id}") -> 148:    fun getExperiment(
      - 148:    fun getExperiment(
    - 167:    @PostMapping("/experiments/{id}/run") -> 168:    fun runExperiment(
      - 168:    fun runExperiment(
    - 208:    @PostMapping("/experiments/{id}/cancel") -> 209:    fun cancelExperiment(
      - 209:    fun cancelExperiment(
    - 236:    @GetMapping("/experiments/{id}/status") -> 237:    fun getStatus(
      - 237:    fun getStatus(
    - 261:    @GetMapping("/experiments/{id}/trials") -> 262:    fun getTrials(
      - 262:    fun getTrials(
    - 278:    @GetMapping("/experiments/{id}/report") -> 279:    fun getReport(
      - 279:    fun getReport(
    - 295:    @DeleteMapping("/experiments/{id}") -> 296:    fun deleteExperiment(
      - 296:    fun deleteExperiment(
    - 314:    @PostMapping("/auto-optimize") -> 315:    fun autoOptimize(
      - 315:    fun autoOptimize(
    - 353:    @PostMapping("/analyze") -> 354:    suspend fun analyzeFeedback(
      - 354:    suspend fun analyzeFeedback(
    - 374:    @PostMapping("/experiments/{id}/activate") -> 375:    fun activateRecommended(
      - 375:    fun activateRecommended(
  - src/main/kotlin/com/arc/reactor/controller/MultipartChatController.kt
    - 38:@RequestMapping("/api/chat")
    - 67:    @PostMapping("/multipart", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]) -> 68:    suspend fun chatMultipart(
      - 68:    suspend fun chatMultipart(

### arc-reactor/arc-slack API 엔드포인트 근거
- 저장소: /Users/jinan/ai/arc-reactor/arc-slack
  - src/main/kotlin/com/arc/reactor/slack/controller/SlackEventController.kt
    - 35:@RequestMapping("/api/slack")
      - 39:@Tag(name = "Slack", description = "Slack 이벤트 웹훅 엔드포인트")
      - 45:    @Operation(summary = "Slack 이벤트 콜백 수신 (URL 검증 포함)")
      - 50:    suspend fun handleEvent(
    - 44:    @PostMapping("/events") -> 50:    suspend fun handleEvent(
      - 45:    @Operation(summary = "Slack 이벤트 콜백 수신 (URL 검증 포함)")
      - 50:    suspend fun handleEvent(
  - src/main/kotlin/com/arc/reactor/slack/controller/SlackCommandController.kt
    - 30:@RequestMapping("/api/slack")
      - 34:@Tag(name = "Slack", description = "Slack 슬래시 명령 처리 엔드포인트")
    - 49:    @PostMapping("/commands", consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE]) -> 55:    fun handleSlashCommandEndpoint(
      - 50:    @Operation(summary = "Slack 슬래시 명령 수신 및 처리")
      - 55:    fun handleSlashCommandEndpoint(

### arc-reactor-web API 엔드포인트 근거
- 저장소: /Users/jinan/ai/arc-reactor-web
  - 컨트롤러 구현 파일 미발견(또는 테스트만 존재)

### Aslan-Verse-Web API 엔드포인트 근거
- 저장소: /Users/jinan/ai/Aslan-Verse-Web
  - 컨트롤러 구현 파일 미발견(또는 테스트만 존재)


### 8) 이번 주기 변경량 요약

### arc-reactor 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경(최대 8):
  - ?? bin/
  - ?? playwright_out.pdf
  - ?? reports/
  - ?? scripts/ops/generate-arc-reactor-docs-pdf.sh
  - ?? scripts/ops/run-pdfs.sh

### aslan-iam 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경(최대 8):
  -  M docker-compose.yml
  -  M iam-admin-api/src/main/resources/application.yml
  -  M iam-api/src/main/resources/application.yml

### swagger-mcp-server 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음

### atlassian-mcp-server 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음

### clipping-mcp-server 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음

### arc-reactor-admin 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음

### arc-reactor/arc-web 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경(최대 8):
  - ?? ../bin/
  - ?? ../playwright_out.pdf
  - ?? ../reports/
  - ?? ../scripts/ops/generate-arc-reactor-docs-pdf.sh
  - ?? ../scripts/ops/run-pdfs.sh

### arc-reactor/arc-slack 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경(최대 8):
  - ?? ../bin/
  - ?? ../playwright_out.pdf
  - ?? ../reports/
  - ?? ../scripts/ops/generate-arc-reactor-docs-pdf.sh
  - ?? ../scripts/ops/run-pdfs.sh

### arc-reactor-web 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음

### Aslan-Verse-Web 변경량 요약
- 최근 2분 커밋: 없음
- 현재 워크트리 변경: 없음


### 9) 저장소 상호연동 근거(키워드 매칭)

- 분석 방식: 각 저장소의 구성 파일/컨트롤러에서 다른 컴포넌트 명칭을 근거로 상호 연동 단서를 추출합니다.

#### arc-reactor
- 저장소: /Users/jinan/ai/arc-reactor
  - aslan-iam 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:33:- Arc Reactor는 aslan-iam 인증 기반 + MCP 생태계 동적 등록 + 웹/슬랙 채널 확장 + 관리 콘솔로 이어지는 중앙형 플랫폼입니다.
  - swagger-mcp-server 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:42:｜ swagger-mcp-server ｜ /Users/jinan/ai/swagger-mcp-server ｜ API 도구 MCP ｜ OK ｜ /Users/jinan/ai/swagger-mcp-server/README.md ｜ Swagger MCP Server ｜ main ｜ 8964374 ｜ clean ｜ 2 weeks ago ｜
  - atlassian-mcp-server 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:43:｜ atlassian-mcp-server ｜ /Users/jinan/ai/atlassian-mcp-server ｜ Jira/Confluence/Bitbucket MCP ｜ OK ｜ /Users/jinan/ai/atlassian-mcp-server/README.md ｜ atlassian-mcp-server ｜ main ｜ 054a127 ｜ clean ｜ 2 weeks ago ｜
  - clipping-mcp-server 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:44:｜ clipping-mcp-server ｜ /Users/jinan/ai/clipping-mcp-server ｜ 클리핑·요약 MCP ｜ OK ｜ /Users/jinan/ai/clipping-mcp-server/README.md ｜ clipping-mcp-server ｜ main ｜ 23abc52 ｜ clean ｜ 3 hours ago ｜
  - arc-reactor-admin 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:45:｜ arc-reactor-admin ｜ /Users/jinan/ai/arc-reactor-admin ｜ 운영/모니터링 UI ｜ OK ｜ /Users/jinan/ai/arc-reactor-admin/README.md ｜ arc-reactor-admin ｜ main ｜ ca42355 ｜ clean ｜ 3 hours ago ｜
  - arc-reactor-web 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:12:- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:48:｜ arc-reactor-web ｜ /Users/jinan/ai/arc-reactor-web ｜ 사용자 UI ｜ OK ｜ /Users/jinan/ai/arc-reactor-web/README.ko.md ｜ Arc Reactor Web ｜ main ｜ e0d84a8 ｜ clean ｜ 3 hours ago ｜
  - MCP 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/KNOWN_ACCEPTABLE.md:84:｜ `McpAdminHmacSupport.kt:64` hex + MessageDigest per request ｜ P4 ｜ MCP admin proxy 전용, 저빈도 ｜ 2026-03-13 ｜
    - /Users/jinan/ai/arc-reactor/KNOWN_ACCEPTABLE.md:97:｜ `McpServerController.kt:432` getStatus per server in list ｜ P4 ｜ MCP 서버 수 소규모 ｜ 2026-03-13 ｜
  - JWT 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/KNOWN_ACCEPTABLE.md:215:｜ `JwtAuthWebFilter.kt:57` blank sub → "anonymous" ｜ P4 ｜ JWT 서명키 탈취 전제. 키 보유 시 어떤 userId도 위장 가능. 방어적 폴백 수준 ｜ 2026-03-14 ｜
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:53:- Arc Reactor의 인증 중심은 aslan-iam의 공개키 검증 흐름과 결합되어 JWT를 로컬 검증하고, 로그인·권한·토큰 발급은 aslan-iam에서 분리 운영합니다 (`aslan-iam/README.md`, `aslan-iam/README.md`의 API 항목).
  - SSE 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:21:- 용어 정합성: Arc Reactor, MCP, IAM, ReAct, Slack, SSE를 일관된 표기 사용
    - /Users/jinan/ai/arc-reactor/reports/arc-reactor-full-feature-guide.md:56:- 웹 채널(`arc-reactor-web`)은 세션 채팅, SSE 스트리밍, Persona/Tool Policy/Output Guard/스케줄러 관리 화면을 제공합니다 (`arc-reactor-web/README.ko.md`).
  - tenant 언급/연동 증거
    - /Users/jinan/ai/arc-reactor/KNOWN_ACCEPTABLE.md:24:｜ `TenantResolver.kt:83` (manager tenant override) ｜ P3 ｜ Self-hosted 배포 설계 의도 (기존 항목과 동일) ｜ 2026-03-12 ｜
    - /Users/jinan/ai/arc-reactor/KNOWN_ACCEPTABLE.md:95:｜ `PlatformAdminController.kt:279` tenantAnalytics N+1 ｜ P4 ｜ 관리자 대시보드, 저빈도 ｜ 2026-03-13 ｜

#### aslan-iam
- 저장소: /Users/jinan/ai/aslan-iam
  - JWT 언급/연동 증거
    - /Users/jinan/ai/aslan-iam/CLAUDE.md:21:├── iam-infra/         # JWT, TOTP, BCrypt 어댑터 (순수 어댑터만)
    - /Users/jinan/ai/aslan-iam/CLAUDE.md:28:  ├── iam-infra       (JWT/TOTP/BCrypt 어댑터)      │

#### swagger-mcp-server
- 저장소: /Users/jinan/ai/swagger-mcp-server
  - MCP 언급/연동 증거
    - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/AdminPreflightController.kt:15:@Tag(name = "Admin", description = "Admin endpoints (requires SWAGGER_MCP_ADMIN_TOKEN header)")
    - /Users/jinan/ai/swagger-mcp-server/src/main/kotlin/com/swagger/mcpserver/controller/AdminPreflightController.kt:25:    @Operation(summary = "Run production-readiness preflight checks for swagger MCP")
  - JWT 언급/연동 증거
    - /Users/jinan/ai/swagger-mcp-server/README.md:233:curl -X POST -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  - SSE 언급/연동 증거
    - /Users/jinan/ai/swagger-mcp-server/README.md:20:# SSE 서버 실행 (기본, 포트 8081)
    - /Users/jinan/ai/swagger-mcp-server/README.md:102:｜ MCP ｜ spring-ai-starter-mcp-server-webflux (SSE + STDIO) ｜

#### atlassian-mcp-server
- 저장소: /Users/jinan/ai/atlassian-mcp-server
  - MCP 언급/연동 증거
    - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/tool/PersonalRequesterIdentityResolver.kt:84:                        "MCP_REQUIRE_EXPLICIT_ASSIGNEE_FOR_PERSONAL_TOOLS=true"
    - /Users/jinan/ai/atlassian-mcp-server/src/main/kotlin/com/atlassian/mcpserver/model/JiraModels.kt:136:// ===== Simplified Info for MCP Tool Responses =====
  - SSE 언급/연동 증거
    - /Users/jinan/ai/atlassian-mcp-server/build.gradle.kts:29:    // Spring AI MCP Server (SSE via Servlet/WebMVC)
    - /Users/jinan/ai/atlassian-mcp-server/README.md:381:Server starts on port **8085** with SSE transport.
  - tenant 언급/연동 증거
    - /Users/jinan/ai/atlassian-mcp-server/README.md:192:｜ `ATLASSIAN_BASE_URL` ｜ Yes ｜ Your Atlassian site URL (browser URL), e.g. `https://<site>.atlassian.net` ｜ `curl -s "$ATLASSIAN_BASE_URL/_edge/tenant_info"` ｜
    - /Users/jinan/ai/atlassian-mcp-server/README.md:196:｜ `ATLASSIAN_CLOUD_ID` ｜ Required when gateway mode is enabled ｜ `curl -s "$ATLASSIAN_BASE_URL/_edge/tenant_info" \｜ jq -r '.cloudId'` ｜ Must be non-empty UUID ｜

#### clipping-mcp-server
- 저장소: /Users/jinan/ai/clipping-mcp-server
  - MCP 언급/연동 증거
    - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/tool/ToolResponse.kt:16: * MCP 툴 실행 시 서비스 계층 예외를 일관된 JSON 에러 응답으로 변환합니다.
    - /Users/jinan/ai/clipping-mcp-server/build.gradle.kts:38:    // Spring AI MCP Server (SSE via WebFlux)
  - JWT 언급/연동 증거
    - /Users/jinan/ai/clipping-mcp-server/docs/superpowers/specs/2026-03-19-e2e-test-suite-design.md:622:｜ cross-flow #10 (탈퇴→로그인 불가) ｜ JWT 세션이면 즉시 무효화 안 될 수 있음 ｜ 백엔드 세션 방식 확인 후 조정 ｜
    - /Users/jinan/ai/clipping-mcp-server/docs/CLIPPING_GAP_ANALYSIS.md:46:- Add API authn/authz for `/api/admin/**` (JWT or service token + role check)
  - SSE 언급/연동 증거
    - /Users/jinan/ai/clipping-mcp-server/build.gradle.kts:38:    // Spring AI MCP Server (SSE via WebFlux)
    - /Users/jinan/ai/clipping-mcp-server/src/main/kotlin/com/clipping/mcpserver/admin/AdminUserRequestController.kt:137:                    is com.clipping.mcpserver.error.InvalidInputException -> "ALREADY_PROCESSED"
  - tenant 언급/연동 증거
    - /Users/jinan/ai/clipping-mcp-server/docs/CLIPPING_GAP_ANALYSIS.md:34:4. Single-tenant policy clarity gap (Low)  
    - /Users/jinan/ai/clipping-mcp-server/docs/CLIPPING_GAP_ANALYSIS.md:35:   - This repository is currently operated for a single company tenant.  

#### arc-reactor-admin
- 저장소: /Users/jinan/ai/arc-reactor-admin
  - swagger-mcp-server 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/README.md:11:로컬 operator stack(`arc-reactor` + `swagger-mcp-server` + `atlassian-mcp-server` + admin dev server)이 떠 있는 상태라면:
  - atlassian-mcp-server 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/README.md:11:로컬 operator stack(`arc-reactor` + `swagger-mcp-server` + `atlassian-mcp-server` + admin dev server)이 떠 있는 상태라면:
  - MCP 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/README.md:28:이 스크립트는 기본적으로 `http://127.0.0.1:18081`에 로그인해서 actuator, capabilities, MCP registry/preflight/policy,
    - /Users/jinan/ai/arc-reactor-admin/README.md:29:MCP security, tool policy, scheduler, approvals, audit, output guard 계약을 확인합니다.
  - JWT 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/docs/superpowers/specs/2026-03-21-ux-improvements-design.md:310:- JWT `exp` parsing utility in `shared/lib/jwt.ts`: manual base64url decode (`atob`) + `JSON.parse` to extract payload. No external library needed. Returns `exp` as Unix timestamp or `null` if token is not a valid JWT.
    - /Users/jinan/ai/arc-reactor-admin/docs/superpowers/specs/2026-03-21-ux-improvements-design.md:338:- **Session expiry**: Unit test for JWT parsing utility, integration test for timer behavior
  - SSE 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/pnpm-lock.yaml:1997:    resolution: {integrity: sha512-1to4zXBxmXHV3IiSSEInrreIlu02vUOvrhxJJH5vcxYTBDAx51cqZiKdyTxlecdKNSjj8EcxGBxNf6Vg+945gw==}
    - /Users/jinan/ai/arc-reactor-admin/docs/admin-qa-checklist.md:32:2. Confirm transport help text explains `SSE`, `STDIO`, `STREAMABLE_HTTP`.
  - tenant 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-admin/docs/admin-operator-roadmap.md:18:- Platform-level and tenant-level admin surfaces
    - /Users/jinan/ai/arc-reactor-admin/docs/admin-operator-roadmap.md:100:- Multi-tenant bulk administration and export workflows

#### arc-reactor-web
- 저장소: /Users/jinan/ai/arc-reactor-web
  - arc-reactor-admin 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-web/docs/prd-admin-features.md:577:Admin 기능을 독립적인 Vite 앱(`arc-reactor-admin`)으로 분리한다.
  - MCP 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-web/README.ko.md:34:- 어드민 대시보드 — MCP 서버, 페르소나, 인텐트, 아웃풋 가드, 툴 정책, 스케줄러, 클리핑
    - /Users/jinan/ai/arc-reactor-web/README.ko.md:44:｜ `/admin/mcp-servers` ｜ MCP 서버 — 등록 및 연결 관리 ｜
  - JWT 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-web/CLAUDE.md:161:JWT stored in `localStorage` as `arc-reactor-auth-token`. The `api` ky instance injects it via `beforeRequest` hook and clears it on 401. Roles: `USER`, `ADMIN`.
    - /Users/jinan/ai/arc-reactor-web/README.ko.md:31:- 선택적 JWT 인증 및 사용자 격리
  - SSE 언급/연동 증거
    - /Users/jinan/ai/arc-reactor-web/CLAUDE.md:107:Use the `api` instance from `src/lib/http.ts` in service files. Do not use `fetch` directly (except `streamChat` which uses SSE).
    - /Users/jinan/ai/arc-reactor-web/README.ko.md:26:- SSE(Server-Sent Events) 기반 실시간 스트리밍 응답

#### Aslan-Verse-Web
- 저장소: /Users/jinan/ai/Aslan-Verse-Web
  - JWT 언급/연동 증거
    - /Users/jinan/ai/Aslan-Verse-Web/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md:367:- JWT Bearer token 방식인지 확인 필요
  - SSE 언급/연동 증거
    - /Users/jinan/ai/Aslan-Verse-Web/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md:31:｜ 실시간 스트리밍 ｜ Native `EventSource` (SSE) ｜ - ｜
    - /Users/jinan/ai/Aslan-Verse-Web/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md:38:**SSE vs axios 결정:** ky를 일반 REST 호출에 사용하고, SSE는 native `EventSource`로 분리 처리. axios 미사용.

<!-- AUTO-ANALYSIS-BLOCK-END -->

## 1) 한눈에 보는 개요

Arc Reactor는 Spring AI 기반 AI Agent 런타임의 핵심을 제공하는 엔터프라이즈 플랫폼입니다.  
`arc-core`를 중심으로 `ReAct` 실행, 보안 `Guard`, 확장성 `Hook`, 동적 `MCP` 연결, `스케줄러`를 한 곳에서 운영할 수 있으며, 웹/Slack/관리 UI까지 동일 정책으로 통합됩니다.

- 핵심 런타임: `arc-reactor` (`arc-core`, `arc-admin`, 실행 조립 `arc-app`)
- 채널: `arc-reactor-web`(웹 UI), `arc-slack`(Slack Events/SocketMode), REST API
- 운영 플랫폼: `arc-reactor-admin`(대시보드/운영 승인/감사)
- 권한/인증: `aslan-iam`(외부 인증 발급, 공개키 기반 토큰 검증)
- 도구(도메인 MCP): `clipping-mcp-server`, `swagger-mcp-server`, `atlassian-mcp-server`
- 실험 인프라: `Aslan-Verse-Web`(멀티 리액터 조직·페르소나 실험)

이 설계는 “한 팀의 채팅봇”이 아니라 “회사 단위 AI 오케스트레이션 플랫폼”으로 보는 게 정확합니다.

## 2) 플랫폼 맵 (전체 시스템 연결)

### 2.1 요청 흐름
1. 사용자/시스템 요청 수신: `arc-reactor-web` 또는 `arc-slack` 또는 직접 API
2. `Guard` 단계: 입력 검증, 길이 제한, 보안 룰, 레이트리밋/인증 검사
3. `Hook(BeforeStart)`: 운영 메타 주입(테넌트/세션/추적값)
4. `ReAct Loop`: LLM ↔ Tool 호출 반복
5. `Hook(AfterToolCall/AfterComplete/AfterResponse)` 처리
6. 응답 반환 및 감사 로그 저장

### 2.2 인증/권한 경로
- `aslan-iam`에서 JWT 공개키를 받아 토큰의 서명 검증을 수행하고, `arc-reactor` 및 연동 MCP가 네트워크 검증을 최소화합니다.
- 토큰이 없는 요청은 `anonymous` 정책 폴백 처리.
- 운영 정책은 채널별(웹/슬랙/관리), 테넌트별, 도메인별로 구분합니다.

### 2.3 MCP 연동 구조
- `arc-reactor`의 REST API를 통해 MCP 서버를 동적으로 등록·수정·삭제
- 서버 타입/프로토콜 기반 동적 라우팅(REST/SSE/STDIO 패턴)
- 프라이빗 주소 접근/토큰/HMAC 등 네트워크 경계 정책 적용

## 3) 핵심 런타임 기능 (`arc-core`)

### 3.1 ReAct 엔진
- 채팅 기반 추론/행동 루프
- `maxToolCalls`, `maxToolsPerRequest`, tool 타임아웃, loop timeout으로 무한 루프 방지
- Assist/Tool 응답 쌍의 무결성 요구(도구 호출 추적 정합성)

### 3.2 Guard (Fail-Close)
- 기본 5단계 Guard 집합: 입력 정규화, 제로폭/이상문자 검사, 경계값 검사, 보안/인젝션 규칙, 승인 정책
- 승인/차단 실패 시 즉시 중단
- 정책 위반을 명시적 에러 응답으로 반환

### 3.3 Hook (Fail-Open)
- `BeforeStart`, `AfterToolCall`, `BeforeResponse`, `AfterComplete`
- Hook 실패는 요청 중단 원인으로 전파하지 않고 로깅/알림 중심으로 격리
- 운영 모니터링과 비용 회수 지표 수집에 유리

### 3.4 Safety, Policy, Output Guard
- Tool 출력 정제(`ToolOutputSanitizer`), PII 마스킹, 출력 가드 규칙
- 권한이 과한 텍스트/민감 데이터 유출 패턴을 규칙 기반으로 차단

### 3.5 HITL(Human in the loop)
- 위험한 Tool은 승인 큐로 라우팅 가능
- 승인자/타임아웃/재요청 정책 지정
- 관리자 승인/감사 체계와 결합 시 규정 준수성 강화

### 3.6 Scheduler
- Spring cron 기반 주기적 작업 등록
- MCP 작업, 내부 에이전트 작업, 메시지 알림/보고 자동화 지원
- 재시도 및 실패 핸들링 옵션 제공

### 3.7 멀티 에이전트 실험성
- 테스트용 Supervisor/Prompt Variant/평가 루프 기반 실험이 가능
- 채팅템플릿, 페르소나, 도구 접근권한을 버전별로 운영

## 4) 채널별 기능

### 4.1 웹 채널 (`arc-reactor-web`)
- 세션 대화, 스트리밍 채팅, 첨부 업로드, 세션 내보내기 지원
- API 통합을 통한 기능 토글: persona, promptTemplate, outputGuard, mcpServers, scheduler, rate policy
- 다국어 UI 및 반응형 레이아웃

### 4.2 Slack 채널 (`arc-slack`)
- Socket Mode와 Events API 모두 대응
- 이벤트/멘션/DM, 슬래시 명령 처리
- 쓰레드 기반 사용자 세션 매핑
- Slack 내 중복 처리 억제 및 서명 검증

### 4.3 관리자 채널 (`arc-reactor-admin`)
- 운영 지표: MCP 상태, 요청/도구 실패율, 로그 연동 상태
- 정책 운영: 허용 도구, 금지 규칙, outputGuard 및 승인 정책
- 관리자 승인 흐름 및 감사 추적

## 5) MCP 서버 상세

### 5.1 aslan-iam
- 조직 사용자 발급/관리, JWT 공개키 기반 검증
- arc-reactor 계열 서비스에서 사용자 및 역할 판단의 단일 권한 소스 역할

### 5.2 swagger-mcp-server
- OpenAPI 명세 탐색/요약/검증
- API 카탈로그 동기화 및 spec 운영 업무 자동화

### 5.3 atlassian-mcp-server
- Jira, Confluence, Bitbucket 도구군 중심으로 작업, 문서, 코드 이력까지 연결
- 프로젝트/공간 단위 권한 분리

### 5.4 clipping-mcp-server
- RSS/뉴스/자료 수집, 요약 생성, 다이제스트 정기 발송
- 팀별/페르소나별 정보 축적용으로 실무 브리핑 자동화

## 6) 실험형 확장: Aslan-Verse-Web

요청하신 실험 시나리오(“여러 팀의 가상 페르소나 조직” 구성) 관점에서 핵심입니다.

- 팀 단위 조직 템플릿(Workspace/Persona/Task/Network)을 구성해 ReAct 실행 정책을 분리 운영
- 같은 회사/팀 계열도 페르소나를 달리해 다른 의사결정 패턴을 병렬 테스트
- Arc Reactor의 Persona/Prompt 정책과 결합해 시뮬레이션 결과를 Slack/Web/Admin 채널로 동기화 가능

## 7) 운영 관점 체크리스트

- 비밀키가 하드코딩되어 있지 않은지 점검 (`jwt-secret` 등)
- Guard/Hook 동작 모드와 승인 규칙을 단계적으로 강화
- MCP private network 제한 정책 적용
- 스케줄러 빈도·실행시간·비용(토큰) 한도 정책
- 감사 로그 보존 기간과 검색성 설정
- 배포 전 hardening 테스트(적대 입력/정상 입력 동시)

## 8) 도입 우선순위(권장)

1. 아키텍처 고정: `arc-reactor` + `aslan-iam` 연동  
2. 운영 채널 연결: `arc-reactor-web`, `arc-reactor-admin`, `arc-slack`  
3. 도구 확장: `swagger`, `atlassian`, `clipping` MCP 등록  
4. 정책 수립: outputGuard, HITL, rate-limit, hook/guard 정책  
5. 확장 실험: `Aslan-Verse-Web` 페르소나 조직으로 멀티 리액터 운영 검증

## 9) 문서화/공유 포맷

- 이 문서(MD): 기능 카탈로그 기준
- 실행 결과 PDF: 제품 소개 및 전사 공유용
- 다음 문서: CTO/임원용 활용 전략 문서(사용 시나리오 + 기대효과 + 와우 포인트)

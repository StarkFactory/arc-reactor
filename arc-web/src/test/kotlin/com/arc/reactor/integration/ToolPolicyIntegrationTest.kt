package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.hook.impl.WriteToolBlockHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.policy.tool.JdbcToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicyProvider
import com.arc.reactor.policy.tool.ToolPolicyStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [ArcReactorApplication::class, IntegrationTestAgentExecutorConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:toolpolicydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql,classpath:test-tool-policy-schema.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.approval.enabled=true",
        "arc.reactor.tool-policy.enabled=true",
        "arc.reactor.tool-policy.dynamic.enabled=true",
        "arc.reactor.tool-policy.dynamic.refresh-ms=600000",
        "arc.reactor.auth.jwt-secret=integration-test-jwt-secret-32-bytes",
        "arc.reactor.postgres.required=false"
    ]
)
@AutoConfigureWebTestClient
@Tag("integration")
/**
 * 도구 정책 통합 테스트.
 *
 * 도구 정책 파이프라인의 E2E 동작을 검증합니다.
 */
class ToolPolicyIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var toolPolicyStore: ToolPolicyStore

    @Autowired
    lateinit var toolPolicyProvider: ToolPolicyProvider

    @Autowired
    lateinit var toolApprovalPolicy: ToolApprovalPolicy

    @Autowired
    lateinit var writeToolBlockHook: WriteToolBlockHook

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    lateinit var adminClient: WebTestClient

    @BeforeEach
    fun setUpSchema() {
        jdbcTemplate.update("DELETE FROM tool_policy")
        jdbcTemplate.update("DELETE FROM users")
        jdbcTemplate.update(
            "INSERT INTO users (id, email, name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            "integration-admin",
            "integration-admin@arc.dev",
            "Integration Admin",
            "ignored",
            "ADMIN"
        )
        toolPolicyProvider.invalidate()
        val token = jwtTokenProvider.createToken(adminUser())
        adminClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
    }

    @Test
    fun `wires JdbcToolPolicyStore when dynamic은(는) enabled이다`() {
        assertInstanceOf(JdbcToolPolicyStore::class.java, toolPolicyStore) {
            "Expected JdbcToolPolicyStore when dynamic policy is enabled"
        }
    }

    @Test
    fun `PUT 후 GET이 유효 정책을 저장하고 영향을 준다`() {
        // stored policy 업데이트
        adminClient.put()
            .uri("/api/tool-policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "writeToolNames" to listOf("jira_create_issue"),
                    "denyWriteChannels" to listOf("slack"),
                    "denyWriteMessage" to "blocked on slack"
                )
            )
            .exchange()
            .expectStatus().isOk

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tool_policy", Int::class.java))

        // GET state
        val state = adminClient.get()
            .uri("/api/tool-policy")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!

        @Suppress("UNCHECKED_CAST")
        val effective = state["effective"] as Map<String, Any>
        assertEquals(true, effective["enabled"])
        assertEquals("blocked on slack", effective["denyWriteMessage"])
        assertNotNull(state["stored"], "GET response should include the stored policy")
    }

    @Test
    fun `policy은(는) affects approval and slack write-tool blocking`() {
        // Store policy
        adminClient.put()
            .uri("/api/tool-policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "writeToolNames" to listOf("jira_create_issue"),
                    "denyWriteChannels" to listOf("slack"),
                    "denyWriteMessage" to "blocked"
                )
            )
            .exchange()
            .expectStatus().isOk

        // Approval policy becomes dynamic: write tools require approval
        assertTrue(toolApprovalPolicy.requiresApproval("jira_create_issue", emptyMap()), "jira_create_issue should require approval as a write tool")
        assertTrue(!toolApprovalPolicy.requiresApproval("confluence_get_page", emptyMap()), "confluence_get_page should not require approval as a read tool")

        // Slack channel: blocked by hook
        val toolCtx = ToolCallContext(
            agentContext = HookContext(
                runId = "r1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "slack"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = runBlocking { writeToolBlockHook.beforeToolCall(toolCtx) }
        val reject = assertInstanceOf(HookResult.Reject::class.java, result) {
            "Slack write tool should be rejected by policy"
        }
        assertEquals("blocked", reject.reason)
    }

    @Test
    fun `allowlist은(는) permits a write tool even in deny channel`() {
        adminClient.put()
            .uri("/api/tool-policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "writeToolNames" to listOf("jira_create_issue"),
                    "denyWriteChannels" to listOf("slack"),
                    "allowWriteToolNamesInDenyChannels" to listOf("jira_create_issue"),
                    "allowWriteToolNamesByChannel" to mapOf<String, List<String>>(),
                    "denyWriteMessage" to "blocked"
                )
            )
            .exchange()
            .expectStatus().isOk

        val toolCtx = ToolCallContext(
            agentContext = HookContext(
                runId = "r1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "slack"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = runBlocking { writeToolBlockHook.beforeToolCall(toolCtx) }
        assertInstanceOf(HookResult.Continue::class.java, result) {
            "Allowlist should permit write tool on deny channel"
        }
    }

    @Test
    fun `channel-scoped allowlist은(는) only specific channels를 허용한다`() {
        adminClient.put()
            .uri("/api/tool-policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "writeToolNames" to listOf("jira_create_issue"),
                    "denyWriteChannels" to listOf("slack", "web"),
                    "allowWriteToolNamesByChannel" to mapOf("slack" to listOf("jira_create_issue")),
                    "denyWriteMessage" to "blocked"
                )
            )
            .exchange()
            .expectStatus().isOk

        val slackCtx = ToolCallContext(
            agentContext = HookContext(
                runId = "r1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "slack"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )
        val slackResult = runBlocking { writeToolBlockHook.beforeToolCall(slackCtx) }
        assertInstanceOf(HookResult.Continue::class.java, slackResult) {
            "Channel-scoped allowlist should permit tool on slack"
        }

        val webCtx = slackCtx.copy(agentContext = slackCtx.agentContext.copy(channel = "web"))
        val webResult = runBlocking { writeToolBlockHook.beforeToolCall(webCtx) }
        val webReject = assertInstanceOf(HookResult.Reject::class.java, webResult) {
            "Same tool should be rejected on non-allowlisted channel"
        }
        assertEquals("blocked", webReject.reason)
    }

    @Test
    fun `deletes stored policy and falls back to config defaults`() {
        adminClient.put()
            .uri("/api/tool-policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "writeToolNames" to listOf("jira_create_issue"),
                    "denyWriteChannels" to listOf("slack"),
                    "denyWriteMessage" to "blocked"
                )
            )
            .exchange()
            .expectStatus().isOk

        adminClient.delete()
            .uri("/api/tool-policy")
            .exchange()
            .expectStatus().isNoContent

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tool_policy", Int::class.java))

        val state = adminClient.get()
            .uri("/api/tool-policy")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!

        @Suppress("UNCHECKED_CAST")
        val effective = state["effective"] as Map<String, Any>
        assertEquals(null, state["stored"], "Stored policy should be cleared after DELETE")
        assertTrue((effective["writeToolNames"] as Collection<*>).contains("jira_create_issue"))
        assertTrue((effective["denyWriteChannels"] as Collection<*>).contains("slack"))

        val toolCtx = ToolCallContext(
            agentContext = HookContext(
                runId = "r1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "slack"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = runBlocking { writeToolBlockHook.beforeToolCall(toolCtx) }
        val reject = assertInstanceOf(HookResult.Reject::class.java, result) {
            "Without stored policy, hook should fall back to config defaults and block slack write tools"
        }
        assertEquals(effective["denyWriteMessage"], reject.reason)
    }

    private fun adminUser() = User(
        id = "integration-admin",
        email = "integration-admin@arc.dev",
        name = "Integration Admin",
        passwordHash = "ignored",
        role = UserRole.ADMIN
    )
}

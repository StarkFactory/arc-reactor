package com.arc.reactor.tool

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel

class SemanticToolSelectorTest {

    private lateinit var embeddingModel: EmbeddingModel
    private val tools = listOf(
        createTool("search_orders", "Search and retrieve customer orders by ID or date range"),
        createTool("process_refund", "Process refund for a customer order"),
        createTool("track_shipping", "Track shipping status and delivery estimates"),
        createTool("send_email", "Send email notifications to customers"),
        createTool("calculate_tax", "Calculate tax for order total"),
        createTool("generate_report", "Generate sales analytics report")
    )

    @BeforeEach
    fun setup() {
        embeddingModel = mockk()
    }

    @Nested
    inner class SelectionLogic {

        @Test
        fun `should return all tools when count below maxResults`() {
            val smallToolList = tools.take(3)
            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("any prompt", smallToolList)

            assertEquals(3, result.size) { "Should return all tools when count <= maxResults" }
        }

        @Test
        fun `should return empty for empty tool list`() {
            val selector = SemanticToolSelector(embeddingModel)

            val result = selector.select("any prompt", emptyList())

            assertTrue(result.isEmpty()) { "Should return empty for empty tool list" }
        }

        @Test
        fun `should select tools by semantic similarity`() {
            // Simulate embeddings where "refund" prompt is close to "process_refund" tool
            val refundToolEmbedding = floatArrayOf(0.9f, 0.1f, 0.0f)
            val searchToolEmbedding = floatArrayOf(0.1f, 0.9f, 0.0f)
            val shippingToolEmbedding = floatArrayOf(0.0f, 0.1f, 0.9f)
            val emailToolEmbedding = floatArrayOf(0.2f, 0.3f, 0.5f)
            val taxToolEmbedding = floatArrayOf(0.3f, 0.3f, 0.3f)
            val reportToolEmbedding = floatArrayOf(0.1f, 0.5f, 0.4f)
            val promptEmbedding = floatArrayOf(0.85f, 0.15f, 0.0f) // Close to refund

            // Mock batch embedding (for tool descriptions)
            every { embeddingModel.embed(any<List<String>>()) } returns listOf(
                searchToolEmbedding, refundToolEmbedding, shippingToolEmbedding,
                emailToolEmbedding, taxToolEmbedding, reportToolEmbedding
            )
            // Mock single embedding (for prompt)
            every { embeddingModel.embed(any<String>()) } returns promptEmbedding

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.5,
                maxResults = 3
            )

            val result = selector.select("I want to refund my order", tools)

            // process_refund should be the top match (highest cosine similarity with prompt)
            assertTrue(result.isNotEmpty()) { "Should return at least one tool" }
            assertEquals("process_refund", result[0].name) {
                "First tool should be process_refund (closest to 'refund' prompt), got: ${result.map { it.name }}"
            }
            assertTrue(result.size <= 3) { "Should not exceed maxResults=3, got: ${result.size}" }
        }

        @Test
        fun `should fall back to all tools when no tool meets threshold`() {
            // All tools have very low similarity
            val lowSimilarity = floatArrayOf(0.1f, 0.0f, 0.0f)
            val promptEmbedding = floatArrayOf(0.0f, 0.0f, 1.0f) // Orthogonal to all tools

            every { embeddingModel.embed(any<List<String>>()) } returns
                tools.map { lowSimilarity }
            every { embeddingModel.embed(any<String>()) } returns promptEmbedding

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.9, // Very high threshold
                maxResults = 3
            )

            val result = selector.select("completely unrelated topic", tools)

            assertEquals(tools.size, result.size) {
                "Should return all tools when none meets threshold"
            }
        }

        @Test
        fun `should fall back to all tools when embedding fails`() {
            every { embeddingModel.embed(any<List<String>>()) } throws RuntimeException("API error")

            val selector = SemanticToolSelector(embeddingModel, maxResults = 3)

            val result = selector.select("any prompt", tools)

            assertEquals(tools.size, result.size) {
                "Should return all tools on embedding failure"
            }
        }

        @Test
        fun `should force confluence knowledge tool for answer style prompts`() {
            val confluenceTools = listOf(
                createTool("confluence_search_by_text", "Low-level search"),
                createTool("confluence_answer_question", "Preferred Confluence knowledge tool"),
                createTool("confluence_get_page", "Get Confluence page metadata"),
                createTool("jira_search_issues", "Search Jira issues")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns listOf(
                floatArrayOf(0.9f, 0.1f), // confluence_search_by_text
                floatArrayOf(0.1f, 0.9f), // confluence_answer_question
                floatArrayOf(0.2f, 0.8f), // confluence_get_page
                floatArrayOf(0.0f, 1.0f)  // jira_search_issues
            )
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(1.0f, 0.0f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.3,
                maxResults = 3
            )

            val result = selector.select("DEV 위키 정책 페이지가 무엇을 설명하는지 알려줘", confluenceTools)

            assertEquals("confluence_answer_question", result.first().name) {
                "Confluence answer prompts should prioritize confluence_answer_question"
            }
            assertFalse(result.any { it.name == "confluence_search_by_text" }) {
                "Low-level Confluence search tools should be excluded for answer-style prompts"
            }
        }

        @Test
        fun `should narrow confluence answer prompts to preferred tools only`() {
            val confluenceTools = listOf(
                createTool("confluence_search_by_text", "Low-level search"),
                createTool("confluence_answer_question", "Preferred Confluence knowledge tool"),
                createTool("confluence_get_page_content", "Get page content"),
                createTool("confluence_get_page", "Get Confluence page metadata"),
                createTool("jira_list_projects", "List Jira projects")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(confluenceTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 5
            )

            val result = selector.select("What does the page titled 개발팀 Home describe in the DEV space?", confluenceTools)

            assertEquals(
                listOf("confluence_answer_question", "confluence_get_page_content", "confluence_get_page"),
                result.map { it.name }
            ) {
                "Confluence answer prompts should be narrowed to the preferred knowledge tools"
            }
        }

        @Test
        fun `should force work item context tool for issue context prompts`() {
            val workTools = listOf(
                createTool("jira_get_issue", "Get Jira issue metadata"),
                createTool("work_item_context", "Build a Jira issue context package with owner, related docs, PRs, and next actions"),
                createTool("confluence_search", "Search Confluence pages")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("PAY-123 이슈 전체 맥락 정리해줘. 관련 문서와 다음 액션까지 보여줘.", workTools)

            assertEquals(listOf("work_item_context"), result.map { it.name }) {
                "Issue context prompts should be narrowed to work_item_context"
            }
        }

        @Test
        fun `should force work service context tool for service summary prompts`() {
            val workTools = listOf(
                createTool("jira_search_issues", "Search Jira issues"),
                createTool("work_service_context", "Build a service-centric digest across Jira, Confluence, and Bitbucket"),
                createTool("bitbucket_list_prs", "List Bitbucket PRs")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("payments 서비스 현재 상황을 한 번에 요약해줘. 최근 Jira랑 관련 문서, 열린 PR도 포함해줘.", workTools)

            assertEquals(listOf("work_service_context"), result.map { it.name }) {
                "Service summary prompts should be narrowed to work_service_context"
            }
        }

        @Test
        fun `should force work service context tool for service baseline prompts`() {
            val workTools = listOf(
                createTool("jira_search_issues", "Search Jira issues"),
                createTool("work_service_context", "Build a service-centric digest across Jira, Confluence, and Bitbucket"),
                createTool("bitbucket_list_prs", "List Bitbucket PRs")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘.", workTools)

            assertEquals(listOf("work_service_context"), result.map { it.name }) {
                "Service baseline prompts should be narrowed to work_service_context"
            }
        }

        @Test
        fun `should force work owner lookup tool for ownership prompts`() {
            val workTools = listOf(
                createTool("jira_get_issue", "Get Jira issue metadata"),
                createTool("work_owner_lookup", "Resolve who owns a service, Jira issue, Jira project, or repository"),
                createTool("confluence_search", "Search Confluence pages")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("PAY-123 담당 서비스랑 owner, 담당 팀 찾아줘.", workTools)

            assertEquals(listOf("work_owner_lookup"), result.map { it.name }) {
                "Ownership prompts should be narrowed to work_owner_lookup"
            }
        }

        @Test
        fun `should force work morning briefing tool for briefing prompts`() {
            val workTools = listOf(
                createTool("jira_list_projects", "List Jira projects"),
                createTool("work_morning_briefing", "Generate a single morning briefing across Jira, Bitbucket, and Confluence"),
                createTool("bitbucket_list_repositories", "List Bitbucket repositories")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 5
            )

            val result = selector.select("Give me a morning briefing for Jira project DEV and Bitbucket repo dev.", workTools)

            assertEquals(listOf("work_morning_briefing"), result.map { it.name }) {
                "Morning briefing prompts should be narrowed to the work_morning_briefing tool"
            }
        }

        @Test
        fun `should prefer read only evidence tool for workspace mutation prompts`() {
            val workspaceTools = listOf(
                createTool("jira_assign_issue", "Assign a Jira issue"),
                createTool("work_owner_lookup", "Resolve who owns a Jira issue"),
                createTool("jira_get_issue", "Get Jira issue metadata")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(workspaceTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("Jira 이슈 DEV-51를 담당자에게 재할당해줘.", workspaceTools)

            assertEquals(listOf("work_owner_lookup"), result.map { it.name }) {
                "Workspace mutation prompts should use at most one read-only evidence tool"
            }
        }

        @Test
        fun `should force jira list projects tool for project inventory prompts`() {
            val jiraTools = listOf(
                createTool("jira_search_issues", "Search Jira issues"),
                createTool("jira_list_projects", "List Jira projects"),
                createTool("jira_search_by_text", "Search Jira by text")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(jiraTools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("내가 접근 가능한 Jira 프로젝트 목록을 보여줘.", jiraTools)

            assertEquals(listOf("jira_list_projects"), result.map { it.name }) {
                "Project inventory prompts should be narrowed to jira_list_projects"
            }
        }

        @Test
        fun `should force bitbucket repository tool for repository inventory prompts`() {
            val tools = listOf(
                createTool("bitbucket_list_prs", "List pull requests"),
                createTool("bitbucket_list_repositories", "List repositories"),
                createTool("bitbucket_list_branches", "List branches")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(tools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select("접근 가능한 Bitbucket 저장소 목록을 보여줘.", tools)

            assertEquals(listOf("bitbucket_list_repositories"), result.map { it.name }) {
                "Repository inventory prompts should be narrowed to bitbucket_list_repositories"
            }
        }

        @Test
        fun `should prioritize swagger load and detail tools for endpoint detail prompts`() {
            val tools = listOf(
                createTool("spec_search", "Search endpoints"),
                createTool("spec_load", "Load an OpenAPI spec"),
                createTool("spec_detail", "Describe an endpoint detail"),
                createTool("spec_summary", "Summarize a spec")
            )
            every { embeddingModel.embed(any<List<String>>()) } returns List(tools.size) { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(embeddingModel, maxResults = 10)

            val result = selector.select(
                "https://petstore3.swagger.io/api/v3/openapi.json 스펙을 로드한 뒤 GET /pet/findByStatus 상세를 설명해줘.",
                tools
            )

            assertEquals(listOf("spec_load", "spec_detail"), result.map { it.name }) {
                "Swagger endpoint detail prompts should prioritize spec_load then spec_detail"
            }
        }
    }

    @Nested
    inner class EmbeddingCache {

        @Test
        fun `should cache tool embeddings and reuse on same tool list`() {
            val toolEmbeddings = tools.map { floatArrayOf(0.5f, 0.5f) }
            every { embeddingModel.embed(any<List<String>>()) } returns toolEmbeddings
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 3
            )

            // First call: should embed tools
            selector.select("prompt 1", tools)
            // Second call with same tools: should NOT re-embed tools
            selector.select("prompt 2", tools)

            // Batch embed should be called only once (for tools), embed(String) called twice (for prompts)
            verify(exactly = 1) { embeddingModel.embed(any<List<String>>()) }
            verify(exactly = 2) { embeddingModel.embed(any<String>()) }
        }

        @Test
        fun `should refresh cache when tool list changes`() {
            val toolsA = tools  // 6 tools
            val toolsB = tools.drop(1)  // 5 tools (different list)
            val embeddingsA = toolsA.map { floatArrayOf(0.5f, 0.5f) }
            val embeddingsB = toolsB.map { floatArrayOf(0.5f, 0.5f) }

            every { embeddingModel.embed(any<List<String>>()) } returnsMany listOf(embeddingsA, embeddingsB)
            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(0.5f, 0.5f)

            val selector = SemanticToolSelector(
                embeddingModel = embeddingModel,
                similarityThreshold = 0.0,
                maxResults = 3  // Below both list sizes to trigger semantic selection
            )

            // First call with full tool list
            selector.select("prompt", toolsA)
            // Second call with different tool list
            selector.select("prompt", toolsB)

            // Batch embed called twice (different tool lists)
            verify(exactly = 2) { embeddingModel.embed(any<List<String>>()) }
        }
    }

    @Nested
    inner class CosineSimilarityTest {

        @Test
        fun `identical vectors should have similarity 1`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(1.0, similarity, 0.001) { "Identical vectors should have similarity 1.0" }
        }

        @Test
        fun `orthogonal vectors should have similarity 0`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(0.0f, 1.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(0.0, similarity, 0.001) { "Orthogonal vectors should have similarity 0.0" }
        }

        @Test
        fun `opposite vectors should have similarity -1`() {
            val a = floatArrayOf(1.0f, 0.0f, 0.0f)
            val b = floatArrayOf(-1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(-1.0, similarity, 0.001) { "Opposite vectors should have similarity -1.0" }
        }

        @Test
        fun `zero vector should have similarity 0`() {
            val a = floatArrayOf(0.0f, 0.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            val similarity = SemanticToolSelector.cosineSimilarity(a, b)

            assertEquals(0.0, similarity, 0.001) { "Zero vector should have similarity 0.0" }
        }

        @Test
        fun `should reject mismatched dimensions`() {
            val a = floatArrayOf(1.0f, 0.0f)
            val b = floatArrayOf(1.0f, 0.0f, 0.0f)

            assertThrows(IllegalArgumentException::class.java) {
                SemanticToolSelector.cosineSimilarity(a, b)
            }
        }
    }

    companion object {
        fun createTool(name: String, description: String): ToolCallback = object : ToolCallback {
            override val name = name
            override val description = description
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
    }
}

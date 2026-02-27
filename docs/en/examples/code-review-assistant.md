# Code Review Assistant

Build a code review bot that analyzes a file diff in parallel across three independent dimensions — security, style, and logic — and returns a structured JSON review.

## Scenario

A CI system or developer sends a raw diff to `POST /api/review`. Three specialist agents analyze the code concurrently. Results are merged and returned as a structured JSON object that can be posted back to GitHub or rendered in a UI.

## Tools

### GetFileDiffTool

Fetches the diff for a specific file in a pull request:

```kotlin
package com.arc.reactor.tool.review

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class GetFileDiffTool(
    private val githubClient: GitHubClient  // your GitHub API wrapper
) : ToolCallback {

    override val name = "get_file_diff"
    override val description = "Fetch the diff for a specific file in a GitHub pull request."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "owner": { "type": "string", "description": "Repository owner" },
                "repo":  { "type": "string", "description": "Repository name" },
                "prNumber": { "type": "integer", "description": "Pull request number" },
                "filePath": { "type": "string", "description": "Path to the file in the repo" }
              },
              "required": ["owner", "repo", "prNumber", "filePath"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val owner    = arguments["owner"] as? String  ?: return "Error: owner is required"
        val repo     = arguments["repo"]  as? String  ?: return "Error: repo is required"
        val prNumber = (arguments["prNumber"] as? Number)?.toInt() ?: return "Error: prNumber is required"
        val filePath = arguments["filePath"] as? String ?: return "Error: filePath is required"

        return try {
            githubClient.getFileDiff(owner, repo, prNumber, filePath)
        } catch (e: Exception) {
            "Error: Failed to fetch diff — ${e.message}"
        }
    }
}
```

### ListFilesTool

Lists all changed files in a pull request so the agent can decide which ones to review:

```kotlin
package com.arc.reactor.tool.review

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class ListFilesTool(
    private val githubClient: GitHubClient
) : ToolCallback {

    override val name = "list_files"
    override val description = "List all files changed in a GitHub pull request."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "owner":    { "type": "string",  "description": "Repository owner" },
                "repo":     { "type": "string",  "description": "Repository name" },
                "prNumber": { "type": "integer", "description": "Pull request number" }
              },
              "required": ["owner", "repo", "prNumber"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val owner    = arguments["owner"] as? String ?: return "Error: owner is required"
        val repo     = arguments["repo"]  as? String ?: return "Error: repo is required"
        val prNumber = (arguments["prNumber"] as? Number)?.toInt() ?: return "Error: prNumber is required"

        return try {
            val files = githubClient.listChangedFiles(owner, repo, prNumber)
            files.joinToString("\n") { "- ${it.filename} (${it.status}, +${it.additions}/-${it.deletions})" }
        } catch (e: Exception) {
            "Error: Failed to list files — ${e.message}"
        }
    }
}
```

### PostCommentTool

Posts a review comment back to the pull request:

```kotlin
package com.arc.reactor.tool.review

import com.arc.reactor.tool.ToolCallback
import org.springframework.stereotype.Component

@Component
class PostCommentTool(
    private val githubClient: GitHubClient
) : ToolCallback {

    override val name = "post_comment"
    override val description = "Post a review comment on a GitHub pull request."

    override val inputSchema: String
        get() = """
            {
              "type": "object",
              "properties": {
                "owner":    { "type": "string", "description": "Repository owner" },
                "repo":     { "type": "string", "description": "Repository name" },
                "prNumber": { "type": "integer", "description": "Pull request number" },
                "body":     { "type": "string", "description": "Comment body (Markdown supported)" }
              },
              "required": ["owner", "repo", "prNumber", "body"]
            }
        """.trimIndent()

    override suspend fun call(arguments: Map<String, Any?>): Any {
        val owner    = arguments["owner"]    as? String ?: return "Error: owner is required"
        val repo     = arguments["repo"]     as? String ?: return "Error: repo is required"
        val prNumber = (arguments["prNumber"] as? Number)?.toInt() ?: return "Error: prNumber is required"
        val body     = arguments["body"]     as? String ?: return "Error: body is required"

        return try {
            githubClient.postComment(owner, repo, prNumber, body)
            "Comment posted successfully."
        } catch (e: Exception) {
            "Error: Failed to post comment — ${e.message}"
        }
    }
}
```

## Parallel Multi-Agent Review Service

The three reviewers run concurrently. Each receives the same diff and focuses on a single dimension:

```kotlin
package com.arc.reactor.service

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentNode
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.agent.multi.ResultMerger
import com.arc.reactor.tool.review.GetFileDiffTool
import com.arc.reactor.tool.review.ListFilesTool
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

@Service
class CodeReviewService(
    private val chatClient: ChatClient,
    private val properties: AgentProperties,
    private val getFileDiffTool: GetFileDiffTool,
    private val listFilesTool: ListFilesTool
) {

    suspend fun review(
        owner: String,
        repo: String,
        prNumber: Int,
        userId: String? = null
    ): MultiAgentResult {
        val prContext = "PR: $owner/$repo#$prNumber"

        return MultiAgent.parallel(
            merger = ResultMerger.JOIN_WITH_NEWLINE,
            failFast = false   // collect all results even if one reviewer fails
        )
            .node("security") {
                systemPrompt = """
                    You are a security code reviewer. Analyze ONLY security issues.
                    Look for: SQL injection, XSS, hardcoded credentials, insecure deserialization,
                    path traversal, improper input validation, and authentication bypasses.
                    Use list_files then get_file_diff to examine the code.
                    Report findings as JSON: {"issues": [{"severity": "HIGH|MEDIUM|LOW", "file": "...", "line": "...", "description": "..."}]}
                """.trimIndent()
                tools = listOf(listFilesTool, getFileDiffTool)
                maxToolCalls = 15
            }
            .node("style") {
                systemPrompt = """
                    You are a code style reviewer. Analyze ONLY style and readability issues.
                    Look for: naming conventions, function length (>20 lines), method complexity,
                    missing documentation, inconsistent formatting, and dead code.
                    Use list_files then get_file_diff to examine the code.
                    Report findings as JSON: {"issues": [{"severity": "HIGH|MEDIUM|LOW", "file": "...", "line": "...", "description": "..."}]}
                """.trimIndent()
                tools = listOf(listFilesTool, getFileDiffTool)
                maxToolCalls = 15
            }
            .node("logic") {
                systemPrompt = """
                    You are a logic and correctness reviewer. Analyze ONLY logic errors and edge cases.
                    Look for: null pointer risks, off-by-one errors, race conditions, missing error handling,
                    incorrect boolean logic, and unhandled edge cases.
                    Use list_files then get_file_diff to examine the code.
                    Report findings as JSON: {"issues": [{"severity": "HIGH|MEDIUM|LOW", "file": "...", "line": "...", "description": "..."}]}
                """.trimIndent()
                tools = listOf(listFilesTool, getFileDiffTool)
                maxToolCalls = 15
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "",
                    userPrompt = "Review the changes in $prContext",
                    userId = userId,
                    maxToolCalls = 15
                ),
                agentFactory = { node -> createReviewAgent(node) }
            )
    }

    private fun createReviewAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}
```

The three agents run concurrently. Total wall-clock time is roughly the slowest single reviewer, not the sum of all three.

## Structured JSON Output

To get a machine-readable review instead of free text, use `ResponseFormat.JSON` and provide a schema:

```kotlin
val REVIEW_SCHEMA = """
{
  "type": "object",
  "properties": {
    "summary": { "type": "string" },
    "recommendation": { "type": "string", "enum": ["APPROVE", "REQUEST_CHANGES", "COMMENT"] },
    "issues": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "severity":    { "type": "string", "enum": ["HIGH", "MEDIUM", "LOW"] },
          "category":    { "type": "string", "enum": ["security", "style", "logic"] },
          "file":        { "type": "string" },
          "description": { "type": "string" }
        },
        "required": ["severity", "category", "description"]
      }
    }
  },
  "required": ["summary", "recommendation", "issues"]
}
""".trimIndent()

// Use in AgentCommand for structured output from a single agent:
AgentCommand(
    systemPrompt = "...",
    userPrompt = "...",
    responseFormat = ResponseFormat.JSON,
    responseSchema = REVIEW_SCHEMA
)
```

Arc Reactor validates the LLM output against the schema and will attempt one repair call if the JSON is invalid. Invalid output that cannot be repaired returns `AgentErrorCode.INVALID_RESPONSE`.

## max-tool-calls Configuration

Complex PR reviews may require many tool calls (list files, then fetch each diff). Adjust per-node:

```kotlin
.node("security") {
    systemPrompt = "..."
    maxToolCalls = 20   // Allows listing files + fetching up to ~9 diffs
    tools = listOf(listFilesTool, getFileDiffTool)
}
```

Or globally via `application.yml`:

```yaml
arc:
  reactor:
    max-tool-calls: 20
    max-tools-per-request: 10
```

## Controller

```kotlin
package com.arc.reactor.controller

import com.arc.reactor.service.CodeReviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class ReviewResponse(
    val success: Boolean,
    val securityReview: String?,
    val styleReview: String?,
    val logicReview: String?,
    val combined: String?,
    val errorMessage: String?
)

@RestController
@RequestMapping("/api/review")
@Tag(name = "Code Review", description = "AI-powered pull request review")
class CodeReviewController(
    private val reviewService: CodeReviewService
) {

    @PostMapping("/{owner}/{repo}/pulls/{prNumber}")
    @Operation(summary = "Run parallel AI code review on a pull request")
    suspend fun review(
        @PathVariable owner: String,
        @PathVariable repo: String,
        @PathVariable prNumber: Int,
        @RequestParam(required = false) userId: String?
    ): ReviewResponse {
        val result = reviewService.review(owner, repo, prNumber, userId)

        // nodeResults preserves per-agent output
        val byNode = result.nodeResults.associate { it.nodeName to it.result.content }

        return ReviewResponse(
            success = result.success,
            securityReview = byNode["security"],
            styleReview = byNode["style"],
            logicReview = byNode["logic"],
            combined = result.finalResult.content,
            errorMessage = if (!result.success) result.finalResult.errorMessage else null
        )
    }
}
```

## Testing

```bash
curl -X POST "http://localhost:8080/api/review/my-org/my-repo/pulls/42?userId=dev-01"
```

Sample response:

```json
{
  "success": true,
  "securityReview": "{\"issues\": [{\"severity\": \"HIGH\", \"category\": \"security\", \"file\": \"AuthService.kt\", \"description\": \"Hardcoded JWT secret detected\"}]}",
  "styleReview": "{\"issues\": []}",
  "logicReview": "{\"issues\": [{\"severity\": \"MEDIUM\", \"category\": \"logic\", \"file\": \"OrderService.kt\", \"description\": \"Missing null check on order.userId\"}]}",
  "combined": "...",
  "errorMessage": null
}
```

## Related

- [Multi-Agent Guide](../architecture/multi-agent.md) — Parallel pattern details
- [CodeReviewExample.kt](../../arc-core/src/main/kotlin/com/arc/reactor/agent/multi/example/CodeReviewExample.kt) — Framework built-in parallel example
- [Structured Output](../architecture/response-processing.md)

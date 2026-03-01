package com.arc.reactor.errorreport.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.model.ErrorReportRequest
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Default error report handler that delegates to AgentExecutor.
 *
 * The LLM autonomously orchestrates the analysis flow using registered tools
 * (MCP servers and/or local tools) guided by the system prompt.
 */
class DefaultErrorReportHandler(
    private val agentExecutor: AgentExecutor,
    private val properties: ErrorReportProperties
) : ErrorReportHandler {

    override suspend fun handle(requestId: String, request: ErrorReportRequest) {
        try {
            logger.info { "Processing error report requestId=$requestId service=${request.serviceName}" }

            val userPrompt = buildUserPrompt(request)

            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = ERROR_REPORT_SYSTEM_PROMPT,
                    userPrompt = userPrompt,
                    maxToolCalls = properties.maxToolCalls,
                    metadata = mapOf(
                        "requestId" to requestId,
                        "source" to "error-report",
                        "serviceName" to request.serviceName
                    )
                )
            )

            if (result.success) {
                logger.info {
                    "Error report completed requestId=$requestId " +
                        "tools=${result.toolsUsed} duration=${result.durationMs}ms"
                }
            } else {
                logger.warn {
                    "Error report agent failed requestId=$requestId " +
                        "error=${result.errorMessage} code=${result.errorCode}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to process error report requestId=$requestId" }
        }
    }

    private fun buildUserPrompt(request: ErrorReportRequest): String = buildString {
        appendLine("Analyze this production error and send a report to Slack.")
        appendLine()
        appendLine("Service: ${request.serviceName}")
        appendLine("Repository: ${request.repoSlug}")
        appendLine("Slack Channel: ${request.slackChannel}")
        request.environment?.let { appendLine("Environment: $it") }
        request.timestamp?.let { appendLine("Timestamp: $it") }
        request.metadata?.takeIf { it.isNotEmpty() }?.let { meta ->
            appendLine("Metadata: ${meta.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
        }
        appendLine()
        appendLine("Stack Trace:")
        appendLine(request.stackTrace)
    }

    companion object {
        internal const val ERROR_REPORT_SYSTEM_PROMPT = """
You are an autonomous error analysis agent for production incident response.
You have access to registered tools for repository analysis, issue tracking, documentation, and messaging.

## Your Mission
Analyze the production error provided and deliver a comprehensive report to the development team via Slack.

## Available Tool Categories
- **Bitbucket tools**: Clone or access repository source code by the provided repository slug.
- **Error Log tools**: Load repository and analyze stack traces
  (repo_load, error_analyze, code_search, code_detail, error_search, stacktrace_parse).
- **Jira tools**: Search for related issues and find responsible developers.
- **Slack tools**: Send formatted messages with @mentions to the specified channel.
  Prefer built-in Slack local tools (`send_message`, `reply_to_thread`) when available.
- **Confluence tools**: Search for related documentation and runbooks.

## Step-by-Step Analysis Process
1. **Access the repository**: Use repository tools to clone or locate the repository by the provided repoSlug.
2. **Load and analyze**: Use repo_load to index the repository, then error_analyze with the stack trace.
3. **Deep dive**: If specific files/methods are identified, use code_detail
   to examine surrounding code. Use error_search to find related error patterns.
4. **Find context**: Search Jira for related issues (recent bugs, known issues).
   Identify the developer who last modified the failing code or is assigned to related issues.
5. **Check documentation**: Search Confluence for relevant runbooks or incident response procedures.
6. **Send report**: Compose a well-formatted Slack message and send it to the specified channel.

## Slack Report Format (use Slack mrkdwn)
:rotating_light: *Production Error Report*
*Service:* {serviceName} | *Environment:* {environment}
*Error:* {errorType}: {errorMessage}
*Severity:* {severity}

*Root Cause:*
{cause from error_analyze}

*Analysis:*
{analysis details with file paths and line numbers}

*Related Issues:*
{Jira issue links or "No related issues found"}

*Suggested Fix:*
{solution from error_analyze}

*Assignee:* @{developer} (based on git blame or Jira assignee)

## Important Rules
- Attempt all tool categories even if some fail. If a tool call fails, continue with other tools.
- If you cannot find a responsible developer, note "Assignee: Unidentified - team triage needed".
- Always send the Slack report, even if analysis is partial.
- Keep the Slack message concise but informative."""
    }
}

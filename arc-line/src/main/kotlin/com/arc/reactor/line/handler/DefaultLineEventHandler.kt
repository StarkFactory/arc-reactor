package com.arc.reactor.line.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.line.model.LineEventCommand
import com.arc.reactor.line.service.LineMessagingService
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default LINE event handler that delegates to AgentExecutor.
 *
 * - Maps LINE source to arc-reactor sessions via sessionId
 * - Tries replyMessage first, falls back to pushMessage if token expired
 * - Guard pipeline is applied automatically via AgentExecutor.execute()
 */
class DefaultLineEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: LineMessagingService
) : LineEventHandler {

    override suspend fun handleMessage(command: LineEventCommand) {
        val text = command.text.trim()
        if (text.isBlank()) {
            logger.debug { "Empty message from user=${command.userId}, skipping" }
            return
        }

        val sessionId = resolveSessionId(command)
        val replyTarget = resolveReplyTarget(command)
        executeAndRespond(command, sessionId, replyTarget, text)
    }

    private fun resolveSessionId(command: LineEventCommand): String {
        return when (command.sourceType) {
            "group" -> "line-${command.groupId}"
            "room" -> "line-${command.roomId}"
            else -> "line-${command.userId}"
        }
    }

    private fun resolveReplyTarget(command: LineEventCommand): String {
        return when (command.sourceType) {
            "group" -> command.groupId ?: command.userId
            "room" -> command.roomId ?: command.userId
            else -> command.userId
        }
    }

    private suspend fun executeAndRespond(
        command: LineEventCommand,
        sessionId: String,
        replyTarget: String,
        userPrompt: String
    ) {
        try {
            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful AI assistant responding on LINE. Keep responses concise.",
                    userPrompt = userPrompt,
                    userId = command.userId,
                    metadata = mapOf(
                        "sessionId" to sessionId,
                        "source" to "line"
                    )
                )
            )

            val responseText = if (result.success) {
                result.content
                    ?: "I processed your request but have no response."
            } else {
                "\u26A0\uFE0F ${result.errorMessage ?: "An error occurred while processing your request."}"
            }

            sendResponse(command.replyToken, replyTarget, responseText)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to process LINE event for user=${command.userId}" }
            try {
                messagingService.pushMessage(
                    to = replyTarget,
                    text = "\u274C An internal error occurred. Please try again later."
                )
            } catch (sendError: Exception) {
                sendError.throwIfCancellation()
                logger.error(sendError) { "Failed to send error message to LINE" }
            }
        }
    }

    private suspend fun sendResponse(
        replyToken: String,
        replyTarget: String,
        text: String
    ) {
        val replied = messagingService.replyMessage(replyToken, text)
        if (!replied) {
            logger.debug { "Reply failed, falling back to push for target=$replyTarget" }
            messagingService.pushMessage(replyTarget, text)
        }
    }
}

package com.arc.reactor.slack.gateway

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.config.SlackSocketBackend
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.databind.ObjectMapper
import com.slack.api.Slack
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.response.AckResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.SmartLifecycle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

private val logger = KotlinLogging.logger {}

class SlackSocketModeGateway(
    private val properties: SlackProperties,
    private val objectMapper: ObjectMapper,
    private val commandProcessor: SlackCommandProcessor,
    private val eventProcessor: SlackEventProcessor,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder
) : SmartLifecycle {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var startRequested: Boolean = false

    private var startupJob: Job? = null
    private var slack: Slack? = null
    private var socketModeClient: SocketModeClient? = null

    override fun start() {
        if (running || startRequested) return
        if (properties.appToken.isBlank()) {
            throw IllegalStateException(
                "arc.reactor.slack.app-token is required when transport-mode=socket_mode"
            )
        }

        startRequested = true
        startupJob = scope.launch {
            connectWithRetry()
        }
    }

    override fun stop() {
        startRequested = false
        running = false
        startupJob?.cancel()
        startupJob = null

        runCatching { socketModeClient?.disconnect() }
            .onFailure { logger.warn(it) { "Failed to disconnect Slack Socket Mode client" } }
        runCatching { socketModeClient?.close() }
            .onFailure { logger.warn(it) { "Failed to close Slack Socket Mode client" } }
        runCatching { slack?.close() }
            .onFailure { logger.warn(it) { "Failed to close Slack client" } }

        socketModeClient = null
        slack = null
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Integer.MAX_VALUE

    private suspend fun connectWithRetry() {
        var delayMs = properties.socketConnectRetryInitialDelayMs.coerceAtLeast(200)
        val maxDelayMs = properties.socketConnectRetryMaxDelayMs.coerceAtLeast(delayMs)

        while (startRequested && !running) {
            try {
                connectOnce()
                running = true
                logger.info { "Slack Socket Mode gateway connected successfully" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "connect_failure"
                )
                logger.error(e) {
                    "Slack Socket Mode connection failed, retrying in ${delayMs}ms"
                }
                delay(delayMs)
                delayMs = min(delayMs * 2, maxDelayMs)
            }
        }
    }

    private fun connectOnce() {
        val slackInstance = Slack.getInstance()
        val backend = when (properties.socketBackend) {
            SlackSocketBackend.JAVA_WEBSOCKET -> SocketModeClient.Backend.JavaWebSocket
            SlackSocketBackend.TYRUS -> SocketModeClient.Backend.Tyrus
        }
        var client: SocketModeClient? = null
        try {
            client = slackInstance.socketMode(properties.appToken, backend)
            registerListeners(client)

            client.setAutoReconnectEnabled(true)
            client.setAutoReconnectOnCloseEnabled(true)
            client.setSessionMonitorEnabled(true)
            client.initializeMessageProcessorExecutor(properties.maxConcurrentRequests.coerceAtLeast(1))
            client.connect()

            slack = slackInstance
            socketModeClient = client
        } catch (e: Exception) {
            runCatching { client?.close() }
            runCatching { slackInstance.close() }
            throw e
        }
    }

    private fun registerListeners(client: SocketModeClient) {
        client.addEventsApiEnvelopeListener { envelope ->
            acknowledgeEnvelope(client, envelope.envelopeId)
            val payload = envelope.payload?.toString()
            if (payload.isNullOrBlank()) return@addEventsApiEnvelopeListener

            runCatching {
                eventProcessor.submitEventCallback(
                    payload = objectMapper.readTree(payload),
                    entrypoint = "socket_mode_events",
                    retryNum = envelope.retryAttempt?.toString(),
                    retryReason = envelope.retryReason
                )
            }.onFailure { e ->
                logger.error(e) { "Failed to process Socket Mode events_api envelope" }
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_events",
                    reason = "handler_exception"
                )
            }
        }

        client.addSlashCommandsEnvelopeListener { envelope ->
            acknowledgeEnvelope(client, envelope.envelopeId)
            val payload = envelope.payload?.toString()
            if (payload.isNullOrBlank()) return@addSlashCommandsEnvelopeListener

            runCatching {
                val command = parseSlashCommand(payload)
                if (command == null) {
                    metricsRecorder.recordDropped(
                        entrypoint = "socket_mode_slash_command",
                        reason = "invalid_payload"
                    )
                    notifyResponseUrlIfPresent(
                        payload = payload,
                        text = SlackCommandProcessor.INVALID_PAYLOAD_RESPONSE_TEXT
                    )
                    return@runCatching
                }

                val accepted = commandProcessor.submit(
                    command = command,
                    entrypoint = "socket_mode_slash_command"
                )
                if (!accepted) {
                    scope.launch {
                        messagingService.sendResponseUrl(
                            responseUrl = command.responseUrl,
                            text = SlackCommandProcessor.BUSY_RESPONSE_TEXT
                        )
                    }
                }
            }.onFailure { e ->
                logger.error(e) { "Failed to process Socket Mode slash command envelope" }
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "handler_exception"
                )
            }
        }

        client.addInteractiveEnvelopeListener { envelope ->
            acknowledgeEnvelope(client, envelope.envelopeId)
            val payload = envelope.payload?.toString()
            val interactionType = payload?.let { raw ->
                runCatching { objectMapper.readTree(raw).path("type").asText() }.getOrNull()
            } ?: "interactive"
            logger.info { "Socket Mode interactive payload ignored: type=$interactionType" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode_interactive",
                reason = "unsupported",
                eventType = interactionType
            )
        }

        client.addWebSocketErrorListener { error ->
            logger.error(error) { "Slack Socket Mode WebSocket error" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode",
                reason = "websocket_error"
            )
        }

        client.addWebSocketCloseListener { statusCode, reason ->
            logger.warn { "Slack Socket Mode WebSocket closed: status=$statusCode reason=$reason" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode",
                reason = "websocket_closed"
            )
        }
    }

    private fun parseSlashCommand(payload: String): SlackSlashCommand? {
        val json = objectMapper.readTree(payload)
        val command = json.path("command").asNullableText() ?: return null
        val userId = json.path("user_id").asNullableText() ?: return null
        val channelId = json.path("channel_id").asNullableText() ?: return null
        val responseUrl = json.path("response_url").asNullableText() ?: return null

        return SlackSlashCommand(
            command = command,
            text = json.path("text").asText(""),
            userId = userId,
            userName = json.path("user_name").asNullableText(),
            channelId = channelId,
            channelName = json.path("channel_name").asNullableText(),
            responseUrl = responseUrl,
            triggerId = json.path("trigger_id").asNullableText()
        )
    }

    private fun notifyResponseUrlIfPresent(payload: String, text: String) {
        val responseUrl = runCatching {
            objectMapper.readTree(payload).path("response_url").asNullableText()
        }.getOrNull()

        if (responseUrl.isNullOrBlank()) return
        scope.launch {
            messagingService.sendResponseUrl(responseUrl = responseUrl, text = text)
        }
    }

    private fun acknowledgeEnvelope(client: SocketModeClient, envelopeId: String?) {
        if (envelopeId.isNullOrBlank()) return
        try {
            client.sendSocketModeResponse(
                AckResponse.builder().envelopeId(envelopeId).build()
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to ack Socket Mode envelope: envelopeId=$envelopeId" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode",
                reason = "ack_failure"
            )
        }
    }

    private fun com.fasterxml.jackson.databind.JsonNode.asNullableText(): String? {
        val value = asText().trim()
        return value.ifBlank { null }
    }
}

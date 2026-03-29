package com.arc.reactor.slack.gateway

import com.arc.reactor.support.throwIfCancellation
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

private val logger = KotlinLogging.logger {}

/**
 * Slack Socket Mode 게이트웨이.
 *
 * WebSocket 기반 Socket Mode 연결을 관리하며, 이벤트(Events API), 슬래시 명령,
 * 인터랙티브 페이로드를 수신하여 각 프로세서에 위임한다.
 *
 * Spring [SmartLifecycle]을 구현하여 애플리케이션 시작/종료 시 자동으로
 * 연결/해제된다. 연결 실패 시 지수 백오프로 재시도한다.
 *
 * 흐름:
 * 1. 앱 시작 -> Socket Mode WebSocket 연결
 * 2. 이벤트 수신 -> 즉시 ACK -> [SlackEventProcessor] 또는 [SlackCommandProcessor]에 위임
 * 3. 앱 종료 -> WebSocket 연결 해제
 *
 * @param properties Slack 설정 (앱 토큰, 백엔드 선택 등)
 * @param objectMapper JSON 파싱용 ObjectMapper
 * @param commandProcessor 슬래시 명령 프로세서
 * @param eventProcessor 이벤트 프로세서
 * @param messagingService 메시지 전송 서비스
 * @param metricsRecorder 메트릭 기록기
 * @see SlackEventProcessor
 * @see SlackCommandProcessor
 */
class SlackSocketModeGateway(
    private val properties: SlackProperties,
    private val objectMapper: ObjectMapper,
    private val commandProcessor: SlackCommandProcessor,
    private val eventProcessor: SlackEventProcessor,
    private val messagingService: SlackMessagingService,
    private val metricsRecorder: SlackMetricsRecorder
) : SmartLifecycle {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val startGuard = AtomicBoolean(false)

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var startRequested: Boolean = false

    @Volatile
    private var startupJob: Job? = null
    @Volatile
    private var slack: Slack? = null

    @Volatile
    private var socketModeClient: SocketModeClient? = null

    override fun start() {
        if (!startGuard.compareAndSet(false, true)) return
        if (properties.appToken.isBlank()) {
            startGuard.set(false)
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
        startGuard.set(false)
        startRequested = false
        running = false
        startupJob?.cancel()
        startupJob = null

        runCatching { socketModeClient?.disconnect() }
            .onFailure { logger.warn(it) { "Socket Mode 클라이언트 연결 해제 실패" } }
        runCatching { socketModeClient?.close() }
            .onFailure { logger.warn(it) { "Socket Mode 클라이언트 종료 실패" } }
        runCatching { slack?.close() }
            .onFailure { logger.warn(it) { "Slack 클라이언트 종료 실패" } }

        socketModeClient = null
        slack = null
        scope.cancel()
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
                logger.info { "Socket Mode 게이트웨이 연결 성공" }
            } catch (e: Exception) {
                e.throwIfCancellation()
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "connect_failure"
                )
                logger.error(e) {
                    "Socket Mode 연결 실패, ${delayMs}ms 후 재시도"
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
        registerEventsApiListener(client)
        registerSlashCommandListener(client)
        registerInteractiveListener(client)
        registerErrorAndCloseListeners(client)
    }

    /** Events API 엔벌로프 리스너를 등록한다. */
    private fun registerEventsApiListener(client: SocketModeClient) {
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
                logger.error(e) { "Socket Mode events_api 엔벌로프 처리 실패" }
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_events",
                    reason = "handler_exception"
                )
            }
        }
    }

    /** 슬래시 명령 엔벌로프 리스너를 등록한다. */
    private fun registerSlashCommandListener(client: SocketModeClient) {
        client.addSlashCommandsEnvelopeListener { envelope ->
            acknowledgeEnvelope(client, envelope.envelopeId)
            val payload = envelope.payload?.toString()
            if (payload.isNullOrBlank()) return@addSlashCommandsEnvelopeListener

            runCatching {
                handleSlashCommandPayload(payload)
            }.onFailure { e ->
                logger.error(e) { "Socket Mode 슬래시 명령 엔벌로프 처리 실패" }
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "handler_exception"
                )
            }
        }
    }

    /** 파싱된 슬래시 명령을 프로세서에 제출하고, 거부 시 응답을 전송한다. */
    private fun handleSlashCommandPayload(payload: String) {
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
            return
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
    }

    /** 인터랙티브 페이로드(미지원) 리스너를 등록한다. */
    private fun registerInteractiveListener(client: SocketModeClient) {
        client.addInteractiveEnvelopeListener { envelope ->
            acknowledgeEnvelope(client, envelope.envelopeId)
            val payload = envelope.payload?.toString()
            val interactionType = payload?.let { raw ->
                runCatching { objectMapper.readTree(raw).path("type").asText() }.getOrNull()
            } ?: "interactive"
            logger.info { "Socket Mode 인터랙티브 페이로드 무시: type=$interactionType" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode_interactive",
                reason = "unsupported",
                eventType = interactionType
            )
        }
    }

    /** WebSocket 에러·종료 리스너를 등록한다. */
    private fun registerErrorAndCloseListeners(client: SocketModeClient) {
        client.addWebSocketErrorListener { error ->
            logger.error(error) { "Socket Mode WebSocket 에러 발생" }
            metricsRecorder.recordDropped(
                entrypoint = "socket_mode",
                reason = "websocket_error"
            )
        }

        client.addWebSocketCloseListener { statusCode, reason ->
            logger.warn { "Socket Mode WebSocket 연결 종료: status=$statusCode reason=$reason" }
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
            logger.error(e) { "Socket Mode 엔벌로프 ACK 실패: envelopeId=$envelopeId" }
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

package com.arc.reactor.slack.gateway

import com.arc.reactor.slack.config.SlackProperties
import com.arc.reactor.slack.config.SlackSocketBackend
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.processor.SlackCommandProcessor
import com.arc.reactor.slack.processor.SlackEventProcessor
import com.arc.reactor.slack.service.SlackMessagingService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.gson.JsonParser
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.listener.EnvelopeListener
import com.slack.api.socket_mode.listener.WebSocketCloseListener
import com.slack.api.socket_mode.listener.WebSocketErrorListener
import com.slack.api.socket_mode.request.EventsApiEnvelope
import com.slack.api.socket_mode.request.InteractiveEnvelope
import com.slack.api.socket_mode.request.SlashCommandsEnvelope
import com.slack.api.socket_mode.response.SocketModeResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SlackSocketModeGateway]에 대한 단위 테스트. 라이프사이클, 엔벨로프 디스패치, 오류 처리를 검증합니다.
 *
 * [SlackSocketModeGateway]는 final Kotlin 클래스이고 [connectOnce]는 private이므로
 * 두 가지 전략을 사용합니다:
 *  - 리플렉션으로 [registerListeners]를 모킹된 [SocketModeClient]와 함께 호출하여 리스너를 캡처합니다.
 *  - Public API / 리플렉션을 통한 필드 주입으로 라이프사이클과 재시도 동작을 테스트합니다.
 */
class SlackSocketModeGatewayTest {

    private val objectMapper = jacksonObjectMapper()
    private val eventProcessor = mockk<SlackEventProcessor>(relaxed = true)
    private val commandProcessor = mockk<SlackCommandProcessor>(relaxed = true)
    private val messagingService = mockk<SlackMessagingService>(relaxed = true)
    private val metricsRecorder = mockk<SlackMetricsRecorder>(relaxed = true)

    private val defaultProperties = SlackProperties(
        enabled = true,
        appToken = "xapp-1-test-app-token",
        botToken = "xoxb-test-bot-token",
        maxConcurrentRequests = 5,
        socketBackend = SlackSocketBackend.JAVA_WEBSOCKET,
        socketConnectRetryInitialDelayMs = 100,
        socketConnectRetryMaxDelayMs = 200
    )

    // -------------------------------------------------------------------------
    // 리플렉션 헬퍼
    // -------------------------------------------------------------------------

    private fun buildGateway(properties: SlackProperties = defaultProperties): SlackSocketModeGateway =
        SlackSocketModeGateway(
            properties, objectMapper, commandProcessor, eventProcessor, messagingService, metricsRecorder
        )

    /** 리플렉션을 통해 private [SlackSocketModeGateway.registerListeners]를 호출합니다. */
    private fun registerListenersOn(gateway: SlackSocketModeGateway, client: SocketModeClient) {
        val method: Method = SlackSocketModeGateway::class.java.getDeclaredMethod(
            "registerListeners", SocketModeClient::class.java
        )
        method.isAccessible = true
        method.invoke(gateway, client)
    }

    private fun setField(gateway: SlackSocketModeGateway, name: String, value: Any?) {
        val field: Field = SlackSocketModeGateway::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(gateway, value)
    }

    private fun setBoolean(gateway: SlackSocketModeGateway, name: String, value: Boolean) {
        val field: Field = SlackSocketModeGateway::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.setBoolean(gateway, value)
    }

    private fun assertTrue(condition: Boolean, message: () -> String) {
        if (!condition) throw AssertionError(message())
    }

    // -------------------------------------------------------------------------
    // 캡처용 모킹 클라이언트
    // -------------------------------------------------------------------------

    /**
     * 추가된 모든 리스너를 캡처하는 모킹된 [SocketModeClient].
     * 실제 WebSocket을 열지 않고 등록된 콜백을 직접 호출할 수 있습니다.
     */
    private fun buildCapturingMockClient(): CapturingMockClient {
        val eventsListeners = CopyOnWriteArrayList<EnvelopeListener<EventsApiEnvelope>>()
        val slashListeners = CopyOnWriteArrayList<EnvelopeListener<SlashCommandsEnvelope>>()
        val interactiveListeners = CopyOnWriteArrayList<EnvelopeListener<InteractiveEnvelope>>()
        val errorListeners = CopyOnWriteArrayList<WebSocketErrorListener>()
        val closeListeners = CopyOnWriteArrayList<WebSocketCloseListener>()

        val mock = mockk<SocketModeClient>(relaxed = true)

        every { mock.addEventsApiEnvelopeListener(any<EnvelopeListener<EventsApiEnvelope>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            eventsListeners.add(firstArg() as EnvelopeListener<EventsApiEnvelope>)
            Unit
        }
        every { mock.addSlashCommandsEnvelopeListener(any<EnvelopeListener<SlashCommandsEnvelope>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            slashListeners.add(firstArg() as EnvelopeListener<SlashCommandsEnvelope>)
            Unit
        }
        every { mock.addInteractiveEnvelopeListener(any<EnvelopeListener<InteractiveEnvelope>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            interactiveListeners.add(firstArg() as EnvelopeListener<InteractiveEnvelope>)
            Unit
        }
        every { mock.addWebSocketErrorListener(any<WebSocketErrorListener>()) } answers {
            errorListeners.add(firstArg() as WebSocketErrorListener)
            Unit
        }
        every { mock.addWebSocketCloseListener(any<WebSocketCloseListener>()) } answers {
            closeListeners.add(firstArg() as WebSocketCloseListener)
            Unit
        }

        return CapturingMockClient(mock, eventsListeners, slashListeners, interactiveListeners, errorListeners, closeListeners)
    }

    data class CapturingMockClient(
        val mock: SocketModeClient,
        val eventsListeners: List<EnvelopeListener<EventsApiEnvelope>>,
        val slashListeners: List<EnvelopeListener<SlashCommandsEnvelope>>,
        val interactiveListeners: List<EnvelopeListener<InteractiveEnvelope>>,
        val errorListeners: List<WebSocketErrorListener>,
        val closeListeners: List<WebSocketCloseListener>
    )

    // -------------------------------------------------------------------------
    // Envelope builders
    // -------------------------------------------------------------------------

    private fun eventsEnvelope(
        envelopeId: String = "env-001",
        payloadJson: String? = null,
        retryAttempt: Int? = null,
        retryReason: String? = null
    ): EventsApiEnvelope = EventsApiEnvelope().also { e ->
        e.envelopeId = envelopeId
        payloadJson?.let { e.payload = JsonParser.parseString(it) }
        e.retryAttempt = retryAttempt
        e.retryReason = retryReason
    }

    private fun slashEnvelope(
        envelopeId: String = "env-002",
        payloadJson: String? = null
    ): SlashCommandsEnvelope = SlashCommandsEnvelope().also { e ->
        e.envelopeId = envelopeId
        payloadJson?.let { e.payload = JsonParser.parseString(it) }
    }

    private fun interactiveEnvelope(
        envelopeId: String = "env-003",
        payloadJson: String? = null
    ): InteractiveEnvelope = InteractiveEnvelope().also { e ->
        e.envelopeId = envelopeId
        payloadJson?.let { e.payload = JsonParser.parseString(it) }
    }

    private val validSlashPayloadJson = """
        {
            "command": "/test",
            "text": "hello world",
            "user_id": "U123",
            "user_name": "alice",
            "channel_id": "C456",
            "channel_name": "general",
            "response_url": "https://hooks.slack.com/commands/test",
            "trigger_id": "trig-001"
        }
    """.trimIndent()

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    inner class LifecycleManagement {

        @Test
        fun `isAutoStartup은(는) returns true`() {
            val gateway = buildGateway()
            assertTrue(gateway.isAutoStartup()) {
                "isAutoStartup should return true so Spring starts the gateway automatically"
            }
        }

        @Test
        fun `phase은(는) Integer MAX_VALUE이다`() {
            val gateway = buildGateway()
            assertTrue(gateway.phase == Integer.MAX_VALUE) {
                "Expected phase=${Integer.MAX_VALUE} but was ${gateway.phase}"
            }
        }

        @Test
        fun `start전에 isRunning returns false`() {
            val gateway = buildGateway()
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running before start() is called"
            }
        }

        @Test
        fun `start throws IllegalStateException when appToken은(는) blank이다`() {
            val gateway = buildGateway(defaultProperties.copy(appToken = ""))
            assertThrows<IllegalStateException>("Expected IllegalStateException when appToken is blank") {
                gateway.start()
            }
        }

        @Test
        fun `gateway is already marked running일 때 start은(는) a no-op이다`() = runTest {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)

            gateway.start()  // guard: running=true, so은(는) return immediately해야 합니다

            // Still running (the flag was set by us; stop was never called)
            assertTrue(gateway.isRunning) {
                "Gateway should still report running after no-op start() when already running"
            }
        }

        @Test
        fun `startRequested is already true일 때 start은(는) a no-op이다`() = runTest {
            val gateway = buildGateway()
            setBoolean(gateway, "startRequested", true)

            gateway.start()  // guard: startRequested=true → returns immediately, no second job

            // startRequested stays true, isRunning unchanged
            assertTrue(!gateway.isRunning) {
                "isRunning should remain false (no successful connection), but should not throw"
            }
        }

        @Test
        fun `resets running and startRequested flags를 중지한다`() {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)
            setBoolean(gateway, "startRequested", true)

            gateway.stop()

            assertTrue(!gateway.isRunning) { "Gateway should not be running after stop()" }
        }

        @Test
        fun `with Runnable callback invokes callback를 중지한다`() {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)

            var callbackFired = false
            gateway.stop { callbackFired = true }

            assertTrue(callbackFired) { "Runnable callback must be invoked by stop(Runnable)" }
            assertTrue(!gateway.isRunning) { "Gateway should not be running after stop(callback)" }
        }

        @Test
        fun `stop은(는) safe on a never-started gateway이다`() {
            val gateway = buildGateway()

            gateway.stop()  // not throw when no client exists해야 합니다

            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() on a never-started instance"
            }
        }

        @Test
        fun `handles disconnect and close exceptions without propagating를 중지한다`() {
            val gateway = buildGateway()
            val capturing = buildCapturingMockClient()

            every { capturing.mock.disconnect() } throws RuntimeException("disconnect error")
            every { capturing.mock.close() } throws RuntimeException("close error")
            setField(gateway, "socketModeClient", capturing.mock)
            setBoolean(gateway, "running", true)

            gateway.stop()  // 예외를 던지면 안 됩니다

            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() even when disconnect/close throw"
            }
        }

        @Test
        fun `called multiple times일 때 stop은(는) idempotent이다`() {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)

            gateway.stop()
            gateway.stop()

            assertTrue(!gateway.isRunning) {
                "Gateway should remain stopped after multiple stop() calls"
            }
        }
    }

    // =========================================================================
    // Listener registration
    // =========================================================================

    @Nested
    inner class ListenerRegistration {

        @Test
        fun `registerListeners은(는) registers exactly one listener of each type`() {
            val gateway = buildGateway()
            val capturing = buildCapturingMockClient()

            registerListenersOn(gateway, capturing.mock)

            assertTrue(capturing.eventsListeners.size == 1) {
                "Expected exactly 1 events_api listener, got ${capturing.eventsListeners.size}"
            }
            assertTrue(capturing.slashListeners.size == 1) {
                "Expected exactly 1 slash_commands listener, got ${capturing.slashListeners.size}"
            }
            assertTrue(capturing.interactiveListeners.size == 1) {
                "Expected exactly 1 interactive listener, got ${capturing.interactiveListeners.size}"
            }
            assertTrue(capturing.errorListeners.size == 1) {
                "Expected exactly 1 WebSocket error listener, got ${capturing.errorListeners.size}"
            }
            assertTrue(capturing.closeListeners.size == 1) {
                "Expected exactly 1 WebSocket close listener, got ${capturing.closeListeners.size}"
            }
        }
    }

    // =========================================================================
    // events_api envelope handling
    // =========================================================================

    @Nested
    inner class EventsApiEnvelopeHandling {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `events_api envelope은(는) ACK and submits event callback를 트리거한다`() {
            val payloadJson = """{"type":"event_callback","event_id":"Ev001","event":{"type":"app_mention","user":"U1","channel":"C1","text":"hi","ts":"1.2"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-e1", payloadJson = payloadJson)

            capturing.eventsListeners.first().handle(envelope)

            verify { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
            verify {
                eventProcessor.submitEventCallback(
                    payload = any(),
                    entrypoint = "socket_mode_events",
                    retryNum = null,
                    retryReason = null
                )
            }
        }

        @Test
        fun `events_api retry metadata은(는) forwarded to submitEventCallback이다`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"x","ts":"1"}}"""
            val envelope = eventsEnvelope(
                envelopeId = "env-retry",
                payloadJson = payloadJson,
                retryAttempt = 2,
                retryReason = "http_timeout"
            )

            capturing.eventsListeners.first().handle(envelope)

            verify {
                eventProcessor.submitEventCallback(
                    payload = any(),
                    entrypoint = "socket_mode_events",
                    retryNum = "2",
                    retryReason = "http_timeout"
                )
            }
        }

        @Test
        fun `events_api envelope with null payload은(는) submitEventCallback를 건너뛴다`() {
            val envelope = eventsEnvelope(envelopeId = "env-null-payload", payloadJson = null)

            capturing.eventsListeners.first().handle(envelope)

            verify(exactly = 0) { eventProcessor.submitEventCallback(any(), any(), any(), any()) }
        }

        @Test
        fun `events_api ACK failure은(는) dropped metric and does not propagate를 기록한다`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-ack-fail", payloadJson = payloadJson)

            every { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) } throws RuntimeException("write failed")

            capturing.eventsListeners.first().handle(envelope)  // 예외를 던지면 안 됩니다

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "ack_failure"
                )
            }
        }

        @Test
        fun `events_api envelope with blank envelopeId은(는) ACK entirely를 건너뛴다`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "", payloadJson = payloadJson)

            capturing.eventsListeners.first().handle(envelope)

            verify(exactly = 0) { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
        }

        @Test
        fun `events_api submitEventCallback exception은(는) handler_exception drop and does not propagate를 기록한다`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-handler-fail", payloadJson = payloadJson)

            every { eventProcessor.submitEventCallback(any(), any(), any(), any()) } throws RuntimeException("handler error")

            capturing.eventsListeners.first().handle(envelope)  // 예외를 던지면 안 됩니다

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_events",
                    reason = "handler_exception"
                )
            }
        }
    }

    // =========================================================================
    // slash_commands envelope handling
    // =========================================================================

    @Nested
    inner class SlashCommandEnvelopeHandling {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `유효한 slash command envelope triggers ACK and processor submit`() {
            every { commandProcessor.submit(any(), any()) } returns true

            val envelope = slashEnvelope(envelopeId = "env-slash-ok", payloadJson = validSlashPayloadJson)
            capturing.slashListeners.first().handle(envelope)

            verify { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
            verify {
                commandProcessor.submit(
                    match<SlackSlashCommand> { it.command == "/test" && it.userId == "U123" },
                    entrypoint = "socket_mode_slash_command"
                )
            }
        }

        @Test
        fun `slash은(는) command envelope with null payload skips processor submit`() {
            val envelope = slashEnvelope(envelopeId = "env-slash-null", payloadJson = null)

            capturing.slashListeners.first().handle(envelope)

            verify(exactly = 0) { commandProcessor.submit(any(), any()) }
        }

        @Test
        fun `slash은(는) command payload missing required fields records invalid_payload drop`() {
            val incompletePayload = """{"command": "/test"}""" // missing user_id, channel_id, response_url
            val envelope = slashEnvelope(envelopeId = "env-slash-invalid", payloadJson = incompletePayload)

            capturing.slashListeners.first().handle(envelope)

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "invalid_payload"
                )
            }
        }

        @Test
        fun `slash은(는) command not accepted by processor sends busy response_url`() = runTest {
            every { commandProcessor.submit(any(), any()) } returns false
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            val envelope = slashEnvelope(envelopeId = "env-slash-busy", payloadJson = validSlashPayloadJson)
            capturing.slashListeners.first().handle(envelope)

            coVerify(timeout = 2000) {
                messagingService.sendResponseUrl(
                    responseUrl = "https://hooks.slack.com/commands/test",
                    text = match { it.contains("busy", ignoreCase = true) },
                    any()
                )
            }
        }

        @Test
        fun `slash은(는) command submit exception records handler_exception drop and does not propagate`() {
            every { commandProcessor.submit(any(), any()) } throws RuntimeException("submit failed")

            val envelope = slashEnvelope(envelopeId = "env-slash-err", payloadJson = validSlashPayloadJson)

            capturing.slashListeners.first().handle(envelope)  // 예외를 던지면 안 됩니다

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "handler_exception"
                )
            }
        }

        @Test
        fun `slash은(는) command invalid payload with response_url notifies the url`() = runTest {
            // Missing user_id and channel_id → parseSlashCommand returns null → invalid_payload
            // But response_url is present → notifyResponseUrlIfPresent sends a message
            val payloadWithResponseUrl =
                """{"command": "/test", "response_url": "https://hooks.slack.com/commands/partial"}"""
            coEvery { messagingService.sendResponseUrl(any(), any(), any()) } returns true

            val envelope = slashEnvelope(envelopeId = "env-slash-invalid-url", payloadJson = payloadWithResponseUrl)
            capturing.slashListeners.first().handle(envelope)

            coVerify(timeout = 2000) {
                messagingService.sendResponseUrl(
                    responseUrl = "https://hooks.slack.com/commands/partial",
                    text = any(),
                    any()
                )
            }
        }
    }

    // =========================================================================
    // interactive envelope handling
    // =========================================================================

    @Nested
    inner class InteractiveEnvelopeHandling {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `interactive envelope은(는) acknowledged and recorded as unsupported drop이다`() {
            val envelope = interactiveEnvelope(
                envelopeId = "env-interactive-1",
                payloadJson = """{"type":"block_actions","actions":[]}"""
            )

            capturing.interactiveListeners.first().handle(envelope)

            verify { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_interactive",
                    reason = "unsupported",
                    eventType = "block_actions"
                )
            }
        }

        @Test
        fun `interactive은(는) envelope with null payload uses fallback event type interactive`() {
            val envelope = interactiveEnvelope(envelopeId = "env-interactive-null", payloadJson = null)

            capturing.interactiveListeners.first().handle(envelope)

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_interactive",
                    reason = "unsupported",
                    eventType = "interactive"
                )
            }
        }
    }

    // =========================================================================
    // WebSocket 에러 및 종료 리스너
    // =========================================================================

    @Nested
    inner class WebSocketEventHandling {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `WebSocket error listener은(는) websocket_error dropped metric를 기록한다`() {
            capturing.errorListeners.first().handle(RuntimeException("connection reset"))

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "websocket_error"
                )
            }
        }

        @Test
        fun `WebSocket close listener은(는) websocket_closed dropped metric를 기록한다`() {
            capturing.closeListeners.first().handle(1001, "going away")

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "websocket_closed"
                )
            }
        }
    }

    // =========================================================================
    // / reconnect logic 재시도
    // =========================================================================

    @Nested
    inner class RetryAndReconnect {

        /**
         * [start] 호출로 재시도 루프를 테스트합니다 (내부적으로 [connectWithRetry]를 시작).
         * [connectOnce]는 테스트 환경에서 예외를 던지는 [Slack.getInstance]를 호출하므로,
         * 재시도 루프가 실행되고 [metricsRecorder.recordDropped] 부수 효과를 관찰합니다.
         */

        @Test
        fun `start initiates connection and gateway은(는) not immediately running before connection succeeds이다`() {
            // start()가 동기적으로 running=true를 설정하지 않는지 확인합니다.
            // 게이트웨이는 connectOnce()가 성공해야 실행 상태가 되며, 이는 실제 Slack 연결이 필요합니다
            // (테스트 환경에서 불가). 따라서 start() 직후 isRunning은 false로 유지됩니다.
            val gateway = buildGateway()

            gateway.start()

            // isRunning is set only AFTER connectOnce() completes successfully.
            // 테스트 환경에서 SDK는 실패하므로, 게이트웨이는 재시도하지만 실행 상태가 되지 않습니다.
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running synchronously immediately after start(); " +
                    "isRunning is only set true inside the retry loop after a successful connectOnce()"
            }

            gateway.stop()
        }

        @Test
        fun `cancels the retry loop and leaves gateway not running를 중지한다`() {
            val gateway = buildGateway(
                defaultProperties.copy(socketConnectRetryInitialDelayMs = 50, socketConnectRetryMaxDelayMs = 100)
            )

            gateway.start() // launches retry coroutine
            Thread.sleep(200) // let it run for a bit
            gateway.stop()
            Thread.sleep(200) // confirm no further retries after stop

            assertTrue(!gateway.isRunning) {
                "Gateway must not be running after stop()"
            }
        }

        @Test
        fun `immediately after start leaves gateway not running를 중지한다`() {
            val gateway = buildGateway()

            gateway.start() // launches retry coroutine internally
            gateway.stop() // cancel before any retry succeeds

            assertTrue(!gateway.isRunning) {
                "Gateway must not be running after immediate stop() following start()"
            }
        }

        @Test
        fun `retry은(는) delay clamping with zero inputs does not cause unexpected behavior`() {
            val properties = defaultProperties.copy(
                socketConnectRetryInitialDelayMs = 0, // coerced to 200 by coerceAtLeast(200)
                socketConnectRetryMaxDelayMs = 0       // coerced to 200 by coerceAtLeast(delayMs)
            )
            val gateway = buildGateway(properties)

            gateway.start()  // not hang or throw even with zero delays해야 합니다
            Thread.sleep(300)
            gateway.stop()

            assertTrue(!gateway.isRunning) {
                "Gateway with clamped zero delays should stop cleanly"
            }
        }

        @Test
        fun `multiple start calls while startRequested은(는) true do not launch duplicate retry loops이다`() {
            val gateway = buildGateway(
                defaultProperties.copy(socketConnectRetryInitialDelayMs = 50, socketConnectRetryMaxDelayMs = 100)
            )

            gateway.start()
            gateway.start() // second call: startRequested=true guard prevents re-entry
            Thread.sleep(200)

            // exception; gateway eventually settles in retry loop 없음
            gateway.stop()
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() even with duplicate start() calls"
            }
        }
    }

    // =========================================================================
    // 확인 응답 의미론
    // =========================================================================

    @Nested
    inner class AcknowledgementSemantics {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `ACK response은(는) the correct envelopeId를 포함한다`() {
            val payloadJson =
                """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "specific-env-id-xyz", payloadJson = payloadJson)

            val ackSlot = slot<SocketModeResponse>()
            every { capturing.mock.sendSocketModeResponse(capture(ackSlot)) } returns Unit

            capturing.eventsListeners.first().handle(envelope)

            val ackJson = ackSlot.captured.toString()
            assertTrue(ackJson.contains("specific-env-id-xyz")) {
                "ACK response must include the envelopeId 'specific-env-id-xyz', got: $ackJson"
            }
        }

        @Test
        fun `null인 envelopeId on slash command envelope skips ACK`() {
            val envelope = SlashCommandsEnvelope().also { e ->
                e.envelopeId = null
                e.payload = JsonParser.parseString(validSlashPayloadJson)
            }
            every { commandProcessor.submit(any(), any()) } returns true

            capturing.slashListeners.first().handle(envelope)

            verify(exactly = 0) { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
        }
    }

    // =========================================================================
    // Malformed JSON handling
    // =========================================================================

    @Nested
    inner class MalformedPayloadHandling {

        private lateinit var gateway: SlackSocketModeGateway
        private lateinit var capturing: CapturingMockClient

        @BeforeEach
        fun setup() {
            gateway = buildGateway()
            capturing = buildCapturingMockClient()
            registerListenersOn(gateway, capturing.mock)
        }

        @AfterEach
        fun teardown() {
            gateway.stop()
        }

        @Test
        fun `slash은(는) command envelope with unparseable JSON string records invalid_payload drop`() {
            // JsonPrimitive("not-json") → toString() = "\"not-json\"" which Jackson parses as
            // a string, not an object. path("command") returns MissingNode → parseSlashCommand returns null
            val envelope = SlashCommandsEnvelope().also { e ->
                e.envelopeId = "env-malformed-slash"
                e.payload = com.google.gson.JsonPrimitive("not-json")
            }

            capturing.slashListeners.first().handle(envelope)

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "invalid_payload"
                )
            }
        }

        @Test
        fun `events_api envelope whose handler throws은(는) handler_exception drop를 기록한다`() {
            val payloadJson =
                """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-throw", payloadJson = payloadJson)

            every {
                eventProcessor.submitEventCallback(any(), any(), any(), any())
            } throws com.fasterxml.jackson.core.JsonParseException(null as com.fasterxml.jackson.core.JsonParser?, "parse error")

            capturing.eventsListeners.first().handle(envelope)  // 예외를 던지면 안 됩니다

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_events",
                    reason = "handler_exception"
                )
            }
        }
    }
}

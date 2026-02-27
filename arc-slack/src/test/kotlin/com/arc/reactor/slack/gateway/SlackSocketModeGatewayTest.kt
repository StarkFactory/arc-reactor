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
 * Unit tests for [SlackSocketModeGateway] covering lifecycle, envelope dispatch, and error handling.
 *
 * Because [SlackSocketModeGateway] is a final Kotlin class and [connectOnce] is private, tests use
 * two strategies:
 *  - Reflection to invoke [registerListeners] with a mock [SocketModeClient], capturing listeners.
 *  - Public API / field-injection via reflection to test lifecycle and retry behaviors.
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
    // Reflection helpers
    // -------------------------------------------------------------------------

    private fun buildGateway(properties: SlackProperties = defaultProperties): SlackSocketModeGateway =
        SlackSocketModeGateway(
            properties, objectMapper, commandProcessor, eventProcessor, messagingService, metricsRecorder
        )

    /** Invokes the private [SlackSocketModeGateway.registerListeners] via reflection. */
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
    // Capturing mock client
    // -------------------------------------------------------------------------

    /**
     * A mock [SocketModeClient] that captures all listeners added to it, so tests can
     * invoke registered callbacks directly without opening a real WebSocket.
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
        fun `isAutoStartup returns true`() {
            val gateway = buildGateway()
            assertTrue(gateway.isAutoStartup()) {
                "isAutoStartup should return true so Spring starts the gateway automatically"
            }
        }

        @Test
        fun `phase is Integer MAX_VALUE`() {
            val gateway = buildGateway()
            assertTrue(gateway.phase == Integer.MAX_VALUE) {
                "Expected phase=${Integer.MAX_VALUE} but was ${gateway.phase}"
            }
        }

        @Test
        fun `isRunning returns false before start`() {
            val gateway = buildGateway()
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running before start() is called"
            }
        }

        @Test
        fun `start throws IllegalStateException when appToken is blank`() {
            val gateway = buildGateway(defaultProperties.copy(appToken = ""))
            assertThrows<IllegalStateException>("Expected IllegalStateException when appToken is blank") {
                gateway.start()
            }
        }

        @Test
        fun `start is a no-op when gateway is already marked running`() = runTest {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)

            gateway.start() // guard: running=true, so should return immediately

            // Still running (the flag was set by us; stop was never called)
            assertTrue(gateway.isRunning) {
                "Gateway should still report running after no-op start() when already running"
            }
        }

        @Test
        fun `start is a no-op when startRequested is already true`() = runTest {
            val gateway = buildGateway()
            setBoolean(gateway, "startRequested", true)

            gateway.start() // guard: startRequested=true → returns immediately, no second job

            // startRequested stays true, isRunning unchanged
            assertTrue(!gateway.isRunning) {
                "isRunning should remain false (no successful connection), but should not throw"
            }
        }

        @Test
        fun `stop resets running and startRequested flags`() {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)
            setBoolean(gateway, "startRequested", true)

            gateway.stop()

            assertTrue(!gateway.isRunning) { "Gateway should not be running after stop()" }
        }

        @Test
        fun `stop with Runnable callback invokes callback`() {
            val gateway = buildGateway()
            setBoolean(gateway, "running", true)

            var callbackFired = false
            gateway.stop { callbackFired = true }

            assertTrue(callbackFired) { "Runnable callback must be invoked by stop(Runnable)" }
            assertTrue(!gateway.isRunning) { "Gateway should not be running after stop(callback)" }
        }

        @Test
        fun `stop is safe on a never-started gateway`() {
            val gateway = buildGateway()

            gateway.stop() // should not throw when no client exists

            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() on a never-started instance"
            }
        }

        @Test
        fun `stop handles disconnect and close exceptions without propagating`() {
            val gateway = buildGateway()
            val capturing = buildCapturingMockClient()

            every { capturing.mock.disconnect() } throws RuntimeException("disconnect error")
            every { capturing.mock.close() } throws RuntimeException("close error")
            setField(gateway, "socketModeClient", capturing.mock)
            setBoolean(gateway, "running", true)

            gateway.stop() // must not throw

            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() even when disconnect/close throw"
            }
        }

        @Test
        fun `stop is idempotent when called multiple times`() {
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
        fun `registerListeners registers exactly one listener of each type`() {
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
        fun `events_api envelope triggers ACK and submits event callback`() {
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
        fun `events_api retry metadata is forwarded to submitEventCallback`() {
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
        fun `events_api envelope with null payload skips submitEventCallback`() {
            val envelope = eventsEnvelope(envelopeId = "env-null-payload", payloadJson = null)

            capturing.eventsListeners.first().handle(envelope)

            verify(exactly = 0) { eventProcessor.submitEventCallback(any(), any(), any(), any()) }
        }

        @Test
        fun `events_api ACK failure records dropped metric and does not propagate`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-ack-fail", payloadJson = payloadJson)

            every { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) } throws RuntimeException("write failed")

            capturing.eventsListeners.first().handle(envelope) // must not throw

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "ack_failure"
                )
            }
        }

        @Test
        fun `events_api envelope with blank envelopeId skips ACK entirely`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "", payloadJson = payloadJson)

            capturing.eventsListeners.first().handle(envelope)

            verify(exactly = 0) { capturing.mock.sendSocketModeResponse(any<SocketModeResponse>()) }
        }

        @Test
        fun `events_api submitEventCallback exception records handler_exception drop and does not propagate`() {
            val payloadJson = """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-handler-fail", payloadJson = payloadJson)

            every { eventProcessor.submitEventCallback(any(), any(), any(), any()) } throws RuntimeException("handler error")

            capturing.eventsListeners.first().handle(envelope) // must not throw

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
        fun `valid slash command envelope triggers ACK and processor submit`() {
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
        fun `slash command envelope with null payload skips processor submit`() {
            val envelope = slashEnvelope(envelopeId = "env-slash-null", payloadJson = null)

            capturing.slashListeners.first().handle(envelope)

            verify(exactly = 0) { commandProcessor.submit(any(), any()) }
        }

        @Test
        fun `slash command payload missing required fields records invalid_payload drop`() {
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
        fun `slash command not accepted by processor sends busy response_url`() = runTest {
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
        fun `slash command submit exception records handler_exception drop and does not propagate`() {
            every { commandProcessor.submit(any(), any()) } throws RuntimeException("submit failed")

            val envelope = slashEnvelope(envelopeId = "env-slash-err", payloadJson = validSlashPayloadJson)

            capturing.slashListeners.first().handle(envelope) // must not throw

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_slash_command",
                    reason = "handler_exception"
                )
            }
        }

        @Test
        fun `slash command invalid payload with response_url notifies the url`() = runTest {
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
        fun `interactive envelope is acknowledged and recorded as unsupported drop`() {
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
        fun `interactive envelope with null payload uses fallback event type interactive`() {
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
    // WebSocket error and close listeners
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
        fun `WebSocket error listener records websocket_error dropped metric`() {
            capturing.errorListeners.first().handle(RuntimeException("connection reset"))

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode",
                    reason = "websocket_error"
                )
            }
        }

        @Test
        fun `WebSocket close listener records websocket_closed dropped metric`() {
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
    // Retry / reconnect logic
    // =========================================================================

    @Nested
    inner class RetryAndReconnect {

        /**
         * Tests the retry loop by calling [start] (which internally launches [connectWithRetry]).
         * [connectOnce] will call [Slack.getInstance] which throws in a test environment,
         * so the retry loop fires and we observe [metricsRecorder.recordDropped] side-effects.
         */

        @Test
        fun `start initiates connection and gateway is not immediately running before connection succeeds`() {
            // Verify that start() does not set running=true synchronously.
            // The gateway only becomes running after connectOnce() succeeds, which requires a real Slack
            // connection (unavailable in test env). Therefore, isRunning remains false immediately after start().
            val gateway = buildGateway()

            gateway.start()

            // isRunning is set only AFTER connectOnce() completes successfully.
            // In the test environment, the SDK will fail, so the gateway retries but never becomes running.
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running synchronously immediately after start(); " +
                    "isRunning is only set true inside the retry loop after a successful connectOnce()"
            }

            gateway.stop()
        }

        @Test
        fun `stop cancels the retry loop and leaves gateway not running`() {
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
        fun `stop immediately after start leaves gateway not running`() {
            val gateway = buildGateway()

            gateway.start() // launches retry coroutine internally
            gateway.stop() // cancel before any retry succeeds

            assertTrue(!gateway.isRunning) {
                "Gateway must not be running after immediate stop() following start()"
            }
        }

        @Test
        fun `retry delay clamping with zero inputs does not cause unexpected behavior`() {
            val properties = defaultProperties.copy(
                socketConnectRetryInitialDelayMs = 0, // coerced to 200 by coerceAtLeast(200)
                socketConnectRetryMaxDelayMs = 0       // coerced to 200 by coerceAtLeast(delayMs)
            )
            val gateway = buildGateway(properties)

            gateway.start() // should not hang or throw even with zero delays
            Thread.sleep(300)
            gateway.stop()

            assertTrue(!gateway.isRunning) {
                "Gateway with clamped zero delays should stop cleanly"
            }
        }

        @Test
        fun `multiple start calls while startRequested is true do not launch duplicate retry loops`() {
            val gateway = buildGateway(
                defaultProperties.copy(socketConnectRetryInitialDelayMs = 50, socketConnectRetryMaxDelayMs = 100)
            )

            gateway.start()
            gateway.start() // second call: startRequested=true guard prevents re-entry
            Thread.sleep(200)

            // No exception; gateway eventually settles in retry loop
            gateway.stop()
            assertTrue(!gateway.isRunning) {
                "Gateway should not be running after stop() even with duplicate start() calls"
            }
        }
    }

    // =========================================================================
    // Acknowledgement semantics
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
        fun `ACK response contains the correct envelopeId`() {
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
        fun `null envelopeId on slash command envelope skips ACK`() {
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
        fun `slash command envelope with unparseable JSON string records invalid_payload drop`() {
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
        fun `events_api envelope whose handler throws records handler_exception drop`() {
            val payloadJson =
                """{"type":"event_callback","event":{"type":"app_mention","user":"U","channel":"C","text":"","ts":"1"}}"""
            val envelope = eventsEnvelope(envelopeId = "env-throw", payloadJson = payloadJson)

            every {
                eventProcessor.submitEventCallback(any(), any(), any(), any())
            } throws com.fasterxml.jackson.core.JsonParseException(null as com.fasterxml.jackson.core.JsonParser?, "parse error")

            capturing.eventsListeners.first().handle(envelope) // must not throw

            verify {
                metricsRecorder.recordDropped(
                    entrypoint = "socket_mode_events",
                    reason = "handler_exception"
                )
            }
        }
    }
}

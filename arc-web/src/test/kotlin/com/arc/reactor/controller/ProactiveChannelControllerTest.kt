package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.InMemoryAdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.util.ClassUtils
import org.springframework.web.server.ServerWebExchange

/**
 * 테스트 전용 채널 DTO.
 *
 * ProactiveChannelStoreBridge.toView()가 리플렉션으로 호출하는
 * getChannelId / getChannelName / getAddedAt 게터를 제공한다.
 */
private class FakeChannel(
    private val channelId: String,
    private val channelName: String?,
    private val addedAt: Long = 1_000_000L
) {
    fun getChannelId(): String = channelId
    fun getChannelName(): String? = channelName
    fun getAddedAt(): Long = addedAt
}

/**
 * ProactiveChannelStore 계약을 만족하는 테스트 전용 구현체.
 *
 * ProactiveChannelStoreBridge가 리플렉션으로 호출하는 메서드
 * (list, isEnabled, add, remove)를 구현한다.
 */
private class FakeProactiveChannelStore {

    private val channels = mutableMapOf<String, FakeChannel>()

    fun list(): List<FakeChannel> = channels.values.sortedBy { it.getAddedAt() }

    fun isEnabled(channelId: String): Boolean = channels.containsKey(channelId)

    fun add(channelId: String, channelName: String?): FakeChannel {
        val ch = FakeChannel(channelId, channelName)
        channels[channelId] = ch
        return ch
    }

    fun remove(channelId: String): Boolean = channels.remove(channelId) != null
}

/**
 * ProactiveChannelController 단위 테스트.
 *
 * `ProactiveChannelStoreBridge`의 리플렉션 기반 호출 로직과
 * 관리자 인가 검사, CRUD 엔드포인트 응답 코드를 검증한다.
 *
 * arc-web은 arc-slack에 컴파일 타임 의존성이 없으므로
 * 테스트 전용 가짜 스토어 객체(FakeStore)를 이용해 리플렉션 브릿지를 검증한다.
 */
class ProactiveChannelControllerTest {

    private lateinit var fakeStore: FakeProactiveChannelStore
    private lateinit var auditStore: AdminAuditStore
    private lateinit var applicationContext: ApplicationContext
    private lateinit var controller: ProactiveChannelController

    @BeforeEach
    fun setup() {
        fakeStore = FakeProactiveChannelStore()
        auditStore = InMemoryAdminAuditStore()
        applicationContext = mockk()

        // ClassUtils.resolveClassName이 arc-slack 클래스를 로드하려 시도하므로
        // 테스트 환경(arc-slack 없음)에서는 FakeProactiveChannelStore::class.java를 반환하도록 스텁.
        mockkStatic(ClassUtils::class)
        every {
            ClassUtils.resolveClassName(any<String>(), any())
        } returns FakeProactiveChannelStore::class.java

        every {
            applicationContext.getBean(FakeProactiveChannelStore::class.java)
        } returns fakeStore

        controller = ProactiveChannelController(applicationContext, auditStore)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(ClassUtils::class)
    }

    // ─── 관리자 교환 객체 헬퍼 ──────────────────────────────────────────────────

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }

    // ─── 인가 검사 ────────────────────────────────────────────────────────────

    @Nested
    inner class Authorization {

        @Test
        fun `관리자가 아니면 list는 403을 반환한다`() {
            val response = controller.list(userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "비관리자 요청에 대해 403 Forbidden이어야 한다"
            }
        }

        @Test
        fun `관리자가 아니면 add는 403을 반환한다`() {
            val response = controller.add(
                AddProactiveChannelRequest(channelId = "C123"),
                userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "비관리자 요청에 대해 403 Forbidden이어야 한다"
            }
        }

        @Test
        fun `관리자가 아니면 remove는 403을 반환한다`() {
            val response = controller.remove("C123", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "비관리자 요청에 대해 403 Forbidden이어야 한다"
            }
        }
    }

    // ─── list ─────────────────────────────────────────────────────────────────

    @Nested
    inner class ListChannels {

        @Test
        fun `채널이 없을 때 빈 목록을 반환한다`() {
            val response = controller.list(adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) {
                "빈 스토어에서도 200 OK이어야 한다"
            }
            val body = response.body as? List<*>
            assertNotNull(body) { "응답 바디는 List이어야 한다" }
            assertTrue(body!!.isEmpty()) { "채널이 없으면 빈 목록이어야 한다" }
        }

        @Test
        fun `등록된 채널 목록을 반환한다`() {
            fakeStore.add("C001", "general")
            fakeStore.add("C002", "ops")

            val response = controller.list(adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) {
                "채널 목록 조회는 200 OK이어야 한다"
            }
            val body = response.body as? List<*>
            assertNotNull(body) { "응답 바디는 List이어야 한다" }
            assertEquals(2, body!!.size) { "등록된 채널 수가 2이어야 한다" }
        }

        @Test
        fun `채널 응답에 channelId와 addedAt이 포함된다`() {
            fakeStore.add("C001", "general")

            val response = controller.list(adminExchange())
            val body = response.body as? List<*>
            val first = body?.firstOrNull() as? ProactiveChannelResponse

            assertNotNull(first) { "첫 번째 응답 항목이 ProactiveChannelResponse이어야 한다" }
            assertEquals("C001", first!!.channelId) { "channelId가 일치해야 한다" }
            assertEquals("general", first.channelName) { "channelName이 일치해야 한다" }
        }
    }

    // ─── add ──────────────────────────────────────────────────────────────────

    @Nested
    inner class AddChannel {

        @Test
        fun `새 채널 추가 시 201을 반환한다`() {
            val response = controller.add(
                AddProactiveChannelRequest(channelId = "C100", channelName = "alerts"),
                adminExchange()
            )

            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "새 채널 추가 시 201 Created이어야 한다"
            }
        }

        @Test
        fun `이미 등록된 채널 추가 시 409를 반환한다`() {
            fakeStore.add("C100", null)

            val response = controller.add(
                AddProactiveChannelRequest(channelId = "C100"),
                adminExchange()
            )

            assertEquals(HttpStatus.CONFLICT, response.statusCode) {
                "이미 등록된 채널은 409 Conflict이어야 한다"
            }
        }

        @Test
        fun `채널 추가 후 감사 로그가 기록된다`() {
            controller.add(
                AddProactiveChannelRequest(channelId = "C200", channelName = "monitoring"),
                adminExchange()
            )

            val logs = auditStore.list(category = "proactive_channel", action = "ADD")
            assertTrue(logs.isNotEmpty()) { "ADD 감사 로그가 기록되어야 한다" }
            assertEquals("C200", logs.first().resourceId) { "감사 로그에 channelId가 포함되어야 한다" }
        }

        @Test
        fun `channelName이 null이어도 추가에 성공한다`() {
            val response = controller.add(
                AddProactiveChannelRequest(channelId = "C300"),
                adminExchange()
            )

            assertEquals(HttpStatus.CREATED, response.statusCode) {
                "channelName이 null이어도 201 Created이어야 한다"
            }
        }
    }

    // ─── remove ───────────────────────────────────────────────────────────────

    @Nested
    inner class RemoveChannel {

        @Test
        fun `등록된 채널 삭제 시 204를 반환한다`() {
            fakeStore.add("C500", "to-remove")

            val response = controller.remove("C500", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) {
                "채널 삭제 성공 시 204 No Content이어야 한다"
            }
        }

        @Test
        fun `등록되지 않은 채널 삭제 시 404를 반환한다`() {
            val response = controller.remove("C999", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) {
                "등록되지 않은 채널 삭제 시 404 Not Found이어야 한다"
            }
        }

        @Test
        fun `채널 삭제 후 감사 로그가 기록된다`() {
            fakeStore.add("C600", "ops")

            controller.remove("C600", adminExchange())

            val logs = auditStore.list(category = "proactive_channel", action = "REMOVE")
            assertTrue(logs.isNotEmpty()) { "REMOVE 감사 로그가 기록되어야 한다" }
            assertEquals("C600", logs.first().resourceId) { "감사 로그에 channelId가 포함되어야 한다" }
        }

        @Test
        fun `삭제 후 채널이 목록에서 제거된다`() {
            fakeStore.add("C700", "dev")

            controller.remove("C700", adminExchange())

            val response = controller.list(adminExchange())
            val body = response.body as? List<*>
            assertTrue(body.orEmpty().isEmpty()) { "삭제 후 목록이 비어있어야 한다" }
        }
    }

    // ─── ProactiveChannelStoreBridge 리플렉션 오류 경로 ────────────────────────

    @Nested
    inner class ReflectionBridgeErrorPaths {

        @Test
        fun `스토어가 InvocationTargetException을 던지면 IllegalStateException으로 변환된다`() {
            // list() 메서드가 예외를 던지는 BrokenStore 클래스
            class BrokenStore {
                @Suppress("unused")
                fun list(): List<Any> = throw RuntimeException("DB down")

                @Suppress("unused")
                fun isEnabled(channelId: String): Boolean = false

                @Suppress("unused")
                fun add(channelId: String, channelName: String?): Any = error("not used")

                @Suppress("unused")
                fun remove(channelId: String): Boolean = false
            }

            val brokenStore = BrokenStore()
            // ClassUtils.resolveClassName이 BrokenStore::class.java를 반환하도록 재설정
            every {
                ClassUtils.resolveClassName(any<String>(), any())
            } returns BrokenStore::class.java
            every {
                applicationContext.getBean(BrokenStore::class.java)
            } returns brokenStore

            // 새 컨트롤러 생성 (lazy store 재초기화)
            val brokenController = ProactiveChannelController(applicationContext, auditStore)

            var caught: Exception? = null
            try {
                brokenController.list(adminExchange())
            } catch (e: Exception) {
                caught = e
            }

            assertNotNull(caught) { "broken store는 예외를 던져야 한다" }
            assertTrue(caught is IllegalStateException) {
                "InvocationTargetException은 IllegalStateException으로 변환되어야 한다"
            }
        }
    }
}

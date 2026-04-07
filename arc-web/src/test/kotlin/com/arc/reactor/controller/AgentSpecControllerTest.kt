package com.arc.reactor.controller

import com.arc.reactor.agent.multiagent.AgentSpecRecord
import com.arc.reactor.agent.multiagent.AgentSpecStore
import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * AgentSpecController 테스트.
 *
 * 멀티에이전트 스펙 CRUD API의 권한 검사, 유효성 검증, 정상 동작을 검증한다.
 */
class AgentSpecControllerTest {

    private lateinit var store: AgentSpecStore
    private lateinit var auditStore: AdminAuditStore
    private lateinit var controller: AgentSpecController

    @BeforeEach
    fun setup() {
        store = mockk(relaxed = true)
        auditStore = mockk(relaxed = true)
        controller = AgentSpecController(store, auditStore)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN,
            JwtAuthWebFilter.USER_ID_ATTRIBUTE to "admin-user-1"
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun sampleRecord(
        id: String = "spec-1",
        name: String = "translator",
        mode: String = "REACT",
        enabled: Boolean = true
    ) = AgentSpecRecord(
        id = id,
        name = name,
        description = "번역 에이전트",
        toolNames = listOf("translate"),
        keywords = listOf("번역", "영어"),
        systemPrompt = "번역 전문가입니다.",
        mode = mode,
        enabled = enabled,
        createdAt = Instant.parse("2026-04-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-01T00:00:00Z")
    )

    // ── 목록 조회 ──

    @Nested
    inner class List {

        @Test
        fun `관리자 권한으로 전체 목록을 조회한다`() {
            val records = listOf(sampleRecord(), sampleRecord(id = "spec-2", name = "coder"))
            every { store.list() } returns records

            val result = controller.list(null, adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "전체 목록 조회는 200이어야 한다" }
            val body = result.body as kotlin.collections.List<*>
            assertEquals(2, body.size) { "2개 레코드가 반환되어야 한다" }
        }

        @Test
        fun `enabled 필터로 활성 스펙만 조회한다`() {
            every { store.listEnabled() } returns listOf(sampleRecord())

            val result = controller.list(true, adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "활성 목록 조회는 200이어야 한다" }
            verify(exactly = 1) { store.listEnabled() }
        }

        @Test
        fun `일반 사용자는 403을 받는다`() {
            val result = controller.list(null, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, result.statusCode) { "비관리자는 403이어야 한다" }
        }
    }

    // ── 상세 조회 ──

    @Nested
    inner class Get {

        @Test
        fun `존재하는 스펙을 조회하면 200을 반환한다`() {
            every { store.get("spec-1") } returns sampleRecord()

            val result = controller.get("spec-1", adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "존재하는 스펙 조회는 200이어야 한다" }
            val body = result.body as AgentSpecResponse
            assertEquals("translator", body.name) { "이름이 일치해야 한다" }
        }

        @Test
        fun `존재하지 않는 스펙 조회 시 404를 반환한다`() {
            every { store.get("nonexistent") } returns null

            val result = controller.get("nonexistent", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, result.statusCode) { "미존재 스펙은 404이어야 한다" }
        }
    }

    // ── 생성 ──

    @Nested
    inner class Create {

        @Test
        fun `유효한 요청으로 스펙을 생성하면 201을 반환한다`() {
            every { store.list() } returns emptyList()
            every { store.save(any()) } answers { firstArg() }

            val request = CreateAgentSpecRequest(
                name = "translator",
                description = "번역 에이전트",
                toolNames = listOf("translate"),
                keywords = listOf("번역"),
                mode = "REACT"
            )

            val result = controller.create(request, adminExchange())

            assertEquals(HttpStatus.CREATED, result.statusCode) { "생성 성공은 201이어야 한다" }
            verify(exactly = 1) { store.save(any()) }
        }

        @Test
        fun `유효하지 않은 모드로 생성하면 400을 반환한다`() {
            val request = CreateAgentSpecRequest(
                name = "bad-agent",
                mode = "INVALID_MODE"
            )

            val result = controller.create(request, adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, result.statusCode) { "잘못된 모드는 400이어야 한다" }
        }

        @Test
        fun `중복 이름으로 생성하면 409를 반환한다`() {
            every { store.list() } returns listOf(sampleRecord(name = "translator"))

            val request = CreateAgentSpecRequest(name = "translator")

            val result = controller.create(request, adminExchange())

            assertEquals(HttpStatus.CONFLICT, result.statusCode) { "중복 이름은 409이어야 한다" }
        }

        @Test
        fun `모드 미지정 시 REACT가 기본값이다`() {
            every { store.list() } returns emptyList()
            every { store.save(any()) } answers { firstArg() }

            val request = CreateAgentSpecRequest(name = "default-mode-agent")

            val result = controller.create(request, adminExchange())

            assertEquals(HttpStatus.CREATED, result.statusCode) { "기본 모드 생성은 201이어야 한다" }
            val body = result.body as AgentSpecResponse
            assertEquals("REACT", body.mode) { "기본 모드는 REACT이어야 한다" }
        }
    }

    // ── 수정 ──

    @Nested
    inner class Update {

        @Test
        fun `존재하는 스펙을 수정하면 200을 반환한다`() {
            every { store.get("spec-1") } returns sampleRecord()
            every { store.save(any()) } answers { firstArg() }

            val request = UpdateAgentSpecRequest(description = "수정된 설명")

            val result = controller.update("spec-1", request, adminExchange())

            assertEquals(HttpStatus.OK, result.statusCode) { "수정 성공은 200이어야 한다" }
            val body = result.body as AgentSpecResponse
            assertEquals("수정된 설명", body.description) { "설명이 업데이트되어야 한다" }
        }

        @Test
        fun `존재하지 않는 스펙 수정 시 404를 반환한다`() {
            every { store.get("nonexistent") } returns null

            val request = UpdateAgentSpecRequest(description = "수정")

            val result = controller.update("nonexistent", request, adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, result.statusCode) { "미존재 스펙 수정은 404이어야 한다" }
        }

        @Test
        fun `유효하지 않은 모드로 수정하면 400을 반환한다`() {
            val request = UpdateAgentSpecRequest(mode = "INVALID")

            val result = controller.update("spec-1", request, adminExchange())

            assertEquals(HttpStatus.BAD_REQUEST, result.statusCode) { "잘못된 모드 수정은 400이어야 한다" }
        }
    }

    // ── 삭제 ──

    @Nested
    inner class Delete {

        @Test
        fun `존재하는 스펙을 삭제하면 204를 반환한다`() {
            every { store.get("spec-1") } returns sampleRecord()

            val result = controller.delete("spec-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, result.statusCode) { "삭제 성공은 204이어야 한다" }
            verify(exactly = 1) { store.delete("spec-1") }
        }

        @Test
        fun `존재하지 않는 스펙 삭제 시 404를 반환한다`() {
            every { store.get("nonexistent") } returns null

            val result = controller.delete("nonexistent", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, result.statusCode) { "미존재 스펙 삭제는 404이어야 한다" }
        }

        @Test
        fun `비관리자 삭제 시도는 403을 반환한다`() {
            val result = controller.delete("spec-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, result.statusCode) { "비관리자 삭제는 403이어야 한다" }
        }
    }
}

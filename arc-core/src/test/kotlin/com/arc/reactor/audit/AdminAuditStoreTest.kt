package com.arc.reactor.audit

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [InMemoryAdminAuditStore] 및 [recordAdminAudit] 헬퍼에 대한 단위 테스트.
 *
 * 필터링, 용량 제한, fail-open 동작, limit clamping 등을 검증한다.
 */
@DisplayName("AdminAuditStore")
class AdminAuditStoreTest {

    // ─── 저장 및 조회 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("List — 전체 조회")
    inner class ListAll {

        @Test
        fun `저장된 로그를 최신순으로 반환한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "cat", action = "CREATE", actor = "a"))
            store.save(AdminAuditLog(category = "cat", action = "UPDATE", actor = "b"))

            val result = store.list(limit = 10)

            assertEquals(2, result.size) { "저장된 로그 수가 일치해야 한다" }
            // addFirst 순서이므로 가장 최근에 저장한 로그가 앞에 있어야 한다
            assertEquals("UPDATE", result.first().action) { "최신 로그가 첫 번째여야 한다" }
        }

        @Test
        fun `저장소가 비어있으면 빈 리스트를 반환한다`() {
            val store = InMemoryAdminAuditStore()

            val result = store.list()

            assertTrue(result.isEmpty()) { "빈 저장소는 빈 리스트를 반환해야 한다" }
        }

        @Test
        fun `save가 저장한 로그 객체를 그대로 반환한다`() {
            val store = InMemoryAdminAuditStore()
            val log = AdminAuditLog(category = "cat", action = "CREATE", actor = "admin")

            val returned = store.save(log)

            assertEquals(log, returned) { "save()는 입력 로그를 그대로 반환해야 한다" }
        }
    }

    // ─── 카테고리 필터 ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("List — 카테고리 필터")
    inner class CategoryFilter {

        @Test
        fun `카테고리로만 필터링하면 해당 카테고리 로그만 반환한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "tool_policy", action = "UPDATE", actor = "admin-1"))
            store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "admin-2"))
            store.save(AdminAuditLog(category = "mcp_server", action = "DELETE", actor = "admin-2"))

            val result = store.list(limit = 10, category = "mcp_server")

            assertEquals(2, result.size) { "mcp_server 카테고리 로그는 2건이어야 한다" }
            assertTrue(result.all { it.category == "mcp_server" }) { "모든 결과가 mcp_server 카테고리여야 한다" }
        }

        @Test
        fun `카테고리 필터는 대소문자를 무시한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "tool_policy", action = "UPDATE", actor = "admin"))

            val upper = store.list(limit = 10, category = "TOOL_POLICY")
            val mixed = store.list(limit = 10, category = "Tool_Policy")

            assertEquals(1, upper.size) { "대문자 카테고리 필터가 동작해야 한다" }
            assertEquals(1, mixed.size) { "혼합 대소문자 카테고리 필터가 동작해야 한다" }
        }

        @Test
        fun `공백만 있는 카테고리는 필터 없음으로 처리한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "cat1", action = "CREATE", actor = "admin"))
            store.save(AdminAuditLog(category = "cat2", action = "UPDATE", actor = "admin"))

            val result = store.list(limit = 10, category = "   ")

            assertEquals(2, result.size) { "공백 카테고리는 필터 없음으로 처리해야 한다" }
        }
    }

    // ─── 액션 필터 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("List — 액션 필터")
    inner class ActionFilter {

        @Test
        fun `액션으로만 필터링하면 해당 액션 로그만 반환한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "cat", action = "CREATE", actor = "a"))
            store.save(AdminAuditLog(category = "cat", action = "DELETE", actor = "b"))

            val result = store.list(limit = 10, action = "DELETE")

            assertEquals(1, result.size) { "DELETE 액션 로그는 1건이어야 한다" }
            assertEquals("DELETE", result.first().action) { "결과 로그의 액션이 DELETE여야 한다" }
        }

        @Test
        fun `액션 필터는 대소문자를 무시한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "cat", action = "CREATE", actor = "admin"))

            val lower = store.list(limit = 10, action = "create")
            val mixed = store.list(limit = 10, action = "Create")

            assertEquals(1, lower.size) { "소문자 액션 필터가 동작해야 한다" }
            assertEquals(1, mixed.size) { "혼합 대소문자 액션 필터가 동작해야 한다" }
        }

        @Test
        fun `공백만 있는 액션은 필터 없음으로 처리한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "cat", action = "CREATE", actor = "admin"))
            store.save(AdminAuditLog(category = "cat", action = "DELETE", actor = "admin"))

            val result = store.list(limit = 10, action = "  ")

            assertEquals(2, result.size) { "공백 액션은 필터 없음으로 처리해야 한다" }
        }
    }

    // ─── 카테고리 + 액션 복합 필터 ─────────────────────────────────────────────

    @Nested
    @DisplayName("List — 복합 필터")
    inner class CombinedFilter {

        @Test
        fun `카테고리와 액션을 함께 필터링한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "a"))
            store.save(AdminAuditLog(category = "mcp_server", action = "DELETE", actor = "b"))
            store.save(AdminAuditLog(category = "tool_policy", action = "CREATE", actor = "c"))

            val result = store.list(limit = 10, category = "mcp_server", action = "delete")

            assertEquals(1, result.size) { "카테고리+액션 복합 필터 결과는 1건이어야 한다" }
            assertEquals("mcp_server", result.first().category) { "카테고리가 mcp_server여야 한다" }
            assertEquals("DELETE", result.first().action) { "액션이 DELETE여야 한다" }
        }

        @Test
        fun `카테고리와 액션이 모두 매칭되지 않으면 빈 리스트를 반환한다`() {
            val store = InMemoryAdminAuditStore()
            store.save(AdminAuditLog(category = "mcp_server", action = "CREATE", actor = "a"))

            val result = store.list(limit = 10, category = "mcp_server", action = "UPDATE")

            assertTrue(result.isEmpty()) { "매칭 없으면 빈 리스트여야 한다" }
        }
    }

    // ─── limit clamping ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("List — limit clamping")
    inner class LimitClamping {

        @Test
        fun `limit이 저장된 건수보다 크면 전체를 반환한다`() {
            val store = InMemoryAdminAuditStore()
            repeat(3) { i ->
                store.save(AdminAuditLog(category = "c", action = "CREATE", actor = "admin-$i"))
            }

            val result = store.list(limit = 100)

            assertEquals(3, result.size) { "limit보다 적은 건수면 전체를 반환해야 한다" }
        }

        @Test
        fun `limit이 0이면 1로 클램핑된다`() {
            val store = InMemoryAdminAuditStore()
            repeat(5) { i ->
                store.save(AdminAuditLog(category = "c", action = "CREATE", actor = "admin-$i"))
            }

            val result = store.list(limit = 0)

            assertEquals(1, result.size) { "limit=0은 1로 클램핑되어야 한다" }
        }

        @Test
        fun `limit이 1001이면 1000으로 클램핑된다`() {
            val store = InMemoryAdminAuditStore()
            repeat(5) { i ->
                store.save(AdminAuditLog(category = "c", action = "UPDATE", actor = "admin-$i"))
            }

            // limit=1001 → coerceIn(1, 1000) → 1000, 실제 데이터 5건이 반환된다
            val result = store.list(limit = 1001)

            assertEquals(5, result.size) { "limit=1001은 1000으로 클램핑되어야 하며 실제 5건이 반환되어야 한다" }
        }
    }

    // ─── 용량 제한 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("용량 제한 — 최대 10,000건")
    inner class CapacityLimit {

        @Test
        fun `10000건 초과 저장 시 가장 오래된 로그가 제거된다`() {
            val store = InMemoryAdminAuditStore()

            // 먼저 "oldest" 로그를 저장
            val oldest = store.save(
                AdminAuditLog(category = "old", action = "CREATE", actor = "old-admin")
            )

            // 추가로 10000건을 저장하여 "oldest"가 밀려나도록 한다
            repeat(10000) { i ->
                store.save(AdminAuditLog(category = "new", action = "UPDATE", actor = "admin-$i"))
            }

            val all = store.list(limit = 1000, category = "old")

            assertTrue(all.none { it.id == oldest.id }) { "10000건 초과 후 가장 오래된 로그는 제거되어야 한다" }
        }

        @Test
        fun `정확히 10000건 저장 시 아무것도 제거되지 않는다`() {
            val store = InMemoryAdminAuditStore()

            repeat(10000) { i ->
                store.save(AdminAuditLog(category = "cat", action = "CREATE", actor = "admin-$i"))
            }

            val result = store.list(limit = 1000)

            assertEquals(1000, result.size) { "10000건 저장 후 limit=1000이면 1000건이 반환되어야 한다" }
        }
    }

    // ─── recordAdminAudit 헬퍼 ────────────────────────────────────────────────

    @Nested
    @DisplayName("recordAdminAudit — fail-open 헬퍼")
    inner class RecordAdminAuditHelper {

        @Test
        fun `정상 저장소에 올바른 필드로 로그를 저장한다`() {
            val store = InMemoryAdminAuditStore()

            recordAdminAudit(
                store = store,
                category = "tool_policy",
                action = "CREATE",
                actor = "admin-1",
                resourceType = "ToolPolicy",
                resourceId = "tp-001",
                detail = "새 정책 생성"
            )

            val result = store.list(limit = 10)
            assertEquals(1, result.size) { "recordAdminAudit 후 1건이 저장되어야 한다" }
            val saved = result.first()
            assertEquals("tool_policy", saved.category) { "카테고리가 일치해야 한다" }
            assertEquals("CREATE", saved.action) { "액션이 일치해야 한다" }
            assertEquals("admin-1", saved.actor) { "actor가 일치해야 한다" }
            assertEquals("ToolPolicy", saved.resourceType) { "resourceType이 일치해야 한다" }
            assertEquals("tp-001", saved.resourceId) { "resourceId가 일치해야 한다" }
            assertEquals("새 정책 생성", saved.detail) { "detail이 일치해야 한다" }
            assertNotNull(saved.createdAt) { "createdAt이 설정되어야 한다" }
        }

        @Test
        fun `저장소 예외가 발생해도 호출자에게 예외를 전파하지 않는다`() {
            val failingStore = mockk<AdminAuditStore>()
            every { failingStore.save(any()) } throws RuntimeException("DB 연결 실패")

            // fail-open: 예외가 발생해도 조용히 무시해야 한다
            recordAdminAudit(
                store = failingStore,
                category = "cat",
                action = "DELETE",
                actor = "admin"
            )
            // 여기까지 도달하면 테스트 통과
        }

        @Test
        fun `선택 파라미터(resourceType, resourceId, detail)는 null 가능하다`() {
            val store = InMemoryAdminAuditStore()

            recordAdminAudit(
                store = store,
                category = "cat",
                action = "UPDATE",
                actor = "admin"
            )

            val result = store.list(limit = 10)
            assertEquals(1, result.size) { "선택 파라미터 없이도 저장되어야 한다" }
            val saved = result.first()
            assertTrue(saved.resourceType == null) { "resourceType은 null이어야 한다" }
            assertTrue(saved.resourceId == null) { "resourceId는 null이어야 한다" }
            assertTrue(saved.detail == null) { "detail은 null이어야 한다" }
        }
    }
}

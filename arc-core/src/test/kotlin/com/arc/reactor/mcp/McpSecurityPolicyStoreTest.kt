package com.arc.reactor.mcp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [InMemoryMcpSecurityPolicyStore] 및 [McpSecurityPolicyProvider]에 대한 테스트.
 *
 * 인메모리 정책 저장소의 CRUD 동작과
 * 정적·동적 보안 정책 통합 로직을 검증한다.
 */
class McpSecurityPolicyStoreTest {

    // ── InMemoryMcpSecurityPolicyStore ────────────────────────────────────────

    @Nested
    inner class InMemoryStore {

        private lateinit var store: InMemoryMcpSecurityPolicyStore

        @BeforeEach
        fun setUp() {
            store = InMemoryMcpSecurityPolicyStore()
        }

        @Nested
        inner class GetOrNull {

            @Test
            fun `초기 정책이 없으면 null을 반환해야 한다`() {
                val result = store.getOrNull()
                assertNull(result) { "초기화된 직후에는 정책이 없어야 한다" }
            }

            @Test
            fun `초기값을 주입하면 바로 조회할 수 있어야 한다`() {
                val initial = McpSecurityPolicy(allowedServerNames = setOf("init-server"))
                val storeWithInitial = InMemoryMcpSecurityPolicyStore(initial)

                val result = storeWithInitial.getOrNull()

                assertNotNull(result) { "초기값이 있으면 null이 아니어야 한다" }
                result!!.allowedServerNames shouldBe setOf("init-server")
            }
        }

        @Nested
        inner class Save {

            @Test
            fun `정책을 저장하면 조회할 수 있어야 한다`() {
                val policy = McpSecurityPolicy(
                    allowedServerNames = setOf("server-a"),
                    maxToolOutputLength = 10_000
                )

                store.save(policy)
                val found = store.getOrNull()

                assertNotNull(found) { "저장 후 정책이 조회되어야 한다" }
                found!!.allowedServerNames shouldBe setOf("server-a")
                found.maxToolOutputLength shouldBe 10_000
            }

            @Test
            fun `첫 저장 시 createdAt이 설정되어야 한다`() {
                val before = Instant.now()
                val saved = store.save(McpSecurityPolicy())
                val after = Instant.now()

                assertTrue(saved.createdAt >= before && saved.createdAt <= after) {
                    "createdAt은 저장 시각 범위 안에 있어야 한다: createdAt=${saved.createdAt}"
                }
            }

            @Test
            fun `첫 저장 시 updatedAt이 설정되어야 한다`() {
                val before = Instant.now()
                val saved = store.save(McpSecurityPolicy())
                val after = Instant.now()

                assertTrue(saved.updatedAt >= before && saved.updatedAt <= after) {
                    "updatedAt은 저장 시각 범위 안에 있어야 한다: updatedAt=${saved.updatedAt}"
                }
            }

            @Test
            fun `덮어쓰기 시 createdAt은 보존되고 updatedAt은 갱신되어야 한다`() {
                val first = store.save(McpSecurityPolicy(allowedServerNames = setOf("first")))
                val firstCreatedAt = first.createdAt

                Thread.sleep(5) // updatedAt 차이를 만들기 위한 최소 대기

                val second = store.save(McpSecurityPolicy(allowedServerNames = setOf("second")))

                second.createdAt shouldBe firstCreatedAt
                second.updatedAt shouldBeGreaterThanOrEqualTo first.updatedAt
                second.allowedServerNames shouldBe setOf("second")
            }

            @Test
            fun `save는 저장된 정책을 반환해야 한다`() {
                val policy = McpSecurityPolicy(allowedServerNames = setOf("returned"))
                val returned = store.save(policy)

                assertNotNull(returned) { "save 반환값이 null이면 안 된다" }
                returned.allowedServerNames shouldBe setOf("returned")
            }
        }

        @Nested
        inner class Delete {

            @Test
            fun `정책이 있을 때 삭제하면 true를 반환해야 한다`() {
                store.save(McpSecurityPolicy())
                val deleted = store.delete()

                assertTrue(deleted) { "정책이 있었으므로 true를 반환해야 한다" }
            }

            @Test
            fun `삭제 후 조회하면 null이어야 한다`() {
                store.save(McpSecurityPolicy(allowedServerNames = setOf("to-delete")))
                store.delete()

                assertNull(store.getOrNull()) { "삭제 후에는 null이어야 한다" }
            }

            @Test
            fun `정책이 없을 때 삭제하면 false를 반환해야 한다`() {
                val deleted = store.delete()

                assertFalse(deleted) { "정책이 없었으므로 false를 반환해야 한다" }
            }

            @Test
            fun `연속 삭제 시 두 번째는 false를 반환해야 한다`() {
                store.save(McpSecurityPolicy())
                store.delete()

                val secondDelete = store.delete()

                assertFalse(secondDelete) { "이미 삭제된 상태에서 다시 삭제하면 false여야 한다" }
            }
        }
    }

    // ── McpSecurityPolicyProvider ─────────────────────────────────────────────

    @Nested
    inner class PolicyProvider {

        private val defaultConfig = McpSecurityConfig(
            allowedServerNames = setOf("default-server"),
            maxToolOutputLength = 50_000,
            allowedStdioCommands = setOf("npx", "node")
        )

        private fun provider(store: McpSecurityPolicyStore = InMemoryMcpSecurityPolicyStore()) =
            McpSecurityPolicyProvider(defaultConfig, store)

        @Nested
        inner class CurrentPolicy {

            @Test
            fun `동적 정책이 없으면 기본 설정을 기반으로 정책을 반환해야 한다`() {
                val p = provider()
                val policy = p.currentPolicy()

                policy.allowedServerNames shouldBe setOf("default-server")
                policy.maxToolOutputLength shouldBe 50_000
            }

            @Test
            fun `동적 정책이 있으면 기본 설정 대신 동적 정책을 사용해야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(allowedServerNames = setOf("dynamic-server")))
                val p = provider(store)

                val policy = p.currentPolicy()

                policy.allowedServerNames shouldBe setOf("dynamic-server")
            }

            @Test
            fun `동적 정책 삭제 후에는 기본 설정으로 돌아와야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(allowedServerNames = setOf("dynamic-server")))
                val p = provider(store)

                store.delete()
                val policy = p.currentPolicy()

                policy.allowedServerNames shouldBe setOf("default-server")
            }
        }

        @Nested
        inner class CurrentConfig {

            @Test
            fun `currentConfig는 현재 정책에서 McpSecurityConfig를 반환해야 한다`() {
                val p = provider()
                val config = p.currentConfig()

                assertNotNull(config) { "currentConfig는 null이면 안 된다" }
                config.allowedServerNames shouldBe setOf("default-server")
            }

            @Test
            fun `동적 정책 저장 후 currentConfig가 반영되어야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                val p = provider(store)

                store.save(McpSecurityPolicy(allowedServerNames = setOf("updated-server")))
                val config = p.currentConfig()

                config.allowedServerNames shouldBe setOf("updated-server")
            }
        }

        @Nested
        inner class Normalize {

            @Test
            fun `서버 이름 공백은 제거되어야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(allowedServerNames = setOf("  server-a  ", " server-b")))
                val p = provider(store)

                val policy = p.currentPolicy()

                assertTrue("server-a" in policy.allowedServerNames) {
                    "공백이 제거된 'server-a'가 허용 목록에 있어야 한다"
                }
                assertTrue("server-b" in policy.allowedServerNames) {
                    "공백이 제거된 'server-b'가 허용 목록에 있어야 한다"
                }
            }

            @Test
            fun `빈 서버 이름은 필터링되어야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(allowedServerNames = setOf("", "   ", "valid-server")))
                val p = provider(store)

                val policy = p.currentPolicy()

                assertFalse("" in policy.allowedServerNames) {
                    "빈 문자열은 허용 목록에 포함되면 안 된다"
                }
                assertFalse("   " in policy.allowedServerNames) {
                    "공백만 있는 이름은 허용 목록에 포함되면 안 된다"
                }
                assertTrue("valid-server" in policy.allowedServerNames) {
                    "유효한 이름은 허용 목록에 남아 있어야 한다"
                }
            }

            @Test
            fun `도구 출력 길이가 최솟값보다 작으면 최솟값으로 강제되어야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(maxToolOutputLength = 0))
                val p = provider(store)

                val policy = p.currentPolicy()

                policy.maxToolOutputLength shouldBe McpSecurityPolicyProvider.MIN_TOOL_OUTPUT_LENGTH
            }

            @Test
            fun `도구 출력 길이가 최댓값보다 크면 최댓값으로 강제되어야 한다`() {
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(maxToolOutputLength = Int.MAX_VALUE))
                val p = provider(store)

                val policy = p.currentPolicy()

                policy.maxToolOutputLength shouldBe McpSecurityPolicyProvider.MAX_TOOL_OUTPUT_LENGTH
            }

            @Test
            fun `도구 출력 길이가 유효 범위 내에 있으면 그대로 유지되어야 한다`() {
                val validLength = 100_000
                val store = InMemoryMcpSecurityPolicyStore()
                store.save(McpSecurityPolicy(maxToolOutputLength = validLength))
                val p = provider(store)

                val policy = p.currentPolicy()

                policy.maxToolOutputLength shouldBe validLength
            }
        }

        @Nested
        inner class Invalidate {

            @Test
            fun `invalidate 호출 후에도 정책 조회가 정상 동작해야 한다`() {
                val p = provider()
                p.invalidate()  // 예약된 메서드 — 예외 없이 동작해야 한다

                val policy = p.currentPolicy()

                assertNotNull(policy) { "invalidate 이후에도 정책을 조회할 수 있어야 한다" }
            }
        }
    }

    // ── McpSecurityPolicy 헬퍼 메서드 ─────────────────────────────────────────

    @Nested
    inner class McpSecurityPolicyHelpers {

        @Test
        fun `toConfig는 정책 값을 그대로 McpSecurityConfig로 변환해야 한다`() {
            val policy = McpSecurityPolicy(
                allowedServerNames = setOf("s1"),
                maxToolOutputLength = 20_000,
                allowedStdioCommands = setOf("node")
            )

            val config = policy.toConfig()

            config.allowedServerNames shouldBe setOf("s1")
            config.maxToolOutputLength shouldBe 20_000
            config.allowedStdioCommands shouldBe setOf("node")
        }

        @Test
        fun `fromConfig는 McpSecurityConfig 값을 McpSecurityPolicy로 변환해야 한다`() {
            val config = McpSecurityConfig(
                allowedServerNames = setOf("s2"),
                maxToolOutputLength = 30_000,
                allowedStdioCommands = setOf("python")
            )

            val policy = McpSecurityPolicy.fromConfig(config)

            policy.allowedServerNames shouldBe setOf("s2")
            policy.maxToolOutputLength shouldBe 30_000
            policy.allowedStdioCommands shouldBe setOf("python")
        }

        @Test
        fun `fromConfig와 toConfig는 왕복 변환이 일치해야 한다`() {
            val original = McpSecurityConfig(
                allowedServerNames = setOf("roundtrip"),
                maxToolOutputLength = 40_000,
                allowedStdioCommands = setOf("deno")
            )

            val roundTripped = McpSecurityPolicy.fromConfig(original).toConfig()

            roundTripped.allowedServerNames shouldBe original.allowedServerNames
            roundTripped.maxToolOutputLength shouldBe original.maxToolOutputLength
            roundTripped.allowedStdioCommands shouldBe original.allowedStdioCommands
        }
    }
}

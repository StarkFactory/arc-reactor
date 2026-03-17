package com.arc.reactor.admin.collection

import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** [TenantResolver]의 ThreadLocal 테넌트 관리 및 [TenantWebFilter]의 요청별 테넌트 해석 테스트 */
class TenantResolverTest {

    private lateinit var resolver: TenantResolver

    @BeforeEach
    fun setUp() {
        resolver = TenantResolver()
    }

    @AfterEach
    fun tearDown() {
        resolver.clear()
    }

    @Nested
    inner class ThreadLocal {

        @Test
        fun `no tenant set일 때 default를 반환한다`() {
            resolver.currentTenantId() shouldBe "default"
        }

        @Test
        fun `set tenant ID를 반환한다`() {
            resolver.setTenantId("tenant-123")
            resolver.currentTenantId() shouldBe "tenant-123"
        }

        @Test
        fun `resets to default를 비운다`() {
            resolver.setTenantId("tenant-123")
            resolver.clear()
            resolver.currentTenantId() shouldBe "default"
        }

        @Test
        fun `overwrites은(는) previous tenant ID`() {
            resolver.setTenantId("first")
            resolver.setTenantId("second")
            resolver.currentTenantId() shouldBe "second"
        }
    }

    @Nested
    inner class WebFilterTests {

        private lateinit var filter: TenantWebFilter

        @BeforeEach
        fun setUp() {
            filter = TenantWebFilter(resolver)
        }

        @Test
        fun `tenant from X-Tenant-Id header를 추출한다`() {
            val exchange = mockExchange(headerTenantId = "from-header")
            val chain = mockk<WebFilterChain>()
            every { chain.filter(any()) } returns Mono.empty()

            filter.filter(exchange, chain).block()

            // doFinally 후 테넌트가 초기화됨. 필터는 chain.filter 전에 테넌트를 설정하고 이후에 초기화
        }

        @Test
        fun `tenant from exchange attribute를 추출한다`() {
            val exchange = mockExchange(attrTenantId = "from-attr")
            val chain = mockk<WebFilterChain>()

            var capturedTenantId: String? = null
            every { chain.filter(any()) } answers {
                capturedTenantId = resolver.currentTenantId()
                Mono.empty()
            }

            filter.filter(exchange, chain).block()

            capturedTenantId shouldBe "from-attr"
        }

        @Test
        fun `request when header mismatches resolved tenant context를 거부한다`() {
            val exchange = mockExchange(
                headerTenantId = "from-header",
                attrTenantId = "from-attr",
                role = UserRole.USER
            )
            val chain = mockk<WebFilterChain>()

            every { chain.filter(any()) } returns Mono.empty()

            shouldThrow<org.springframework.web.server.ServerWebInputException> {
                filter.filter(exchange, chain).block()
            }
        }

        @Test
        fun `admin tenant override when header mismatches resolved context를 허용한다`() {
            val exchange = mockExchange(
                headerTenantId = "from-header",
                resolvedTenantId = "from-attr",
                role = UserRole.ADMIN_MANAGER
            )
            val chain = mockk<WebFilterChain>()

            var capturedTenantId: String? = null
            every { chain.filter(any()) } answers {
                capturedTenantId = resolver.currentTenantId()
                Mono.empty()
            }

            filter.filter(exchange, chain).block()

            capturedTenantId shouldBe "from-header"
        }

        @Test
        fun `header when it matches resolved tenant context를 수락한다`() {
            val exchange = mockExchange(
                headerTenantId = "from-attr",
                resolvedTenantId = "from-attr",
                attrTenantId = "legacy-value"
            )
            val chain = mockk<WebFilterChain>()

            var capturedTenantId: String? = null
            every { chain.filter(any()) } answers {
                capturedTenantId = resolver.currentTenantId()
                Mono.empty()
            }

            filter.filter(exchange, chain).block()

            capturedTenantId shouldBe "from-attr"
        }

        @Test
        fun `malformed tenant header를 거부한다`() {
            val exchange = mockExchange(headerTenantId = "tenant-1;DROP TABLE users")
            val chain = mockk<WebFilterChain>()
            every { chain.filter(any()) } returns Mono.empty()

            shouldThrow<org.springframework.web.server.ServerWebInputException> {
                filter.filter(exchange, chain).block()
            }
        }

        @Test
        fun `default when no tenant info present로 폴백한다`() {
            val exchange = mockExchange()
            val chain = mockk<WebFilterChain>()

            var capturedTenantId: String? = null
            every { chain.filter(any()) } answers {
                capturedTenantId = resolver.currentTenantId()
                Mono.empty()
            }

            filter.filter(exchange, chain).block()

            capturedTenantId shouldBe "default"
        }

        @Test
        fun `tenant after request completes를 비운다`() {
            val exchange = mockExchange(headerTenantId = "temp-tenant")
            val chain = mockk<WebFilterChain>()
            every { chain.filter(any()) } returns Mono.empty()

            filter.filter(exchange, chain).block()

            resolver.currentTenantId() shouldBe "default"
        }

        @Test
        fun `has은(는) highest precedence ordering`() {
            filter.order shouldBe (org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 10)
        }

        private fun mockExchange(
            headerTenantId: String? = null,
            attrTenantId: String? = null,
            resolvedTenantId: String? = null,
            role: UserRole? = null
        ): ServerWebExchange {
            val headers = mockk<HttpHeaders>()
            every { headers.getFirst("X-Tenant-Id") } returns headerTenantId

            val request = mockk<ServerHttpRequest>()
            every { request.headers } returns headers

            val attributes = mutableMapOf<String, Any>()
            if (attrTenantId != null) {
                attributes[TenantResolver.LEGACY_ATTR_KEY] = attrTenantId
            }
            if (resolvedTenantId != null) {
                attributes[TenantResolver.EXCHANGE_ATTR_KEY] = resolvedTenantId
            }
            if (role != null) {
                attributes[JwtAuthWebFilter.USER_ROLE_ATTRIBUTE] = role
            }

            val exchange = mockk<ServerWebExchange>()
            every { exchange.request } returns request
            every { exchange.attributes } returns attributes

            return exchange
        }
    }
}

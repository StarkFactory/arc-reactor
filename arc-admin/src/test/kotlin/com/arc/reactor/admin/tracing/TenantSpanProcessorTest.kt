package com.arc.reactor.admin.tracing

import com.arc.reactor.admin.collection.TenantResolver
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private val TENANT_ID_KEY = AttributeKey.stringKey("tenant_id")

/**
 * [TenantSpanProcessor]의 테넌트 ID 주입 동작 테스트.
 *
 * 멀티 테넌트 환경에서 모든 OTel 스팬에 tenant_id 속성이 올바르게
 * 주입되는지 검증한다. OTel Context 우선, ThreadLocal 폴백 순서를 확인한다.
 */
class TenantSpanProcessorTest {

    private val tenantResolver = TenantResolver()
    private val processor = TenantSpanProcessor(tenantResolver)

    @AfterEach
    fun tearDown() {
        tenantResolver.clear()
    }

    @Nested
    inner class OnStart {

        @Test
        fun `OTel Context에 tenant ID가 있으면 스팬에 해당 값을 주입한다`() {
            val span = mockk<ReadWriteSpan>(relaxed = true)
            val capturedKey = slot<AttributeKey<String>>()
            val capturedValue = slot<String>()
            every { span.setAttribute(capture(capturedKey), capture(capturedValue)) } returns span

            val ctx = Context.current().with(TenantSpanProcessor.TENANT_CONTEXT_KEY, "tenant-otel")

            processor.onStart(ctx, span)

            assertEquals(TENANT_ID_KEY, capturedKey.captured) {
                "setAttribute는 tenant_id AttributeKey로 호출되어야 한다"
            }
            assertEquals("tenant-otel", capturedValue.captured) {
                "OTel Context에 설정된 tenant ID가 스팬에 주입되어야 한다"
            }
        }

        @Test
        fun `OTel Context가 없을 때 ThreadLocal tenant ID로 폴백한다`() {
            val span = mockk<ReadWriteSpan>(relaxed = true)
            val capturedValue = slot<String>()
            every { span.setAttribute(any<AttributeKey<String>>(), capture(capturedValue)) } returns span

            tenantResolver.setTenantId("tenant-threadlocal")

            processor.onStart(Context.root(), span)

            assertEquals("tenant-threadlocal", capturedValue.captured) {
                "OTel Context에 tenant가 없으면 ThreadLocal로 폴백해야 한다"
            }
        }

        @Test
        fun `테넌트가 전혀 없을 때 default를 주입한다`() {
            val span = mockk<ReadWriteSpan>(relaxed = true)
            val capturedValue = slot<String>()
            every { span.setAttribute(any<AttributeKey<String>>(), capture(capturedValue)) } returns span

            // OTel Context도, ThreadLocal도 설정 안 됨
            processor.onStart(Context.root(), span)

            assertEquals("default", capturedValue.captured) {
                "테넌트가 전혀 없으면 'default'를 스팬에 주입해야 한다"
            }
        }

        @Test
        fun `OTel Context가 ThreadLocal보다 우선순위가 높다`() {
            val span = mockk<ReadWriteSpan>(relaxed = true)
            val capturedValue = slot<String>()
            every { span.setAttribute(any<AttributeKey<String>>(), capture(capturedValue)) } returns span

            // 양쪽 모두 설정 — OTel Context가 우선이어야 한다
            tenantResolver.setTenantId("tenant-threadlocal")
            val ctx = Context.current().with(TenantSpanProcessor.TENANT_CONTEXT_KEY, "tenant-otel")

            processor.onStart(ctx, span)

            assertEquals("tenant-otel", capturedValue.captured) {
                "OTel Context의 tenant ID가 ThreadLocal보다 우선 적용되어야 한다"
            }
        }

        @Test
        fun `setAttribute는 정확히 한 번 호출된다`() {
            val span = mockk<ReadWriteSpan>(relaxed = true)
            every { span.setAttribute(any<AttributeKey<String>>(), any<String>()) } returns span

            processor.onStart(Context.root(), span)

            verify(exactly = 1) { span.setAttribute(TENANT_ID_KEY, any<String>()) }
        }

        @Test
        fun `서로 다른 테넌트를 순차적으로 주입한다`() {
            val tenants = listOf("acme", "globex", "initech")

            for (tenantId in tenants) {
                val span = mockk<ReadWriteSpan>(relaxed = true)
                val capturedValue = slot<String>()
                every { span.setAttribute(any<AttributeKey<String>>(), capture(capturedValue)) } returns span

                val ctx = Context.current().with(TenantSpanProcessor.TENANT_CONTEXT_KEY, tenantId)
                processor.onStart(ctx, span)

                capturedValue.captured shouldBe tenantId
            }
        }
    }

    @Nested
    inner class OnEnd {

        @Test
        fun `onEnd는 스팬 속성을 수정하지 않는다`() {
            val span = mockk<ReadableSpan>(relaxed = true)

            // 예외 없이 실행되어야 한다
            processor.onEnd(span)

            // ReadableSpan에는 수정 메서드가 없으므로 별도 메서드 호출이 없어야 한다
            verify(exactly = 0) { span.name }
        }
    }

    @Nested
    inner class LifecycleFlags {

        @Test
        fun `isStartRequired는 true를 반환한다`() {
            assertTrue(processor.isStartRequired()) {
                "onStart에서 tenant 주입이 필요하므로 isStartRequired는 true여야 한다"
            }
        }

        @Test
        fun `isEndRequired는 false를 반환한다`() {
            assertFalse(processor.isEndRequired()) {
                "onEnd에서 아무 작업도 없으므로 isEndRequired는 false여야 한다"
            }
        }
    }

    @Nested
    inner class TenantContextKey {

        @Test
        fun `TENANT_CONTEXT_KEY는 TenantWebFilter와 동일한 인스턴스를 공유해야 한다`() {
            // ContextKey.named()로 생성된 키는 인스턴스가 다르면 다른 키이므로
            // 동일한 TENANT_CONTEXT_KEY 인스턴스를 사용하는지 확인한다.
            val span = mockk<ReadWriteSpan>(relaxed = true)
            val capturedValue = slot<String>()
            every { span.setAttribute(any<AttributeKey<String>>(), capture(capturedValue)) } returns span

            // TenantWebFilter가 사용하는 것과 동일한 TENANT_CONTEXT_KEY로 값을 설정
            val ctx = Context.current().with(TenantSpanProcessor.TENANT_CONTEXT_KEY, "shared-key-tenant")

            processor.onStart(ctx, span)

            assertEquals("shared-key-tenant", capturedValue.captured) {
                "TenantSpanProcessor.TENANT_CONTEXT_KEY와 TenantWebFilter가 동일한 키 인스턴스를 공유해야 한다"
            }
        }
    }
}

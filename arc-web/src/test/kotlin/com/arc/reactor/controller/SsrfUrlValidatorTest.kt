package com.arc.reactor.controller

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * 에 대한 단위 테스트. [SsrfUrlValidator].
 *
 * R293: [SsrfUrlValidator.validate]가 suspend로 전환되어 모든 호출은 [runBlocking]
 * 으로 래핑한다. blocking DNS resolution은 실제 IO 디스패처로 격리되어 production
 * 코드에서는 Reactor Netty 이벤트 루프 차단 위험이 사라진다.
 */
class SsrfUrlValidatorTest {

    @Nested
    inner class SchemeValidation {

        @Test
        fun `allow http scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://example.com/sse")
            assertNull(result) { "http scheme should be allowed" }
        }

        @Test
        fun `allow https scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("https://example.com/sse")
            assertNull(result) { "https scheme should be allowed" }
        }

        @Test
        fun `reject file scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("file:///etc/passwd")
            assertNotNull(result) { "file:// scheme should be rejected" }
            assertTrue(result!!.contains("not allowed")) { "Error should mention scheme not allowed" }
        }

        @Test
        fun `reject gopher scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("gopher://evil.com/")
            assertNotNull(result) { "gopher:// scheme should be rejected" }
        }

        @Test
        fun `reject dict scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("dict://evil.com/")
            assertNotNull(result) { "dict:// scheme should be rejected" }
        }

        @Test
        fun `reject ftp scheme해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("ftp://evil.com/file")
            assertNotNull(result) { "ftp:// scheme should be rejected" }
        }
    }

    @Nested
    inner class PrivateIpValidation {

        @Test
        fun `reject 10_0_0_0 slash 8 range해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://10.0.0.1:8080/sse")
            assertNotNull(result) { "10.x.x.x should be rejected" }
        }

        @Test
        fun `reject 10_255_255_255해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://10.255.255.255/sse")
            assertNotNull(result) { "10.255.255.255 should be rejected" }
        }

        @Test
        fun `reject 172_16_0_0 slash 12 range해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://172.16.0.1/sse")
            assertNotNull(result) { "172.16.x.x should be rejected" }
        }

        @Test
        fun `reject 172_31_255_255해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://172.31.255.255/sse")
            assertNotNull(result) { "172.31.x.x should be rejected" }
        }

        @Test
        fun `allow 172_15_0_1 outside private range해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://172.15.0.1/sse")
            // 172.15.x.x is not in the 172.16-31 range, so은(는) be allowed해야 합니다
            assertNull(result) { "172.15.0.1 should be allowed (outside /12 range)" }
        }

        @Test
        fun `reject 192_168_0_0 slash 16 range해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://192.168.1.100/sse")
            assertNotNull(result) { "192.168.x.x should be rejected" }
        }

        @Test
        fun `reject 127_0_0_1 loopback해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://127.0.0.1:8080/sse")
            assertNotNull(result) { "127.0.0.1 should be rejected" }
        }

        @Test
        fun `reject 169_254 link-local해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http://169.254.169.254/latest/meta-data/")
            assertNotNull(result) { "169.254.x.x should be rejected (cloud metadata)" }
        }
    }

    @Nested
    inner class HostValidation {

        @Test
        fun `reject malformed url해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("not-a-url")
            assertNotNull(result) { "Malformed URL should be rejected" }
        }

        @Test
        fun `reject url without host해야 한다`() = runBlocking {
            val result = SsrfUrlValidator.validate("http:///path")
            assertNotNull(result) { "URL without host should be rejected" }
        }
    }

    @Nested
    inner class IsPrivateOrReservedDirectTests {

        @Test
        fun `detect IPv6 loopback해야 한다`() {
            val loopback = InetAddress.getByName("::1")
            assertTrue(SsrfUrlValidator.isPrivateOrReserved(loopback)) {
                "IPv6 loopback ::1 should be detected as private"
            }
        }

        @Test
        fun `detect 0_0_0_0해야 한다`() {
            val addr = InetAddress.getByName("0.0.0.0")
            assertTrue(SsrfUrlValidator.isPrivateOrReserved(addr)) {
                "0.0.0.0 should be detected as reserved"
            }
        }
    }
}

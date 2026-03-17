package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * McpAdminHmacSupport에 대한 테스트.
 *
 * HMAC-SHA256 서명·설정 파싱·정규화 로직을 검증합니다.
 */
class McpAdminHmacSupportTest {

    companion object {
        private val HEX_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }

    // ── McpAdminHmacSettings ────────────────────────────────────────────

    @Nested
    inner class HmacSettingsFrom {

        @Test
        fun `빈 config에서 기본값을 반환한다`() {
            val settings = McpAdminHmacSettings.from(emptyMap())

            assertFalse(settings.required) { "빈 config에서 required는 false여야 한다" }
            assertNull(settings.secret) { "빈 config에서 secret은 null이어야 한다" }
            assertEquals(300L, settings.windowSeconds) { "빈 config에서 windowSeconds는 기본 300이어야 한다" }
        }

        @Test
        fun `required가 true이면 true를 반환한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to true)
            )

            assertTrue(settings.required) { "Boolean true는 required=true여야 한다" }
        }

        @Test
        fun `required 문자열 yes를 true로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "yes")
            )

            assertTrue(settings.required) { "문자열 'yes'는 required=true여야 한다" }
        }

        @Test
        fun `required 문자열 1을 true로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "1")
            )

            assertTrue(settings.required) { "문자열 '1'은 required=true여야 한다" }
        }

        @Test
        fun `required 문자열 on을 true로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "on")
            )

            assertTrue(settings.required) { "문자열 'on'은 required=true여야 한다" }
        }

        @Test
        fun `required 문자열 false를 false로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "false")
            )

            assertFalse(settings.required) { "문자열 'false'는 required=false여야 한다" }
        }

        @Test
        fun `required 문자열 off를 false로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "off")
            )

            assertFalse(settings.required) { "문자열 'off'는 required=false여야 한다" }
        }

        @Test
        fun `required 문자열 no를 false로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "no")
            )

            assertFalse(settings.required) { "문자열 'no'는 required=false여야 한다" }
        }

        @Test
        fun `required 숫자 1을 true로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to 1)
            )

            assertTrue(settings.required) { "숫자 1은 required=true여야 한다" }
        }

        @Test
        fun `required 숫자 0을 false로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to 0)
            )

            assertFalse(settings.required) { "숫자 0은 required=false여야 한다" }
        }

        @Test
        fun `required 인식 불가 문자열에서 기본값 false를 반환한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacRequired" to "maybe")
            )

            assertFalse(settings.required) { "인식 불가 문자열은 기본값 false여야 한다" }
        }

        @Test
        fun `secret을 올바르게 추출한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacSecret" to "my-secret-key")
            )

            assertEquals("my-secret-key", settings.secret) { "secret이 정확히 추출되어야 한다" }
        }

        @Test
        fun `secret 공백을 트림하고 비어있으면 null로 반환한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacSecret" to "   ")
            )

            assertNull(settings.secret) { "공백만 있는 secret은 null이어야 한다" }
        }

        @Test
        fun `secret 빈 문자열을 null로 반환한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacSecret" to "")
            )

            assertNull(settings.secret) { "빈 문자열 secret은 null이어야 한다" }
        }

        @Test
        fun `secret 앞뒤 공백을 트림한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacSecret" to "  trimmed-secret  ")
            )

            assertEquals("trimmed-secret", settings.secret) {
                "secret 앞뒤 공백이 제거되어야 한다"
            }
        }

        @Test
        fun `windowSeconds를 올바르게 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacWindowSeconds" to 600)
            )

            assertEquals(600L, settings.windowSeconds) { "windowSeconds가 600이어야 한다" }
        }

        @Test
        fun `windowSeconds 문자열을 Long으로 해석한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacWindowSeconds" to "120")
            )

            assertEquals(120L, settings.windowSeconds) { "문자열 '120'이 Long 120으로 해석되어야 한다" }
        }

        @Test
        fun `windowSeconds 0 이하를 1로 보정한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacWindowSeconds" to 0)
            )

            assertEquals(1L, settings.windowSeconds) { "windowSeconds 0은 최소값 1로 보정되어야 한다" }
        }

        @Test
        fun `windowSeconds 음수를 1로 보정한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacWindowSeconds" to -100)
            )

            assertEquals(1L, settings.windowSeconds) { "음수 windowSeconds는 최소값 1로 보정되어야 한다" }
        }

        @Test
        fun `windowSeconds 유효하지 않은 문자열에서 기본값 300을 반환한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf("adminHmacWindowSeconds" to "invalid")
            )

            assertEquals(300L, settings.windowSeconds) {
                "유효하지 않은 문자열에서 windowSeconds 기본값 300이어야 한다"
            }
        }

        @Test
        fun `모든 필드가 있는 config를 올바르게 파싱한다`() {
            val settings = McpAdminHmacSettings.from(
                mapOf(
                    "adminHmacRequired" to true,
                    "adminHmacSecret" to "test-secret",
                    "adminHmacWindowSeconds" to 60
                )
            )

            assertTrue(settings.required) { "required가 true여야 한다" }
            assertEquals("test-secret", settings.secret) { "secret이 정확해야 한다" }
            assertEquals(60L, settings.windowSeconds) { "windowSeconds가 60이어야 한다" }
        }
    }

    @Nested
    inner class HmacSettingsIsEnabled {

        @Test
        fun `secret이 있으면 isEnabled는 true를 반환한다`() {
            val settings = McpAdminHmacSettings(
                required = false,
                secret = "some-secret",
                windowSeconds = 300L
            )

            assertTrue(settings.isEnabled()) { "secret이 있으면 isEnabled=true여야 한다" }
        }

        @Test
        fun `secret이 null이면 isEnabled는 false를 반환한다`() {
            val settings = McpAdminHmacSettings(
                required = true,
                secret = null,
                windowSeconds = 300L
            )

            assertFalse(settings.isEnabled()) { "secret이 null이면 isEnabled=false여야 한다" }
        }

        @Test
        fun `secret이 빈 문자열이면 isEnabled는 false를 반환한다`() {
            val settings = McpAdminHmacSettings(
                required = true,
                secret = "",
                windowSeconds = 300L
            )

            assertFalse(settings.isEnabled()) { "secret이 빈 문자열이면 isEnabled=false여야 한다" }
        }

        @Test
        fun `secret이 공백만이면 isEnabled는 false를 반환한다`() {
            val settings = McpAdminHmacSettings(
                required = true,
                secret = "   ",
                windowSeconds = 300L
            )

            assertFalse(settings.isEnabled()) { "secret이 공백만이면 isEnabled=false여야 한다" }
        }
    }

    // ── McpAdminRequestSigner ───────────────────────────────────────────

    @Nested
    inner class RequestSignerSign {

        private val secret = "test-secret"
        private val fixedMillis = 1_700_000_000_000L // 2023-11-14T22:13:20Z

        @Test
        fun `sign은 올바른 timestamp를 반환한다`() {
            val result = McpAdminRequestSigner.sign(
                method = "GET",
                path = "/admin/preflight",
                query = "",
                body = "",
                secret = secret,
                nowMillis = fixedMillis
            )

            assertEquals(
                (fixedMillis / 1000).toString(),
                result.timestamp
            ) { "timestamp는 epoch seconds여야 한다" }
        }

        @Test
        fun `sign은 동일 입력에 대해 동일 signature를 반환한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/admin/policy",
                query = "serverId=abc",
                body = """{"enabled": true}""",
                secret = secret,
                nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/admin/policy",
                query = "serverId=abc",
                body = """{"enabled": true}""",
                secret = secret,
                nowMillis = fixedMillis
            )

            assertEquals(result1.signature, result2.signature) {
                "동일 입력에 대해 signature가 일치해야 한다"
            }
        }

        @Test
        fun `다른 secret으로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = "secret-a", nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = "secret-b", nowMillis = fixedMillis
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 secret은 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `다른 method로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "POST", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 HTTP method는 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `다른 path로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "GET", path = "/admin/a", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "GET", path = "/admin/b", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 path는 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `다른 body로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "POST", path = "/test", query = "", body = "body-a",
                secret = secret, nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "POST", path = "/test", query = "", body = "body-b",
                secret = secret, nowMillis = fixedMillis
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 body는 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `다른 query로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "a=1", body = "",
                secret = secret, nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "b=2", body = "",
                secret = secret, nowMillis = fixedMillis
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 query는 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `다른 timestamp로 서명하면 다른 signature를 생성한다`() {
            val result1 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )
            val result2 = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis + 1000
            )

            assertNotEquals(result1.signature, result2.signature) {
                "다른 timestamp는 다른 signature를 생성해야 한다"
            }
        }

        @Test
        fun `method를 대문자로 정규화한다`() {
            val lower = McpAdminRequestSigner.sign(
                method = "get", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )
            val upper = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )

            assertEquals(lower.signature, upper.signature) {
                "method는 대소문자 무관하게 동일 signature여야 한다"
            }
        }

        @Test
        fun `signature가 hex 문자열이다`() {
            val result = McpAdminRequestSigner.sign(
                method = "GET", path = "/test", query = "", body = "",
                secret = secret, nowMillis = fixedMillis
            )

            assertTrue(result.signature.matches(HEX_SHA256_PATTERN)) {
                "HMAC-SHA256 signature는 64자 hex 문자열이어야 한다: ${result.signature}"
            }
        }

        @Test
        fun `독립 HMAC-SHA256 구현과 동일한 결과를 생성한다`() {
            val method = "POST"
            val path = "/admin/policy"
            val query = "serverId=test-mcp"
            val body = """{"allowedTools":["read_file"]}"""
            val timestamp = (fixedMillis / 1000).toString()

            // 독립적으로 expected signature 계산
            val bodyHash = sha256Hex(body)
            val canonical = listOf(method, path, query, timestamp, bodyHash).joinToString("\n")
            val expectedSignature = hmacSha256(secret, canonical)

            val result = McpAdminRequestSigner.sign(
                method = method,
                path = path,
                query = query,
                body = body,
                secret = secret,
                nowMillis = fixedMillis
            )

            assertEquals(expectedSignature, result.signature) {
                "독립 HMAC-SHA256 계산과 동일한 signature여야 한다"
            }
        }
    }

    @Nested
    inner class BuildCanonicalString {

        @Test
        fun `정규화 문자열 형식이 올바르다`() {
            val canonical = McpAdminRequestSigner.buildCanonicalString(
                method = "POST",
                path = "/admin/policy",
                query = "serverId=abc",
                timestamp = "1700000000",
                body = "hello"
            )

            val lines = canonical.split("\n")
            assertEquals(5, lines.size) { "정규화 문자열은 5줄이어야 한다" }
            assertEquals("POST", lines[0]) { "첫 줄은 HTTP method여야 한다" }
            assertEquals("/admin/policy", lines[1]) { "둘째 줄은 path여야 한다" }
            assertEquals("serverId=abc", lines[2]) { "셋째 줄은 query여야 한다" }
            assertEquals("1700000000", lines[3]) { "넷째 줄은 timestamp여야 한다" }
            assertEquals(sha256Hex("hello"), lines[4]) { "다섯째 줄은 body의 SHA-256 해시여야 한다" }
        }

        @Test
        fun `빈 body에서 빈 문자열의 SHA-256 해시를 사용한다`() {
            val canonical = McpAdminRequestSigner.buildCanonicalString(
                method = "GET",
                path = "/test",
                query = "",
                timestamp = "1700000000",
                body = ""
            )

            val lines = canonical.split("\n")
            assertEquals(sha256Hex(""), lines[4]) {
                "빈 body는 빈 문자열의 SHA-256 해시여야 한다"
            }
        }

        @Test
        fun `빈 query를 빈 문자열로 처리한다`() {
            val canonical = McpAdminRequestSigner.buildCanonicalString(
                method = "GET",
                path = "/test",
                query = "",
                timestamp = "1700000000",
                body = ""
            )

            val lines = canonical.split("\n")
            assertEquals("", lines[2]) { "빈 query는 빈 문자열이어야 한다" }
        }

        @Test
        fun `method를 대문자로 정규화한다`() {
            val canonical = McpAdminRequestSigner.buildCanonicalString(
                method = "post",
                path = "/test",
                query = "",
                timestamp = "1700000000",
                body = ""
            )

            val lines = canonical.split("\n")
            assertEquals("POST", lines[0]) { "method는 대문자로 정규화되어야 한다" }
        }
    }

    @Nested
    inner class EdgeCases {

        private val secret = "edge-secret"
        private val fixedMillis = 1_700_000_000_000L

        @Test
        fun `특수문자가 포함된 path를 올바르게 처리한다`() {
            val result = McpAdminRequestSigner.sign(
                method = "GET",
                path = "/admin/servers/my-server%20name/policy",
                query = "",
                body = "",
                secret = secret,
                nowMillis = fixedMillis
            )

            assertTrue(result.signature.matches(HEX_SHA256_PATTERN)) {
                "특수문자 path에서도 유효한 hex signature여야 한다"
            }
        }

        @Test
        fun `한글이 포함된 body를 UTF-8로 처리한다`() {
            val koreanBody = """{"message":"안녕하세요"}"""

            val result = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/test",
                query = "",
                body = koreanBody,
                secret = secret,
                nowMillis = fixedMillis
            )

            // 독립 검증
            val expectedSignature = computeExpectedSignature(
                method = "POST",
                path = "/test",
                query = "",
                body = koreanBody,
                secret = secret,
                timestamp = (fixedMillis / 1000).toString()
            )

            assertEquals(expectedSignature, result.signature) {
                "한글 body에서도 독립 계산과 동일한 signature여야 한다"
            }
        }

        @Test
        fun `이모지가 포함된 body를 올바르게 처리한다`() {
            val emojiBody = """{"emoji":"🚀🔥"}"""

            val result = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/test",
                query = "",
                body = emojiBody,
                secret = secret,
                nowMillis = fixedMillis
            )

            assertTrue(result.signature.matches(HEX_SHA256_PATTERN)) {
                "이모지 body에서도 유효한 hex signature여야 한다"
            }
        }

        @Test
        fun `큰 body를 올바르게 처리한다`() {
            val largeBody = "x".repeat(100_000)

            val result = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/test",
                query = "",
                body = largeBody,
                secret = secret,
                nowMillis = fixedMillis
            )

            assertTrue(result.signature.matches(HEX_SHA256_PATTERN)) {
                "큰 body에서도 유효한 hex signature여야 한다"
            }
        }

        @Test
        fun `복잡한 query string을 올바르게 처리한다`() {
            val result = McpAdminRequestSigner.sign(
                method = "GET",
                path = "/test",
                query = "a=1&b=hello%20world&c=true&d=",
                body = "",
                secret = secret,
                nowMillis = fixedMillis
            )

            assertTrue(result.signature.matches(HEX_SHA256_PATTERN)) {
                "복잡한 query에서도 유효한 hex signature여야 한다"
            }
        }

        @Test
        fun `개행이 포함된 body를 올바르게 처리한다`() {
            val bodyWithNewlines = "line1\nline2\nline3"

            val result = McpAdminRequestSigner.sign(
                method = "POST",
                path = "/test",
                query = "",
                body = bodyWithNewlines,
                secret = secret,
                nowMillis = fixedMillis
            )

            val expectedSignature = computeExpectedSignature(
                method = "POST",
                path = "/test",
                query = "",
                body = bodyWithNewlines,
                secret = secret,
                timestamp = (fixedMillis / 1000).toString()
            )

            assertEquals(expectedSignature, result.signature) {
                "개행이 포함된 body에서도 독립 계산과 동일한 signature여야 한다"
            }
        }
    }

    // ── 테스트 헬퍼 ─────────────────────────────────────────────────────

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun computeExpectedSignature(
        method: String,
        path: String,
        query: String,
        body: String,
        secret: String,
        timestamp: String
    ): String {
        val bodyHash = sha256Hex(body)
        val canonical = listOf(method.uppercase(), path, query, timestamp, bodyHash).joinToString("\n")
        return hmacSha256(secret, canonical)
    }
}

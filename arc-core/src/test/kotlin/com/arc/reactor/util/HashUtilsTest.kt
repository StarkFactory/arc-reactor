package com.arc.reactor.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * HashUtils에 대한 테스트.
 *
 * SHA-256 해싱, HMAC-SHA256, hex 변환의 정확성과 일관성을 검증한다.
 */
class HashUtilsTest {

    @Nested
    inner class Sha256HexTests {

        @Test
        fun `sha256Hex는 빈 문자열에 대해 알려진 SHA-256 해시를 반환해야 한다`() {
            val result = HashUtils.sha256Hex("")
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result
            ) { "빈 문자열의 SHA-256 해시가 RFC 표준값과 일치해야 한다" }
        }

        @Test
        fun `sha256Hex는 알려진 입력에 대해 올바른 해시를 반환해야 한다`() {
            val result = HashUtils.sha256Hex("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                result
            ) { "'hello' 문자열의 SHA-256 해시가 표준값과 일치해야 한다" }
        }

        @Test
        fun `sha256Hex는 동일 입력에 대해 동일 결과를 반환해야 한다`() {
            val input = "arc-reactor-test-input"
            val first = HashUtils.sha256Hex(input)
            val second = HashUtils.sha256Hex(input)
            assertEquals(first, second) { "동일 입력에 대해 sha256Hex는 항상 동일한 결과를 반환해야 한다" }
        }

        @Test
        fun `sha256Hex는 서로 다른 입력에 대해 서로 다른 해시를 반환해야 한다`() {
            val hashA = HashUtils.sha256Hex("inputA")
            val hashB = HashUtils.sha256Hex("inputB")
            assertNotEquals(hashA, hashB) { "서로 다른 입력은 서로 다른 SHA-256 해시를 가져야 한다" }
        }

        @Test
        fun `sha256Hex는 항상 64자 소문자 hex 문자열을 반환해야 한다`() {
            val result = HashUtils.sha256Hex("임의의 UTF-8 입력 テスト")
            assertEquals(64, result.length) { "SHA-256 해시는 항상 64자여야 한다" }
            assertTrue(result.all { it.isDigit() || it in 'a'..'f' }) { "SHA-256 hex 결과는 소문자 16진수 문자만 포함해야 한다" }
        }

        @Test
        fun `sha256Hex는 연속 호출 후에도 ThreadLocal digest를 올바르게 재설정해야 한다`() {
            // digest.reset()이 제대로 호출되는지 검증 (ThreadLocal 재사용 안전성)
            val expected = HashUtils.sha256Hex("consistency-check")
            repeat(5) { i ->
                val result = HashUtils.sha256Hex("consistency-check")
                assertEquals(expected, result) { "반복 ${i + 1}번째 호출에서 digest reset이 올바르게 동작해야 한다" }
            }
        }

        @Test
        fun `sha256Hex는 유니코드 다국어 입력을 UTF-8로 올바르게 처리해야 한다`() {
            val unicode = "한글입력テスト🚀"
            val result = HashUtils.sha256Hex(unicode)
            assertEquals(64, result.length) { "유니코드 입력에 대한 SHA-256 결과도 64자여야 한다" }
            // 동일 입력으로 재호출 시 결과 일관성 검증
            assertEquals(result, HashUtils.sha256Hex(unicode)) { "유니코드 입력에 대해 반복 호출 결과가 일치해야 한다" }
        }
    }

    @Nested
    inner class HmacSha256HexTests {

        private val secret = "test-secret".toByteArray(Charsets.UTF_8)

        @Test
        fun `hmacSha256Hex는 알려진 키와 데이터에 대해 올바른 HMAC을 반환해야 한다`() {
            // RFC 4231 또는 표준 HMAC-SHA256 벡터 기반 검증
            val key = "key".toByteArray(Charsets.UTF_8)
            val result = HashUtils.hmacSha256Hex(key, "The quick brown fox jumps over the lazy dog")
            assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                result
            ) { "알려진 HMAC-SHA256 벡터와 결과가 일치해야 한다" }
        }

        @Test
        fun `hmacSha256Hex는 동일 키와 데이터에 대해 항상 동일한 결과를 반환해야 한다`() {
            val first = HashUtils.hmacSha256Hex(secret, "data")
            val second = HashUtils.hmacSha256Hex(secret, "data")
            assertEquals(first, second) { "동일 시크릿/데이터 조합에서 HMAC 결과가 항상 동일해야 한다" }
        }

        @Test
        fun `hmacSha256Hex는 데이터가 달라지면 다른 결과를 반환해야 한다`() {
            val hmac1 = HashUtils.hmacSha256Hex(secret, "payload-A")
            val hmac2 = HashUtils.hmacSha256Hex(secret, "payload-B")
            assertNotEquals(hmac1, hmac2) { "데이터가 다른 경우 HMAC 결과가 달라야 한다" }
        }

        @Test
        fun `hmacSha256Hex는 키가 달라지면 다른 결과를 반환해야 한다`() {
            val key1 = "secret-1".toByteArray(Charsets.UTF_8)
            val key2 = "secret-2".toByteArray(Charsets.UTF_8)
            val hmac1 = HashUtils.hmacSha256Hex(key1, "same-data")
            val hmac2 = HashUtils.hmacSha256Hex(key2, "same-data")
            assertNotEquals(hmac1, hmac2) { "키가 다른 경우 동일 데이터에 대한 HMAC 결과도 달라야 한다" }
        }

        @Test
        fun `hmacSha256Hex는 항상 64자 소문자 hex 문자열을 반환해야 한다`() {
            val result = HashUtils.hmacSha256Hex(secret, "output-format-check")
            assertEquals(64, result.length) { "HMAC-SHA256 결과는 항상 64자여야 한다" }
            assertTrue(result.all { it.isDigit() || it in 'a'..'f' }) { "HMAC-SHA256 hex 결과는 소문자 16진수 문자만 포함해야 한다" }
        }

        @Test
        fun `hmacSha256Hex는 빈 데이터에 대해서도 올바른 형식의 HMAC을 반환해야 한다`() {
            val result = HashUtils.hmacSha256Hex(secret, "")
            assertEquals(64, result.length) { "빈 데이터의 HMAC-SHA256 결과도 64자여야 한다" }
        }
    }

    @Nested
    inner class BytesToHexTests {

        @Test
        fun `bytesToHex는 빈 배열에 대해 빈 문자열을 반환해야 한다`() {
            val result = HashUtils.bytesToHex(byteArrayOf())
            assertEquals("", result) { "빈 바이트 배열은 빈 hex 문자열로 변환되어야 한다" }
        }

        @Test
        fun `bytesToHex는 단일 0x00 바이트를 00으로 변환해야 한다`() {
            val result = HashUtils.bytesToHex(byteArrayOf(0x00))
            assertEquals("00", result) { "0x00 바이트는 '00' 문자열로 변환되어야 한다" }
        }

        @Test
        fun `bytesToHex는 단일 0xFF 바이트를 ff로 변환해야 한다`() {
            val result = HashUtils.bytesToHex(byteArrayOf(0xFF.toByte()))
            assertEquals("ff", result) { "0xFF 바이트는 소문자 'ff'로 변환되어야 한다" }
        }

        @Test
        fun `bytesToHex는 알려진 바이트 배열을 정확한 hex 문자열로 변환해야 한다`() {
            val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            val result = HashUtils.bytesToHex(bytes)
            assertEquals("deadbeef", result) { "0xDEADBEEF 바이트 배열은 'deadbeef' 문자열로 변환되어야 한다" }
        }

        @Test
        fun `bytesToHex는 결과 문자열 길이가 입력 배열 크기의 2배여야 한다`() {
            val bytes = ByteArray(32) { it.toByte() }
            val result = HashUtils.bytesToHex(bytes)
            assertEquals(64, result.length) { "32바이트 배열의 hex 변환 결과는 64자여야 한다" }
        }

        @Test
        fun `bytesToHex는 상위 니블과 하위 니블을 모두 올바르게 인코딩해야 한다`() {
            // 0x0F: 상위 니블 0, 하위 니블 F → "0f"
            // 0xF0: 상위 니블 F, 하위 니블 0 → "f0"
            val result = HashUtils.bytesToHex(byteArrayOf(0x0F.toByte(), 0xF0.toByte()))
            assertEquals("0ff0", result) { "0x0F, 0xF0 바이트 쌍의 니블 인코딩이 정확해야 한다" }
        }

        @Test
        fun `bytesToHex는 소문자 hex 문자만 출력해야 한다`() {
            val bytes = ByteArray(16) { (0xAA + it).toByte() }
            val result = HashUtils.bytesToHex(bytes)
            assertTrue(result.all { it.isDigit() || it in 'a'..'f' }) { "bytesToHex 출력은 소문자 hex 문자(0-9, a-f)만 포함해야 한다" }
        }
    }
}

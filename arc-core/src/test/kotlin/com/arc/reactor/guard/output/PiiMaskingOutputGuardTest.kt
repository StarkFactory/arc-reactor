package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PII 마스킹 출력 가드에 대한 테스트.
 *
 * 개인정보 마스킹 동작을 검증합니다.
 */
class PiiMaskingOutputGuardTest {

    private lateinit var guard: PiiMaskingOutputGuard

    private val defaultContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello"),
        toolsUsed = emptyList(),
        durationMs = 100
    )

    @BeforeEach
    fun setup() {
        guard = PiiMaskingOutputGuard()
    }

    @Nested
    inner class NoPii {

        @Test
        fun `content returns Allowed를 정리한다`() = runTest {
            val result = guard.check("This is a normal response.", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Content without PII should be Allowed"
            }
        }

        @Test
        fun `비어있는 content returns Allowed`() = runTest {
            val result = guard.check("", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Empty content should be Allowed"
            }
        }
    }

    @Nested
    inner class KoreanResidentRegistrationNumber {

        @Test
        fun `resident registration number with dash를 마스킹한다`() = runTest {
            val content = "주민번호는 950101-1234567 입니다."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask RRN"
            }
            assertFalse(modified.content.contains("950101-1234567")) {
                "RRN should be masked"
            }
            assertTrue(modified.content.contains("******-*******")) {
                "Should contain masked RRN"
            }
            assertTrue(modified.reason.contains("주민등록번호")) {
                "Reason should mention RRN type"
            }
        }

        @Test
        fun `resident registration number with space around dash를 마스킹한다`() = runTest {
            val content = "번호: 880515 - 2987654"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask RRN with spaces"
            }
            assertFalse(modified.content.contains("880515")) {
                "RRN should be masked even with spaces"
            }
        }
    }

    @Nested
    inner class PhoneNumber {

        @Test
        fun `phone number with dashes를 마스킹한다`() = runTest {
            val content = "연락처: 010-1234-5678"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask phone number"
            }
            assertFalse(modified.content.contains("010-1234-5678")) {
                "Phone number should be masked"
            }
            assertTrue(modified.content.contains("***-****-****")) {
                "Should contain masked phone"
            }
        }

        @Test
        fun `phone number without dashes를 마스킹한다`() = runTest {
            val content = "전화: 01012345678"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask phone without dashes"
            }
            assertFalse(modified.content.contains("01012345678")) {
                "Phone number should be masked"
            }
        }

        @Test
        fun `various Korean mobile prefixes를 마스킹한다`() = runTest {
            for (prefix in listOf("010", "011", "016", "017", "018", "019")) {
                val content = "번호: ${prefix}-9999-8888"
                val result = guard.check(content, defaultContext)
                assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                    "Should mask phone with prefix $prefix"
                }
            }
        }
    }

    @Nested
    inner class CreditCard {

        @Test
        fun `credit card with dashes를 마스킹한다`() = runTest {
            val content = "카드: 1234-5678-9012-3456"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask credit card"
            }
            assertFalse(modified.content.contains("1234-5678-9012-3456")) {
                "Card number should be masked"
            }
            assertTrue(modified.content.contains("****-****-****-****")) {
                "Should contain masked card"
            }
        }

        @Test
        fun `credit card with spaces를 마스킹한다`() = runTest {
            val content = "카드번호 1234 5678 9012 3456 확인"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask credit card with spaces"
            }
        }

        @Test
        fun `credit card without separators를 마스킹한다`() = runTest {
            val content = "카드 1234567890123456"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask credit card without separators"
            }
        }
    }

    @Nested
    inner class Email {

        @Test
        fun `email address를 마스킹한다`() = runTest {
            val content = "이메일: user@example.com"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask email"
            }
            assertFalse(modified.content.contains("user@example.com")) {
                "Email should be masked"
            }
            assertTrue(modified.content.contains("***@***.***")) {
                "Should contain masked email"
            }
        }

        @Test
        fun `email with dots and hyphens를 마스킹한다`() = runTest {
            val content = "메일: john.doe-work@my-company.co.kr"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask email with special chars"
            }
        }
    }

    @Nested
    inner class KoreanDriverLicense {

        @Test
        fun `운전면허번호를 마스킹한다`() = runTest {
            val content = "면허번호: 11-23-123456-78"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask driver license"
            }
            assertFalse(modified.content.contains("11-23-123456-78")) {
                "Driver license should be masked"
            }
            assertTrue(modified.reason.contains("운전면허번호")) {
                "Reason should mention driver license type"
            }
        }
    }

    @Nested
    inner class KoreanPassport {

        @Test
        fun `여권번호를 마스킹한다`() = runTest {
            val content = "여권번호는 M12345678 입니다."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask passport number"
            }
            assertFalse(modified.content.contains("M12345678")) {
                "Passport number should be masked"
            }
        }
    }

    @Nested
    inner class UsSsn {

        @Test
        fun `US SSN을 마스킹한다`() = runTest {
            val content = "SSN is 123-45-6789."
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask US SSN"
            }
            assertFalse(modified.content.contains("123-45-6789")) {
                "US SSN should be masked"
            }
            assertTrue(modified.content.contains("***-**-****")) {
                "Should contain masked SSN format"
            }
        }
    }

    @Nested
    inner class JpMyNumber {

        @Test
        fun `일본 마이넘버를 마스킹한다`() = runTest {
            val content = "マイナンバー: 1234 5678 9012"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask JP My Number"
            }
            assertFalse(modified.content.contains("1234 5678 9012")) {
                "JP My Number should be masked"
            }
        }
    }

    @Nested
    inner class Iban {

        @Test
        fun `IBAN을 마스킹한다`() = runTest {
            val content = "IBAN: DE89370400440532013000"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask IBAN"
            }
            assertFalse(modified.content.contains("DE89370400440532013000")) {
                "IBAN should be masked"
            }
        }

        @Test
        fun `공백 포함 IBAN을 마스킹한다`() = runTest {
            val content = "IBAN: GB29 NWBK 6016 1331 9268 19"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask IBAN with spaces"
            }
            assertFalse(modified.content.contains("GB29")) {
                "IBAN with spaces should be masked"
            }
        }
    }

    @Nested
    inner class IpAddress {

        @Test
        fun `IPv4 주소를 마스킹한다`() = runTest {
            val content = "서버 IP: 192.168.1.100"
            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask IPv4 address"
            }
            assertFalse(modified.content.contains("192.168.1.100")) {
                "IPv4 address should be masked"
            }
        }

        @Test
        fun `유효하지 않은 IP는 마스킹하지 않는다`() = runTest {
            val content = "버전 256.1.2.3은 유효하지 않습니다"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Invalid IP range (256) should not be masked"
            }
        }
    }

    @Nested
    inner class MultiplePii {

        @Test
        fun `multiple PII types in one response를 마스킹한다`() = runTest {
            val content = """
                고객 정보:
                - 주민번호: 950101-1234567
                - 전화: 010-1234-5678
                - 이메일: test@example.com
            """.trimIndent()

            val result = guard.check(content, defaultContext)
            val modified = assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask all PII types"
            }
            assertFalse(modified.content.contains("950101-1234567")) { "RRN should be masked" }
            assertFalse(modified.content.contains("010-1234-5678")) { "Phone should be masked" }
            assertFalse(modified.content.contains("test@example.com")) { "Email should be masked" }
            assertTrue(modified.reason.contains("주민등록번호")) { "Reason should include RRN" }
            assertTrue(modified.reason.contains("전화번호")) { "Reason should include phone" }
            assertTrue(modified.reason.contains("이메일")) { "Reason should include email" }
        }
    }

    @Nested
    inner class StageMetadata {

        @Test
        fun `stage name은(는) PiiMasking이다`() {
            assertEquals("PiiMasking", guard.stageName) { "Stage name should be PiiMasking" }
        }

        @Test
        fun `order은(는) 10이다`() {
            assertEquals(10, guard.order) { "Order should be 10" }
        }
    }
}

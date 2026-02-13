package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
        fun `clean content returns Allowed`() = runTest {
            val result = guard.check("This is a normal response.", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Content without PII should be Allowed"
            }
        }

        @Test
        fun `empty content returns Allowed`() = runTest {
            val result = guard.check("", defaultContext)
            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "Empty content should be Allowed"
            }
        }
    }

    @Nested
    inner class KoreanResidentRegistrationNumber {

        @Test
        fun `masks resident registration number with dash`() = runTest {
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
        fun `masks resident registration number with space around dash`() = runTest {
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
        fun `masks phone number with dashes`() = runTest {
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
        fun `masks phone number without dashes`() = runTest {
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
        fun `masks various Korean mobile prefixes`() = runTest {
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
        fun `masks credit card with dashes`() = runTest {
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
        fun `masks credit card with spaces`() = runTest {
            val content = "카드번호 1234 5678 9012 3456 확인"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask credit card with spaces"
            }
        }

        @Test
        fun `masks credit card without separators`() = runTest {
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
        fun `masks email address`() = runTest {
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
        fun `masks email with dots and hyphens`() = runTest {
            val content = "메일: john.doe-work@my-company.co.kr"
            val result = guard.check(content, defaultContext)
            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "Should mask email with special chars"
            }
        }
    }

    @Nested
    inner class MultiplePii {

        @Test
        fun `masks multiple PII types in one response`() = runTest {
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
        fun `stage name is PiiMasking`() {
            assertEquals("PiiMasking", guard.stageName) { "Stage name should be PiiMasking" }
        }

        @Test
        fun `order is 10`() {
            assertEquals(10, guard.order) { "Order should be 10" }
        }
    }
}

package com.arc.reactor.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [PiiPatterns] 정규식 패턴 탐지 및 마스킹 검증 테스트.
 *
 * 각 PII 패턴 그룹(KR, INTL, COMMON)과 ALL 목록의
 * 탐지 정확도(true positive / false negative / false positive)를 검증한다.
 */
class PiiPatternsTest {

    // ───────────────────────────────────────────────
    // KR 패턴 테스트
    // ───────────────────────────────────────────────

    @Nested
    inner class 주민등록번호 {

        private val pattern = PiiPatterns.KR.first { it.name == "주민등록번호" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "900101-1234567",
            "000101-3234567",
            "851231-2345678",
            "701010-4123456"
        ])
        fun `유효한 주민등록번호 형식을 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "주민등록번호 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `공백이 포함된 주민등록번호를 탐지한다`() {
            val input = "900101 - 1234567"
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "공백 포함 주민등록번호 '${input}'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("******-*******", pattern.mask) {
                "주민등록번호 마스크는 '******-*******' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "9001011234567",     // 구분자 없는 13자리 — 규칙상 비매칭
            "9001-01-1234567"    // 생년월일 구분 형식
        ])
        fun `잘못된 형식은 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "주민등록번호 패턴이 잘못된 형식 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class 전화번호 {

        private val pattern = PiiPatterns.KR.first { it.name == "전화번호" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "010-1234-5678",
            "011-123-4567",
            "016-9876-5432",
            "01012345678",
            "0101234567"
        ])
        fun `유효한 한국 휴대폰 번호를 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "전화번호 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("***-****-****", pattern.mask) {
                "전화번호 마스크는 '***-****-****' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "020-1234-5678",    // 02 지역번호 (휴대폰 아님)
            "031-123-4567"      // 031 지역번호 (휴대폰 아님)
        ])
        fun `한국 지역 번호는 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "전화번호 패턴이 지역번호 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class 운전면허번호 {

        private val pattern = PiiPatterns.KR.first { it.name == "운전면허번호" }

        @Test
        fun `유효한 운전면허번호를 탐지한다`() {
            val input = "11-23-123456-78"
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "운전면허번호 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("**-**-******-**", pattern.mask) {
                "운전면허번호 마스크는 '**-**-******-**' 이어야 한다"
            }
        }

        @Test
        fun `잘못된 구분자 형식은 탐지하지 않는다`() {
            val input = "11/23/123456/78"
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "운전면허번호 패턴이 슬래시 구분자 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class 한국여권번호 {

        private val pattern = PiiPatterns.KR.first { it.name == "한국여권번호" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = ["M12345678", "A98765432", "Z00000001"])
        fun `유효한 한국 여권번호를 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "한국여권번호 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("*********", pattern.mask) {
                "한국여권번호 마스크는 '*********' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "m12345678",     // 소문자 — \b[A-Z] 불일치
            "MM12345678",    // 영문 2자 + 숫자 8자 — 형식 불일치
            "M1234567"       // 숫자 7자 (1자 부족)
        ])
        fun `잘못된 여권번호 형식은 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "한국여권번호 패턴이 잘못된 형식 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    // ───────────────────────────────────────────────
    // INTL 패턴 테스트
    // ───────────────────────────────────────────────

    @Nested
    inner class IBAN {

        private val pattern = PiiPatterns.INTL.first { it.name == "IBAN" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "DE89370400440532013000",
            "GB29NWBK60161331926819",
            "FR7630006000011234567890189"
        ])
        fun `유효한 IBAN을 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "IBAN 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("[IBAN MASKED]", pattern.mask) {
                "IBAN 마스크는 '[IBAN MASKED]' 이어야 한다"
            }
        }
    }

    @Nested
    inner class USSSN {

        private val pattern = PiiPatterns.INTL.first { it.name == "US SSN" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = ["123-45-6789", "001-01-0001", "999-99-9999"])
        fun `유효한 US SSN을 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "US SSN 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("***-**-****", pattern.mask) {
                "US SSN 마스크는 '***-**-****' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "1234-56-789",   // 앞 4자리 (형식 불일치)
            "123456789"      // 구분자 없음
        ])
        fun `잘못된 SSN 형식은 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "US SSN 패턴이 잘못된 형식 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class JPMyNumber {

        private val pattern = PiiPatterns.INTL.first { it.name == "JP My Number" }

        @Test
        fun `유효한 일본 마이넘버를 탐지한다`() {
            val input = "1234 5678 9012"
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "JP My Number 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("**** **** ****", pattern.mask) {
                "JP My Number 마스크는 '**** **** ****' 이어야 한다"
            }
        }

        @Test
        fun `공백 없는 12자리 숫자는 탐지하지 않는다`() {
            val input = "123456789012"
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "JP My Number 패턴이 공백 없는 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    // ───────────────────────────────────────────────
    // COMMON 패턴 테스트
    // ───────────────────────────────────────────────

    @Nested
    inner class 신용카드번호 {

        private val pattern = PiiPatterns.COMMON.first { it.name == "신용카드번호" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "1234-5678-9012-3456",
            "1234 5678 9012 3456",
            "1234567890123456"
        ])
        fun `유효한 신용카드번호를 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "신용카드번호 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("****-****-****-****", pattern.mask) {
                "신용카드번호 마스크는 '****-****-****-****' 이어야 한다"
            }
        }

        @Test
        fun `15자리 숫자는 탐지하지 않는다`() {
            val input = "123456789012345"
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "신용카드번호 패턴이 15자리 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class 이메일 {

        private val pattern = PiiPatterns.COMMON.first { it.name == "이메일" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "user@example.com",
            "john.doe+tag@sub.domain.co.kr",
            "test_123@foo.io"
        ])
        fun `유효한 이메일 주소를 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "이메일 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("***@***.***", pattern.mask) {
                "이메일 마스크는 '***@***.***' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "notanemail",
            "@nodomain",
            "missingat.com"
        ])
        fun `이메일 형식이 아닌 문자열은 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "이메일 패턴이 잘못된 형식 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    @Nested
    inner class IP주소 {

        private val pattern = PiiPatterns.COMMON.first { it.name == "IP주소" }

        @ParameterizedTest(name = "탐지: {0}")
        @ValueSource(strings = [
            "192.168.1.100",
            "0.0.0.0",
            "255.255.255.255",
            "10.0.0.1"
        ])
        fun `유효한 IPv4 주소를 탐지한다`(input: String) {
            assertTrue(pattern.regex.containsMatchIn(input)) {
                "IP주소 패턴이 '$input'을 탐지해야 한다"
            }
        }

        @Test
        fun `마스크 문자열이 올바르다`() {
            assertEquals("***.***.***.***", pattern.mask) {
                "IP주소 마스크는 '***.***.***.***' 이어야 한다"
            }
        }

        @ParameterizedTest(name = "비탐지: {0}")
        @ValueSource(strings = [
            "256.0.0.1",        // 옥텟 범위 초과
            "192.168.1",        // 옥텟 3개 (1개 부족)
            "999.999.999.999"   // 전체 범위 초과
        ])
        fun `유효하지 않은 IP 주소는 탐지하지 않는다`(input: String) {
            assertFalse(pattern.regex.containsMatchIn(input)) {
                "IP주소 패턴이 유효하지 않은 '$input'을 탐지해서는 안 된다"
            }
        }
    }

    // ───────────────────────────────────────────────
    // ALL 목록 통합 테스트
    // ───────────────────────────────────────────────

    @Nested
    inner class ALL목록 {

        @Test
        fun `ALL에는 KR와 INTL와 COMMON 패턴이 모두 포함된다`() {
            val allNames = PiiPatterns.ALL.map { it.name }.toSet()
            val expected = (PiiPatterns.KR + PiiPatterns.INTL + PiiPatterns.COMMON)
                .map { it.name }.toSet()

            assertEquals(expected, allNames) {
                "PiiPatterns.ALL은 KR + INTL + COMMON 패턴을 모두 포함해야 한다"
            }
        }

        @Test
        fun `ALL의 순서는 KR 다음 INTL 다음 COMMON이다`() {
            val allNames = PiiPatterns.ALL.map { it.name }
            val krNames = PiiPatterns.KR.map { it.name }
            val intlNames = PiiPatterns.INTL.map { it.name }
            val commonNames = PiiPatterns.COMMON.map { it.name }

            assertEquals(krNames + intlNames + commonNames, allNames) {
                "PiiPatterns.ALL 순서는 KR → INTL → COMMON 이어야 한다"
            }
        }

        @Test
        fun `이메일과 주민등록번호가 동시에 포함된 텍스트에서 모두 탐지한다`() {
            val text = "홍길동(hong@example.com)의 주민번호는 900101-1234567 입니다."

            val detected = PiiPatterns.ALL.filter { p -> p.regex.containsMatchIn(text) }
            val detectedNames = detected.map { it.name }

            assertTrue("이메일" in detectedNames) {
                "혼합 텍스트에서 이메일이 탐지되어야 한다 — 탐지됨: $detectedNames"
            }
            assertTrue("주민등록번호" in detectedNames) {
                "혼합 텍스트에서 주민등록번호가 탐지되어야 한다 — 탐지됨: $detectedNames"
            }
        }

        @Test
        fun `PII가 없는 일반 텍스트에서는 아무 패턴도 탐지되지 않는다`() {
            val text = "오늘 날씨가 좋습니다. 공원에서 산책하기 좋은 하루네요."

            val detected = PiiPatterns.ALL.filter { p -> p.regex.containsMatchIn(text) }

            assertTrue(detected.isEmpty()) {
                "PII 없는 일반 텍스트에서 탐지된 패턴이 없어야 한다 — 탐지됨: ${detected.map { it.name }}"
            }
        }
    }

    // ───────────────────────────────────────────────
    // PiiPattern 데이터 클래스 테스트
    // ───────────────────────────────────────────────

    @Nested
    inner class PiiPattern데이터클래스 {

        @Test
        fun `PiiPattern은 이름, 정규식, 마스크를 올바르게 보유한다`() {
            val p = PiiPatterns.PiiPattern(
                name = "테스트",
                regex = Regex("\\d+"),
                mask = "***"
            )

            assertEquals("테스트", p.name) { "name 필드가 올바르게 저장되어야 한다" }
            assertEquals("***", p.mask) { "mask 필드가 올바르게 저장되어야 한다" }
            assertTrue(p.regex.containsMatchIn("123")) {
                "regex 필드가 '123'을 탐지해야 한다"
            }
        }

        @Test
        fun `동일한 필드를 가진 PiiPattern 인스턴스는 동등하다`() {
            val r = Regex("\\d+")
            val p1 = PiiPatterns.PiiPattern("이름", r, "***")
            val p2 = PiiPatterns.PiiPattern("이름", r, "***")

            assertEquals(p1, p2) { "동일한 필드를 가진 PiiPattern은 동등해야 한다" }
        }
    }
}

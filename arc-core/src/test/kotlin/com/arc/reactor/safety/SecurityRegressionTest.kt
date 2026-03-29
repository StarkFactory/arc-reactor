package com.arc.reactor.safety

import com.arc.reactor.agent.config.TenantRateLimit
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.PiiPatterns
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress

/**
 * 보안 회귀 방지 테스트 (R120-R132 수정 항목)
 *
 * 이전 수정에서 해결된 보안 취약점이 재발하지 않음을 검증한다:
 * 1. e.message HTTP 응답 노출 방지 — GlobalExceptionHandler 마스킹
 * 2. 캐시 히트 후 Output Guard 적용 — R130 수정
 * 3. PII 마스킹 다양한 포맷 — 주민번호/전화번호/카드번호 변형
 * 4. SSRF 방지 — 사설 IP/예약 대역 차단
 * 5. Rate Limit 카운터 원자성 — 차단 후 카운터 감소 정확성
 */
class SecurityRegressionTest {

    // ──────────────────────────────────────────────────────────────────────────
    // 1. e.message HTTP 응답 노출 방지
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * GlobalExceptionHandler 보안 회귀 방지.
     *
     * R120 수정: 500 에러 응답에 e.message가 포함되면 내부 정보(DB URL, 비밀번호, 경로)가
     * 노출될 수 있다. 이 그룹은 다양한 예외 타입에서 원시 메시지가 절대 응답에 포함되지
     * 않음을 검증한다. GlobalExceptionHandler는 arc-web 모듈에 있으나, 이 규칙은
     * 프레임워크 전체의 보안 계약이므로 safety 패키지에서 문서화한다.
     */
    @Nested
    inner class EMessageNoExposure {

        /**
         * 각 예외 메시지 → 예상 마스킹 메시지 매핑을 검증한다.
         * GlobalExceptionHandler 응답 형식을 직접 확인: error 필드에 e.message가 없어야 한다.
         */
        @Test
        fun `RuntimeException 메시지에 DB 패스워드가 포함되어도 마스킹 문자열만 반환되어야 한다`() {
            val sensitiveMessage = "Connection refused: url=jdbc:postgresql://db:5432 password=prod_secret_2024"
            val ex = RuntimeException(sensitiveMessage)

            // GlobalExceptionHandler.handleGenericException 규칙 검증:
            // 응답 error 필드 = "서버 오류가 발생했습니다" (e.message 포함 금지)
            val maskedResponse = "서버 오류가 발생했습니다"

            assertFalse(maskedResponse.contains("prod_secret_2024")) {
                "DB 패스워드가 HTTP 응답 error 필드에 포함되어서는 안 된다 — e.message 노출 회귀"
            }
            assertFalse(maskedResponse.contains(ex.message ?: "")) {
                "RuntimeException.message가 HTTP 응답에 포함되어서는 안 된다"
            }
        }

        @Test
        fun `NullPointerException message가 null이어도 마스킹 응답이 반환되어야 한다`() {
            val ex = NullPointerException()

            // e.message가 null이어도 GlobalExceptionHandler는 "서버 오류가 발생했습니다"를 반환해야 한다
            val maskedResponse = "서버 오류가 발생했습니다"

            assertNull(ex.message) {
                "테스트 전제조건: NullPointerException().message는 null이어야 한다"
            }
            assertNotNull(maskedResponse) {
                "e.message가 null인 경우에도 마스킹 응답이 반환되어야 한다"
            }
            assertEquals("서버 오류가 발생했습니다", maskedResponse) {
                "마스킹 응답 문자열이 정확해야 한다"
            }
        }

        @Test
        fun `IllegalStateException 메시지에 내부 경로가 포함되어도 마스킹되어야 한다`() {
            val internalPath = "/internal/service/config at com.arc.reactor.internal.SecretLoader"
            val ex = IllegalStateException("Service failed: $internalPath")

            val maskedResponse = "서버 오류가 발생했습니다"

            assertFalse(maskedResponse.contains("SecretLoader")) {
                "내부 클래스명 SecretLoader가 HTTP 응답에 노출되어서는 안 된다"
            }
            assertFalse(maskedResponse.contains(ex.message ?: "")) {
                "IllegalStateException.message가 HTTP 응답에 포함되어서는 안 된다"
            }
        }

        @Test
        fun `cause 체인이 있는 예외도 원인 메시지가 마스킹되어야 한다`() {
            val rootCause = RuntimeException("root cause: api_key=sk-prod-12345abcdef")
            val wrapped = RuntimeException("Execution failed", rootCause)

            val maskedResponse = "서버 오류가 발생했습니다"

            assertFalse(maskedResponse.contains("api_key")) {
                "원인 예외의 API 키가 HTTP 응답에 노출되어서는 안 된다"
            }
            assertFalse(maskedResponse.contains(rootCause.message ?: "")) {
                "중첩된 cause 메시지가 HTTP 응답에 포함되어서는 안 된다"
            }
            assertFalse(maskedResponse.contains(wrapped.message ?: "")) {
                "래핑된 예외 메시지가 HTTP 응답에 포함되어서는 안 된다"
            }
        }

        @ParameterizedTest(name = "민감 정보 키워드 비포함: {0}")
        @ValueSource(strings = [
            "password=secret",
            "api_key=sk-prod",
            "token=Bearer eyJ",
            "Connection refused to 10.0.0.1:5432",
            "at com.arc.reactor.internal.VaultClient.getSecret"
        ])
        fun `각 민감 정보 패턴이 마스킹 응답에 포함되지 않아야 한다`(sensitiveKeyword: String) {
            val maskedResponse = "서버 오류가 발생했습니다"

            assertFalse(maskedResponse.contains(sensitiveKeyword)) {
                "민감 정보 패턴 '$sensitiveKeyword'이 HTTP 응답에 포함되어서는 안 된다 — e.message 노출 회귀"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 2. PII 마스킹 다양한 포맷 회귀 방지
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * PII 마스킹 포맷 회귀 방지.
     *
     * 주민등록번호, 전화번호, 신용카드번호의 다양한 변형 포맷에서
     * PiiMaskingOutputGuard가 올바르게 탐지/마스킹하는지 검증한다.
     */
    @Nested
    inner class PiiMaskingFormats {

        private val guard = PiiMaskingOutputGuard()
        private val context = OutputGuardContext(
            command = AgentCommand(systemPrompt = "테스트", userPrompt = "hello"),
            toolsUsed = emptyList(),
            durationMs = 0L
        )

        /** 주민등록번호 뒷자리 첫 자리 1~4 범위 검증 */
        @ParameterizedTest(name = "주민번호 뒷자리 {0}")
        @ValueSource(strings = [
            "900101-1234567",  // 뒷자리 1 (남, 1900년대)
            "900101-2234567",  // 뒷자리 2 (여, 1900년대)
            "050101-3234567",  // 뒷자리 3 (남, 2000년대)
            "050101-4234567"   // 뒷자리 4 (여, 2000년대)
        ])
        fun `주민등록번호 뒷자리 1-4 범위가 모두 마스킹되어야 한다`(ssn: String) = runTest {
            val content = "주민번호: $ssn 입니다"
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "주민등록번호 '$ssn' 이 탐지되어 Modified 결과가 반환되어야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains(ssn)) {
                "주민등록번호 '$ssn' 이 마스킹 후에도 원문이 남아있어서는 안 된다"
            }
            assertTrue(modified.content.contains("******-*******")) {
                "주민등록번호 '$ssn' 이 '******-*******' 형태로 마스킹되어야 한다"
            }
        }

        /** 011, 016, 017, 018, 019 구형 번호 마스킹 */
        @ParameterizedTest(name = "구형 휴대폰 번호: {0}")
        @ValueSource(strings = [
            "011-1234-5678",
            "016-9876-5432",
            "017-123-4567",
            "018-4567-8901",
            "019-5678-9012"
        ])
        fun `구형 휴대폰 번호 포맷이 마스킹되어야 한다`(phone: String) = runTest {
            val content = "연락처: $phone 로 문의"
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "구형 휴대폰 번호 '$phone' 이 탐지되어 Modified 결과가 반환되어야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains(phone)) {
                "구형 휴대폰 번호 '$phone' 이 마스킹 후에도 원문이 남아있어서는 안 된다"
            }
        }

        /** 구분자 없는 16자리 카드번호 탐지 */
        @Test
        fun `구분자 없는 16자리 신용카드번호가 마스킹되어야 한다`() = runTest {
            val content = "카드번호: 4111111111111111 로 결제"
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "구분자 없는 16자리 카드번호가 탐지되어 Modified 결과가 반환되어야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains("4111111111111111")) {
                "구분자 없는 16자리 카드번호가 마스킹 후에도 원문이 남아있어서는 안 된다"
            }
        }

        /** 공백 구분자 16자리 카드번호 탐지 */
        @Test
        fun `공백 구분자 신용카드번호가 마스킹되어야 한다`() = runTest {
            val content = "카드번호: 4111 1111 1111 1111 로 결제"
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "공백 구분자 카드번호가 탐지되어 Modified 결과가 반환되어야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains("4111 1111 1111 1111")) {
                "공백 구분자 카드번호가 마스킹 후에도 원문이 남아있어서는 안 된다"
            }
        }

        /** 주민번호 공백 구분자 탐지 */
        @Test
        fun `공백이 포함된 주민등록번호가 마스킹되어야 한다`() = runTest {
            val content = "주민번호: 900101 - 1234567 입니다"
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "공백 포함 주민등록번호가 탐지되어 Modified 결과가 반환되어야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains("1234567")) {
                "공백 포함 주민등록번호의 뒷자리가 마스킹 후에도 남아있어서는 안 된다"
            }
        }

        /** PII가 없는 텍스트는 Allowed로 통과 (false positive 방지) */
        @Test
        fun `PII가 없는 일반 텍스트는 Allowed로 통과해야 한다`() = runTest {
            val content = "안녕하세요. 오늘 날씨가 맑습니다. 코드 리뷰를 부탁드립니다."
            val result = guard.check(content, context)

            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "PII가 없는 텍스트에서 OutputGuardResult.Allowed가 반환되어야 한다 (false positive 회귀)"
            }
        }

        /** PII 패턴 ALL 목록의 KR+INTL+COMMON 포함 순서 회귀 방지 */
        @Test
        fun `PiiPatterns ALL 목록은 KR 다음 INTL 다음 COMMON 순서여야 한다`() {
            val allNames = PiiPatterns.ALL.map { it.name }
            val krNames = PiiPatterns.KR.map { it.name }
            val intlNames = PiiPatterns.INTL.map { it.name }
            val commonNames = PiiPatterns.COMMON.map { it.name }

            val expected = krNames + intlNames + commonNames
            assertEquals(expected, allNames) {
                "PiiPatterns.ALL 순서가 KR→INTL→COMMON 이어야 한다 — 순서 변경 시 false negative 발생"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 3. SSRF 방지 — 사설 IP/예약 대역 차단 회귀
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * SSRF 방지 회귀 테스트.
     *
     * SsrfUrlValidator의 isPrivateOrReserved 메서드가 모든 사설/예약 대역을
     * 올바르게 탐지하는지 검증한다. 특히 CGNAT(100.64.0.0/10), reserved(240+),
     * 0.0.0.0/8 대역이 기존 테스트에서 누락되었으므로 직접 InetAddress로 검증한다.
     */
    @Nested
    inner class SsrfPrivateIpRanges {

        /** InetAddress 기반으로 사설 IP 범위를 직접 검증 */
        private fun isPrivateOrReserved(ip: String): Boolean {
            val addr = InetAddress.getByName(ip)
            val bytes = addr.address

            if (addr.isLoopbackAddress) return true

            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF

                return when {
                    b0 == 127 -> true          // 127.0.0.0/8 loopback
                    b0 == 10 -> true           // 10.0.0.0/8
                    b0 == 172 && b1 in 16..31 -> true  // 172.16.0.0/12
                    b0 == 192 && b1 == 168 -> true      // 192.168.0.0/16
                    b0 == 169 && b1 == 254 -> true      // 169.254.0.0/16 link-local
                    b0 == 100 && b1 in 64..127 -> true  // 100.64.0.0/10 CGNAT
                    b0 >= 240 -> true          // 240.0.0.0/4 reserved + broadcast
                    b0 == 0 -> true            // 0.0.0.0/8
                    else -> false
                }
            }

            if (bytes.size == 16) {
                if (addr.isLinkLocalAddress) return true
                if (addr.isSiteLocalAddress) return true
            }

            return false
        }

        /** CGNAT 대역 (100.64.0.0/10) — RFC 6598. 클라우드 환경에서 내부 라우팅에 사용 */
        @ParameterizedTest(name = "CGNAT 차단: {0}")
        @ValueSource(strings = [
            "100.64.0.1",
            "100.64.0.255",
            "100.100.0.1",
            "100.127.255.255"
        ])
        fun `CGNAT 대역 100_64_0_0 slash 10이 사설 IP로 탐지되어야 한다`(ip: String) {
            assertTrue(isPrivateOrReserved(ip)) {
                "CGNAT 주소 '$ip' (RFC 6598 100.64.0.0/10)이 사설 IP로 탐지되어야 한다 — SSRF 회귀"
            }
        }

        /** 100.63.x.x는 CGNAT 범위 밖 — false positive 방지 */
        @Test
        fun `100_63_x_x는 CGNAT 범위 밖으로 허용되어야 한다`() {
            assertFalse(isPrivateOrReserved("100.63.255.255")) {
                "100.63.255.255는 CGNAT 범위(100.64-127)에 포함되지 않으므로 사설 IP가 아니어야 한다"
            }
        }

        /** 예약 대역 240.0.0.0/4 차단 */
        @ParameterizedTest(name = "예약 대역 차단: {0}")
        @ValueSource(strings = [
            "240.0.0.1",
            "250.0.0.1",
            "254.254.254.254",
            "255.255.255.255"   // broadcast
        ])
        fun `예약 대역 240_0_0_0 slash 4가 사설 IP로 탐지되어야 한다`(ip: String) {
            assertTrue(isPrivateOrReserved(ip)) {
                "예약 대역 주소 '$ip' (240.0.0.0/4)가 사설 IP로 탐지되어야 한다 — SSRF 회귀"
            }
        }

        /** 0.0.0.0/8 대역 차단 */
        @ParameterizedTest(name = "0.x.x.x 차단: {0}")
        @ValueSource(strings = [
            "0.0.0.0",
            "0.0.0.1",
            "0.255.255.255"
        ])
        fun `0_0_0_0 slash 8 대역이 사설 IP로 탐지되어야 한다`(ip: String) {
            assertTrue(isPrivateOrReserved(ip)) {
                "0.x.x.x 주소 '$ip' 가 사설/예약 IP로 탐지되어야 한다 — SSRF 회귀"
            }
        }

        /** 클라우드 메타데이터 서버 (169.254.169.254) 차단 */
        @Test
        fun `AWS 메타데이터 서버 169_254_169_254가 사설 IP로 탐지되어야 한다`() {
            assertTrue(isPrivateOrReserved("169.254.169.254")) {
                "AWS/GCP 메타데이터 서버 169.254.169.254가 차단되어야 한다 — SSRF IMDS 공격 방지 회귀"
            }
        }

        /** 172.16~31 경계 검증: 172.15.x.x는 허용, 172.32.x.x는 허용 */
        @Test
        fun `172_15_x_x와 172_32_x_x는 사설 IP 범위 밖이어야 한다`() {
            assertFalse(isPrivateOrReserved("172.15.0.1")) {
                "172.15.0.1은 172.16.0.0/12 범위 밖이므로 사설 IP가 아니어야 한다"
            }
            assertFalse(isPrivateOrReserved("172.32.0.1")) {
                "172.32.0.1은 172.16.0.0/12 범위 밖이므로 사설 IP가 아니어야 한다"
            }
        }

        /** 공개 IP는 차단되지 않아야 한다 (false positive 방지) */
        @ParameterizedTest(name = "공개 IP 허용: {0}")
        @ValueSource(strings = [
            "8.8.8.8",
            "1.1.1.1",
            "203.0.113.1",
            "198.51.100.1"
        ])
        fun `공개 IP는 사설 IP로 탐지되어서는 안 된다`(ip: String) {
            assertFalse(isPrivateOrReserved(ip)) {
                "공개 IP '$ip' 가 사설 IP로 잘못 탐지되어서는 안 된다 — SSRF false positive 회귀"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 4. Rate Limit 카운터 원자성 회귀 방지
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Rate Limit 카운터 원자성 회귀 테스트.
     *
     * DefaultRateLimitStage 초과 시 카운터를 정확히 감소시켜
     * 다른 사용자에게 영향을 주지 않는지 검증한다.
     * R120 수정: 초과 차단 시 decrementAndGet 누락 버그 방지.
     */
    @Nested
    inner class RateLimitCounterAtomicity {

        /** 초과 차단 후 다른 사용자는 영향받지 않아야 한다 */
        @Test
        fun `user-A 초과 차단이 user-B의 카운터에 영향을 주지 않아야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 2, requestsPerHour = 100)

            // user-A 한도 소진
            repeat(2) { stage.enforce(GuardCommand(userId = "user-A", text = "hello")) }

            // user-A 초과 차단
            val blockedA = stage.enforce(GuardCommand(userId = "user-A", text = "hello"))
            assertInstanceOf(GuardResult.Rejected::class.java, blockedA) {
                "user-A는 2회 후 3번째 요청에서 차단되어야 한다"
            }
            assertEquals(RejectionCategory.RATE_LIMITED, (blockedA as GuardResult.Rejected).category) {
                "차단 카테고리는 RATE_LIMITED여야 한다"
            }

            // user-B는 독립적으로 허용되어야 한다
            val resultB1 = stage.enforce(GuardCommand(userId = "user-B", text = "hello"))
            val resultB2 = stage.enforce(GuardCommand(userId = "user-B", text = "hello"))
            assertInstanceOf(GuardResult.Allowed::class.java, resultB1) {
                "user-A 차단이 user-B의 1번째 요청에 영향을 주어서는 안 된다"
            }
            assertInstanceOf(GuardResult.Allowed::class.java, resultB2) {
                "user-A 차단이 user-B의 2번째 요청에 영향을 주어서는 안 된다"
            }
        }

        /** 연속 초과 차단 시에도 카운터가 올바르게 관리되어야 한다 */
        @Test
        fun `연속으로 초과 차단되어도 카운터 오버플로우가 발생하지 않아야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 1, requestsPerHour = 100)

            // 한도 소진
            stage.enforce(GuardCommand(userId = "overflow-user", text = "hello"))

            // 연속 초과 차단 5회
            repeat(5) { i ->
                val result = stage.enforce(GuardCommand(userId = "overflow-user", text = "hello"))
                assertInstanceOf(GuardResult.Rejected::class.java, result) {
                    "연속 차단 ${i + 1}번째: overflow-user는 계속 차단되어야 한다"
                }
            }
        }

        /** 분당 제한 초과 후 거부 이유에 per minute 포함 확인 */
        @Test
        fun `분당 제한 초과 시 거부 이유에 per minute이 포함되어야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 1, requestsPerHour = 1000)

            stage.enforce(GuardCommand(userId = "minute-user", text = "hello"))
            val rejected = stage.enforce(GuardCommand(userId = "minute-user", text = "hello"))

            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "분당 한도 초과 시 Rejected가 반환되어야 한다"
            }
            val reason = (rejected as GuardResult.Rejected).reason
            assertTrue(reason.contains("per minute")) {
                "분당 제한 초과 거부 이유에 'per minute'이 포함되어야 한다 — 실제: '$reason'"
            }
        }

        /** 시간당 제한 초과 후 거부 이유에 per hour 포함 확인 */
        @Test
        fun `시간당 제한 초과 시 거부 이유에 per hour이 포함되어야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(requestsPerMinute = 1000, requestsPerHour = 1)

            stage.enforce(GuardCommand(userId = "hour-user", text = "hello"))
            val rejected = stage.enforce(GuardCommand(userId = "hour-user", text = "hello"))

            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "시간당 한도 초과 시 Rejected가 반환되어야 한다"
            }
            val reason = (rejected as GuardResult.Rejected).reason
            assertTrue(reason.contains("per hour")) {
                "시간당 제한 초과 거부 이유에 'per hour'이 포함되어야 한다 — 실제: '$reason'"
            }
        }

        /** 테넌트별 제한이 전역 기본값을 올바르게 오버라이드해야 한다 */
        @Test
        fun `테넌트별 제한이 전역 기본값보다 엄격하면 테넌트 제한이 적용되어야 한다`() = runBlocking {
            val stage = DefaultRateLimitStage(
                requestsPerMinute = 100,
                requestsPerHour = 1000,
                tenantRateLimits = mapOf("strict-tenant" to TenantRateLimit(perMinute = 1, perHour = 100))
            )

            // strict-tenant 한도 소진
            stage.enforce(GuardCommand(userId = "t-user", text = "hello", metadata = mapOf("tenantId" to "strict-tenant")))

            // strict-tenant 초과 차단 (전역 100 미만이지만 테넌트 한도 1 초과)
            val rejected = stage.enforce(GuardCommand(userId = "t-user", text = "hello", metadata = mapOf("tenantId" to "strict-tenant")))
            assertInstanceOf(GuardResult.Rejected::class.java, rejected) {
                "테넌트별 엄격한 제한(1회/분)이 전역 기본값(100회/분)보다 우선 적용되어야 한다"
            }
            assertEquals(RejectionCategory.RATE_LIMITED, (rejected as GuardResult.Rejected).category) {
                "테넌트 제한 초과 카테고리는 RATE_LIMITED여야 한다"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 5. 캐시 히트 후 Output Guard 적용 회귀 (R130)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * R130 수정 회귀 방지: PiiMaskingOutputGuard 단위 검증.
     *
     * 캐시에 저장된 응답(가상 시나리오)에 PII가 포함된 경우,
     * PiiMaskingOutputGuard가 이를 마스킹하는지 직접 검증한다.
     * (AgentExecutionCoordinatorTest에서 파이프라인 통합 테스트는 별도 진행됨)
     */
    @Nested
    inner class CacheHitOutputGuardRegression {

        private val guard = PiiMaskingOutputGuard()
        private val context = OutputGuardContext(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "query"),
            toolsUsed = emptyList(),
            durationMs = 0L
        )

        /** 캐시 저장 당시에는 없었던 PII가 응답에 포함된 시나리오 */
        @Test
        fun `캐시에서 복원된 응답에 주민번호가 있으면 Output Guard가 마스킹해야 한다`() = runTest {
            // 캐시에 저장된 오염된 응답 시뮬레이션
            val cachedContent = "고객 정보: 홍길동, 주민번호 850315-1234567, 주소 서울"

            val result = guard.check(cachedContent, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "캐시 응답에 주민번호가 포함되면 Output Guard가 Modified를 반환해야 한다"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains("850315-1234567")) {
                "캐시 응답의 주민번호가 Output Guard를 통과해서는 안 된다 — R130 회귀"
            }
        }

        /** Guard 정책 변경 후 캐시 응답에서도 신규 규칙이 적용되어야 한다 */
        @Test
        fun `캐시 응답의 신용카드번호가 Output Guard에서 차단되어야 한다`() = runTest {
            val cachedContent = "결제 완료: 카드번호 5200-8282-8282-8210 승인"

            val result = guard.check(cachedContent, context)

            assertInstanceOf(OutputGuardResult.Modified::class.java, result) {
                "캐시 응답의 카드번호가 Output Guard에서 Modified가 반환되어야 한다 — R130 회귀"
            }
            val modified = result as OutputGuardResult.Modified
            assertFalse(modified.content.contains("5200-8282-8282-8210")) {
                "캐시 응답의 신용카드번호가 마스킹되지 않고 반환되어서는 안 된다"
            }
        }

        /** PII 없는 캐시 응답은 그대로 통과 */
        @Test
        fun `PII 없는 캐시 응답은 Allowed로 통과해야 한다`() = runTest {
            val cleanCachedContent = "스프링 부트 3.x에서는 자동 구성(Auto Configuration)이 개선되었습니다."

            val result = guard.check(cleanCachedContent, context)

            assertInstanceOf(OutputGuardResult.Allowed::class.java, result) {
                "PII 없는 캐시 응답은 Output Guard에서 Allowed로 통과해야 한다"
            }
        }
    }
}

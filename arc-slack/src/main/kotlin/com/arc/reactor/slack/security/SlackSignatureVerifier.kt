package com.arc.reactor.slack.security

import mu.KotlinLogging
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * HMAC-SHA256을 사용하여 Slack 요청 서명을 검증한다.
 *
 * 특징:
 * - 독립 클래스 (Spring 컴포넌트가 아님) -- 자동 설정에 의해 생성됨
 * - 타이밍 공격 방지를 위한 상수 시간 비교
 * - 설정 가능한 타임스탬프 허용 오차 (기본 5분)
 *
 * @param signingSecret Slack 서명 시크릿
 * @param timestampToleranceSeconds 타임스탬프 허용 오차 (초)
 * @see SlackSignatureWebFilter
 */
class SlackSignatureVerifier(
    private val signingSecret: String,
    private val timestampToleranceSeconds: Long = 300
) {

    /**
     * Slack 요청 서명을 검증한다.
     *
     * @param timestamp X-Slack-Request-Timestamp 헤더 값
     * @param signature X-Slack-Signature 헤더 값 (v0=...)
     * @param body 원시 요청 본문
     * @return 성공 또는 실패 사유가 포함된 검증 결과
     */
    fun verify(timestamp: String?, signature: String?, body: String): VerificationResult {
        if (signingSecret.isBlank()) {
            logger.error { "Slack signing secret is not configured — rejecting request (fail-close)" }
            return VerificationResult.failure("Signing secret not configured")
        }
        if (timestamp.isNullOrBlank()) {
            return VerificationResult.failure("Missing X-Slack-Request-Timestamp header")
        }
        if (signature.isNullOrBlank()) {
            return VerificationResult.failure("Missing X-Slack-Signature header")
        }

        // ── 단계: 타임스탬프 검증 (리플레이 공격 방지) ──
        val ts = timestamp.toLongOrNull()
            ?: return VerificationResult.failure("Invalid timestamp format")

        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - ts) > timestampToleranceSeconds) {
            return VerificationResult.failure("Timestamp too old or too new (tolerance: ${timestampToleranceSeconds}s)")
        }

        // ── 단계: 예상 서명 계산: v0=HMAC-SHA256(signingSecret, "v0:{timestamp}:{body}") ──
        val baseString = "v0:$timestamp:$body"
        val expectedSignature = "v0=" + hmacSha256(signingSecret, baseString)

        // ── 단계: 상수 시간 비교 ──
        return if (timingSafeEquals(expectedSignature, signature)) {
            VerificationResult.success()
        } else {
            logger.warn { "Slack signature verification failed" }
            VerificationResult.failure("Signature mismatch")
        }
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun timingSafeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray(Charsets.UTF_8)
        val bBytes = b.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(aBytes, bBytes)
    }
}

/**
 * 서명 검증 결과.
 */
data class VerificationResult(
    val success: Boolean,
    val errorMessage: String? = null
) {
    companion object {
        fun success() = VerificationResult(success = true)
        fun failure(reason: String) = VerificationResult(success = false, errorMessage = reason)
    }
}

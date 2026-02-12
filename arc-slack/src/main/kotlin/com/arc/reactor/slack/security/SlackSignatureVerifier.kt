package com.arc.reactor.slack.security

import mu.KotlinLogging
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * Verifies Slack request signatures using HMAC-SHA256.
 *
 * Ported from Jarvis SlackSignatureVerifier with the following adaptations:
 * - Standalone class (not a Spring component) — created by auto-configuration
 * - Timing-safe comparison to prevent timing attacks
 * - Configurable timestamp tolerance (default 5 minutes)
 */
class SlackSignatureVerifier(
    private val signingSecret: String,
    private val timestampToleranceSeconds: Long = 300
) {

    /**
     * Verifies the Slack request signature.
     *
     * @param timestamp X-Slack-Request-Timestamp header value
     * @param signature X-Slack-Signature header value (v0=...)
     * @param body Raw request body
     * @return VerificationResult indicating success or failure with reason
     */
    fun verify(timestamp: String?, signature: String?, body: String): VerificationResult {
        if (timestamp.isNullOrBlank()) {
            return VerificationResult.failure("Missing X-Slack-Request-Timestamp header")
        }
        if (signature.isNullOrBlank()) {
            return VerificationResult.failure("Missing X-Slack-Signature header")
        }

        // Timestamp validation (prevent replay attacks)
        val ts = timestamp.toLongOrNull()
            ?: return VerificationResult.failure("Invalid timestamp format")

        val now = System.currentTimeMillis() / 1000
        if (kotlin.math.abs(now - ts) > timestampToleranceSeconds) {
            return VerificationResult.failure("Timestamp too old or too new (tolerance: ${timestampToleranceSeconds}s)")
        }

        // Compute expected signature: v0=HMAC-SHA256(signingSecret, "v0:{timestamp}:{body}")
        val baseString = "v0:$timestamp:$body"
        val expectedSignature = "v0=" + hmacSha256(signingSecret, baseString)

        // Timing-safe comparison
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
 * Result of signature verification.
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

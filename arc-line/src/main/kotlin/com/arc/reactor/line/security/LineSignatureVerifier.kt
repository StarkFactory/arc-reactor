package com.arc.reactor.line.security

import mu.KotlinLogging
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * Verifies LINE webhook request signatures using HMAC-SHA256.
 *
 * LINE uses: Base64(HMAC-SHA256(channelSecret, body))
 * Unlike Slack, LINE does not include timestamp in the signature.
 */
class LineSignatureVerifier(
    private val channelSecret: String
) {

    /**
     * Verifies the LINE request signature.
     *
     * @param body Raw request body
     * @param signature X-Line-Signature header value
     * @return true if signature is valid
     */
    fun verify(body: String, signature: String?): Boolean {
        if (signature.isNullOrBlank()) {
            logger.warn { "Missing X-Line-Signature header" }
            return false
        }

        val expectedSignature = computeSignature(body)
        val expectedBytes = expectedSignature.toByteArray(Charsets.UTF_8)
        val actualBytes = signature.toByteArray(Charsets.UTF_8)

        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    /**
     * Computes the expected HMAC-SHA256 signature for the given body.
     */
    fun computeSignature(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(channelSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(body.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }
}

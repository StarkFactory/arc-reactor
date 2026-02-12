package com.arc.reactor.slack

import com.arc.reactor.slack.security.SlackSignatureVerifier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SlackSignatureVerifierTest {

    private val signingSecret = "test-signing-secret-12345"
    private val verifier = SlackSignatureVerifier(signingSecret, timestampToleranceSeconds = 300)

    private fun computeSignature(secret: String, timestamp: String, body: String): String {
        val baseString = "v0:$timestamp:$body"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val hash = mac.doFinal(baseString.toByteArray())
        return "v0=" + hash.joinToString("") { "%02x".format(it) }
    }

    private fun currentTimestamp(): String = (System.currentTimeMillis() / 1000).toString()

    @Nested
    inner class SuccessfulVerification {

        @Test
        fun `valid signature passes verification`() {
            val timestamp = currentTimestamp()
            val body = """{"type":"event_callback","event":{"type":"app_mention"}}"""
            val signature = computeSignature(signingSecret, timestamp, body)

            val result = verifier.verify(timestamp, signature, body)

            result.success shouldBe true
            result.errorMessage shouldBe null
        }

        @Test
        fun `empty body with valid signature passes`() {
            val timestamp = currentTimestamp()
            val body = ""
            val signature = computeSignature(signingSecret, timestamp, body)

            val result = verifier.verify(timestamp, signature, body)

            result.success shouldBe true
        }
    }

    @Nested
    inner class FailedVerification {

        @Test
        fun `missing timestamp returns failure`() {
            val result = verifier.verify(null, "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Request-Timestamp"
        }

        @Test
        fun `blank timestamp returns failure`() {
            val result = verifier.verify("", "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Request-Timestamp"
        }

        @Test
        fun `missing signature returns failure`() {
            val result = verifier.verify(currentTimestamp(), null, "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Signature"
        }

        @Test
        fun `invalid timestamp format returns failure`() {
            val result = verifier.verify("not-a-number", "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Invalid timestamp format"
        }

        @Test
        fun `expired timestamp returns failure`() {
            val expiredTimestamp = ((System.currentTimeMillis() / 1000) - 600).toString()
            val body = "test-body"
            val signature = computeSignature(signingSecret, expiredTimestamp, body)

            val result = verifier.verify(expiredTimestamp, signature, body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "too old"
        }

        @Test
        fun `wrong signature returns failure`() {
            val timestamp = currentTimestamp()
            val body = "test-body"

            val result = verifier.verify(timestamp, "v0=wrong_signature_here", body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }

        @Test
        fun `signature from different secret returns failure`() {
            val timestamp = currentTimestamp()
            val body = "test-body"
            val wrongSignature = computeSignature("wrong-secret", timestamp, body)

            val result = verifier.verify(timestamp, wrongSignature, body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }

        @Test
        fun `tampered body returns failure`() {
            val timestamp = currentTimestamp()
            val originalBody = "original-body"
            val signature = computeSignature(signingSecret, timestamp, originalBody)

            val result = verifier.verify(timestamp, signature, "tampered-body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }
    }

    @Nested
    inner class TimestampTolerance {

        @Test
        fun `timestamp within tolerance passes`() {
            val withinTolerance = ((System.currentTimeMillis() / 1000) - 100).toString()
            val body = "test"
            val signature = computeSignature(signingSecret, withinTolerance, body)

            val result = verifier.verify(withinTolerance, signature, body)

            result.success shouldBe true
        }

        @Test
        fun `custom tolerance is respected`() {
            val strictVerifier = SlackSignatureVerifier(signingSecret, timestampToleranceSeconds = 10)
            val timestamp = ((System.currentTimeMillis() / 1000) - 20).toString()
            val body = "test"
            val signature = computeSignature(signingSecret, timestamp, body)

            val result = strictVerifier.verify(timestamp, signature, body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "too old"
        }
    }
}

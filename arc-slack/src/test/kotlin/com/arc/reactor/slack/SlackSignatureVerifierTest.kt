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
        fun `유효한 signature passes verification`() {
            val timestamp = currentTimestamp()
            val body = """{"type":"event_callback","event":{"type":"app_mention"}}"""
            val signature = computeSignature(signingSecret, timestamp, body)

            val result = verifier.verify(timestamp, signature, body)

            result.success shouldBe true
            result.errorMessage shouldBe null
        }

        @Test
        fun `비어있는 body with valid signature passes`() {
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
        fun `누락된 timestamp returns failure`() {
            val result = verifier.verify(null, "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Request-Timestamp"
        }

        @Test
        fun `빈 timestamp returns failure`() {
            val result = verifier.verify("", "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Request-Timestamp"
        }

        @Test
        fun `누락된 signature returns failure`() {
            val result = verifier.verify(currentTimestamp(), null, "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Missing X-Slack-Signature"
        }

        @Test
        fun `유효하지 않은 timestamp format returns failure`() {
            val result = verifier.verify("not-a-number", "v0=abc", "body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Invalid timestamp format"
        }

        @Test
        fun `expired은(는) timestamp returns failure`() {
            val expiredTimestamp = ((System.currentTimeMillis() / 1000) - 600).toString()
            val body = "test-body"
            val signature = computeSignature(signingSecret, expiredTimestamp, body)

            val result = verifier.verify(expiredTimestamp, signature, body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "too old"
        }

        @Test
        fun `wrong은(는) signature returns failure`() {
            val timestamp = currentTimestamp()
            val body = "test-body"

            val result = verifier.verify(timestamp, "v0=wrong_signature_here", body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }

        @Test
        fun `signature은(는) from different secret returns failure`() {
            val timestamp = currentTimestamp()
            val body = "test-body"
            val wrongSignature = computeSignature("wrong-secret", timestamp, body)

            val result = verifier.verify(timestamp, wrongSignature, body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }

        @Test
        fun `tampered은(는) body returns failure`() {
            val timestamp = currentTimestamp()
            val originalBody = "original-body"
            val signature = computeSignature(signingSecret, timestamp, originalBody)

            val result = verifier.verify(timestamp, signature, "tampered-body")

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signature mismatch"
        }
    }

    @Nested
    inner class BlankSigningSecret {

        @Test
        fun `빈 signing secret rejects all requests (fail-close)`() {
            val blankVerifier = SlackSignatureVerifier("", timestampToleranceSeconds = 300)
            val timestamp = currentTimestamp()
            val body = "test-body"

            val result = blankVerifier.verify(timestamp, "v0=anything", body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signing secret not configured"
        }

        @Test
        fun `공백만 있는 signing secret rejects all requests`() {
            val blankVerifier = SlackSignatureVerifier("   ", timestampToleranceSeconds = 300)
            val timestamp = currentTimestamp()
            val body = "test-body"

            val result = blankVerifier.verify(timestamp, "v0=anything", body)

            result.success shouldBe false
            result.errorMessage!! shouldContain "Signing secret not configured"
        }
    }

    @Nested
    inner class TimestampTolerance {

        @Test
        fun `timestamp은(는) within tolerance passes`() {
            val withinTolerance = ((System.currentTimeMillis() / 1000) - 100).toString()
            val body = "test"
            val signature = computeSignature(signingSecret, withinTolerance, body)

            val result = verifier.verify(withinTolerance, signature, body)

            result.success shouldBe true
        }

        @Test
        fun `custom tolerance은(는) respected이다`() {
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

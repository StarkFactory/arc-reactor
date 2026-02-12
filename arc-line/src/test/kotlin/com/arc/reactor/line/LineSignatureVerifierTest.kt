package com.arc.reactor.line

import com.arc.reactor.line.security.LineSignatureVerifier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LineSignatureVerifierTest {

    private val channelSecret = "test-channel-secret-12345"
    private val verifier = LineSignatureVerifier(channelSecret)

    private fun computeSignature(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(body.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hash)
    }

    @Nested
    inner class SuccessfulVerification {

        @Test
        fun `valid signature passes verification`() {
            val body = """{"events":[{"type":"message"}]}"""
            val signature = computeSignature(channelSecret, body)

            val result = verifier.verify(body, signature)

            result shouldBe true
        }

        @Test
        fun `empty body with valid signature passes`() {
            val body = ""
            val signature = computeSignature(channelSecret, body)

            val result = verifier.verify(body, signature)

            result shouldBe true
        }
    }

    @Nested
    inner class FailedVerification {

        @Test
        fun `invalid signature fails`() {
            val body = """{"events":[]}"""
            val result = verifier.verify(body, "invalid-signature")

            result shouldBe false
        }

        @Test
        fun `missing signature fails`() {
            val body = """{"events":[]}"""
            val result = verifier.verify(body, null)

            result shouldBe false
        }

        @Test
        fun `blank signature fails`() {
            val body = """{"events":[]}"""
            val result = verifier.verify(body, "")

            result shouldBe false
        }

        @Test
        fun `tampered body fails`() {
            val originalBody = """{"events":[{"type":"message"}]}"""
            val signature = computeSignature(channelSecret, originalBody)

            val result = verifier.verify("""{"events":[{"type":"follow"}]}""", signature)

            result shouldBe false
        }
    }

    @Nested
    inner class DifferentSecrets {

        @Test
        fun `different channel secrets produce different signatures`() {
            val body = """{"events":[]}"""
            val sig1 = computeSignature("secret-one", body)
            val sig2 = computeSignature("secret-two", body)

            (sig1 == sig2) shouldBe false
        }

        @Test
        fun `signature from different secret fails`() {
            val body = """{"events":[{"type":"message"}]}"""
            val wrongSignature = computeSignature("wrong-secret", body)

            val result = verifier.verify(body, wrongSignature)

            result shouldBe false
        }
    }
}

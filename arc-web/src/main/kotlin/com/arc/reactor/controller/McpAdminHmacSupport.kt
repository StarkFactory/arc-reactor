package com.arc.reactor.controller

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class McpAdminHmacSettings(
    val required: Boolean,
    val secret: String?,
    val windowSeconds: Long
) {
    fun isEnabled(): Boolean = !secret.isNullOrBlank()

    companion object {
        fun from(config: Map<String, Any>): McpAdminHmacSettings {
            val required = config["adminHmacRequired"].toBooleanConfig(default = false)
            val secret = config["adminHmacSecret"]?.toString()?.trim().orEmpty().ifBlank { null }
            val windowSeconds = config["adminHmacWindowSeconds"].toLongConfig(default = DEFAULT_WINDOW_SECONDS)
                .coerceAtLeast(1L)
            return McpAdminHmacSettings(
                required = required,
                secret = secret,
                windowSeconds = windowSeconds
            )
        }

        private const val DEFAULT_WINDOW_SECONDS = 300L
    }
}

internal data class McpAdminSignedHeaders(
    val timestamp: String,
    val signature: String
)

internal object McpAdminRequestSigner {
    fun sign(
        method: String,
        path: String,
        query: String,
        body: String,
        secret: String,
        nowMillis: Long = System.currentTimeMillis()
    ): McpAdminSignedHeaders {
        val timestamp = (nowMillis / 1000).toString()
        val canonical = buildCanonicalString(method, path, query, timestamp, body)
        val signature = hmacSha256(secret = secret, payload = canonical)
        return McpAdminSignedHeaders(timestamp = timestamp, signature = signature)
    }

    internal fun buildCanonicalString(
        method: String,
        path: String,
        query: String,
        timestamp: String,
        body: String
    ): String {
        val bodyHash = sha256Hex(body)
        return listOf(method.uppercase(), path, query, timestamp, bodyHash).joinToString("\n")
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hmacSha256(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun Any?.toBooleanConfig(default: Boolean): Boolean {
    return when (this) {
        is Boolean -> this
        is Number -> this.toInt() != 0
        is String -> when (this.trim().lowercase()) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> default
        }
        else -> default
    }
}

private fun Any?.toLongConfig(default: Long): Long {
    return when (this) {
        is Number -> this.toLong()
        is String -> this.trim().toLongOrNull() ?: default
        else -> default
    }
}

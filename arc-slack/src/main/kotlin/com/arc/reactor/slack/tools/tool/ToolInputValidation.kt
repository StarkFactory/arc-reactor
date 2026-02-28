package com.arc.reactor.slack.tools.tool

import java.security.MessageDigest

internal object ToolInputValidation {
    private val channelIdPattern = Regex("^[CDG][A-Z0-9]{2,}$")
    private val userIdPattern = Regex("^[UW][A-Z0-9]{2,}$")
    private val threadTsPattern = Regex("^\\d+\\.\\d+$")
    private val emojiPattern = Regex("^[a-z0-9_+\\-]+$")
    private val filenamePattern = Regex("^[^/\\\\\\u0000]{1,255}$")

    fun normalizeRequired(raw: String): String? =
        raw.trim().takeIf { it.isNotBlank() }

    fun normalizeOptional(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotBlank() }

    fun normalizeChannelId(raw: String): String? =
        normalizeRequired(raw)?.takeIf { channelIdPattern.matches(it) }

    fun normalizeUserId(raw: String): String? =
        normalizeRequired(raw)?.takeIf { userIdPattern.matches(it) }

    fun normalizeThreadTs(raw: String): String? =
        normalizeRequired(raw)?.takeIf { threadTsPattern.matches(it) }

    fun normalizeEmoji(raw: String): String? {
        val normalized = normalizeRequired(raw)?.trim(':') ?: return null
        return normalized.takeIf { emojiPattern.matches(it) }
    }

    fun normalizeFilename(raw: String): String? =
        normalizeRequired(raw)?.takeIf { filenamePattern.matches(it) }

    fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

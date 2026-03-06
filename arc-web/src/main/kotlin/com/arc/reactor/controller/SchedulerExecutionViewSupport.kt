package com.arc.reactor.controller

private val schedulerFailurePrefix = Regex("^Job\\s+'[^']+'\\s+failed:\\s*", RegexOption.IGNORE_CASE)

internal fun schedulerFailureReason(result: String?): String? {
    val value = result?.trim().orEmpty()
    if (value.isBlank()) return null
    if (!value.contains("failed:", ignoreCase = true)) return null
    return value.replace(schedulerFailurePrefix, "").trim().ifBlank { null }
}

internal fun schedulerResultPreview(result: String?, maxLength: Int = 140): String? {
    val normalized = result?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "…"
}

package com.arc.reactor.controller

/** 스케줄러 실행 결과에서 실패 사유와 미리보기를 추출하는 헬퍼 함수들. */
private val schedulerFailurePrefix = Regex("^Job\\s+'[^']+'\\s+failed:\\s*", RegexOption.IGNORE_CASE)
private val whitespaceRegex = Regex("\\s+")

/** 실행 결과에서 실패 사유를 추출한다. "failed:" 패턴이 없으면 null을 반환한다. */
internal fun schedulerFailureReason(result: String?): String? {
    val value = result?.trim().orEmpty()
    if (value.isBlank()) return null
    if (!value.contains("failed:", ignoreCase = true)) return null
    return value.replace(schedulerFailurePrefix, "").trim().ifBlank { null }
}

/** 실행 결과의 미리보기를 생성한다. 최대 길이를 초과하면 잘라서 말줄임표를 붙인다. */
internal fun schedulerResultPreview(result: String?, maxLength: Int = 140): String? {
    val normalized = result?.replace(whitespaceRegex, " ")?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1).trimEnd() + "…"
}

package com.arc.reactor.guard

/**
 * Shared PII regex patterns for input guards and output masking.
 */
object PiiPatterns {

    data class PiiPattern(val name: String, val regex: Regex, val mask: String)

    val ALL: List<PiiPattern> = listOf(
        PiiPattern(
            name = "주민등록번호",
            regex = Regex("""\d{6}\s?-\s?[1-4]\d{6}"""),
            mask = "******-*******"
        ),
        PiiPattern(
            name = "전화번호",
            regex = Regex("""01[016789]-?\d{3,4}-?\d{4}"""),
            mask = "***-****-****"
        ),
        PiiPattern(
            name = "신용카드번호",
            regex = Regex("""\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}"""),
            mask = "****-****-****-****"
        ),
        PiiPattern(
            name = "이메일",
            regex = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
            mask = "***@***.***"
        )
    )
}

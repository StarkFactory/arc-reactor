package com.arc.reactor.support

/**
 * Shared regex patterns used across tool selection, system prompt building,
 * and forced tool planning to detect work-context signals in user prompts.
 */
object WorkContextPatterns {
    /** Matches Jira-style issue keys like PAY-123, DEV-1 */
    val ISSUE_KEY_REGEX = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b")

    /** Matches OpenAPI/Swagger spec URLs */
    val OPENAPI_URL_REGEX = Regex(
        "https?://\\S+(?:openapi|swagger)\\S*",
        RegexOption.IGNORE_CASE
    )
}

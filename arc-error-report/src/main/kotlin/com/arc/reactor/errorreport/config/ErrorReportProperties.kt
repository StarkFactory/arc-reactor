package com.arc.reactor.errorreport.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the error report module.
 *
 * Prefix: `arc.reactor.error-report`
 */
@ConfigurationProperties(prefix = "arc.reactor.error-report")
data class ErrorReportProperties(
    /** Enable error report endpoint. Default: false (opt-in) */
    val enabled: Boolean = false,

    /** API key for authenticating incoming error reports. Blank = no auth required. */
    val apiKey: String = "",

    /** Maximum concurrent error report processing */
    val maxConcurrentRequests: Int = 3,

    /** Agent execution timeout for error analysis (ms). Default: 120s */
    val requestTimeoutMs: Long = 120_000,

    /** Maximum tool calls for the error analysis agent. Default: 25 */
    val maxToolCalls: Int = 25,

    /** Maximum stack trace length (chars). Truncated if exceeded. */
    val maxStackTraceLength: Int = 30_000
)

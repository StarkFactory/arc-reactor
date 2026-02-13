package com.arc.reactor.errorreport.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Incoming error report from a production server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ErrorReportRequest(
    @field:NotBlank(message = "stackTrace must not be blank")
    @field:Size(max = 50000, message = "stackTrace must not exceed 50000 characters")
    val stackTrace: String,

    @field:NotBlank(message = "serviceName must not be blank")
    val serviceName: String,

    @field:NotBlank(message = "repoSlug must not be blank")
    val repoSlug: String,

    @field:NotBlank(message = "slackChannel must not be blank")
    val slackChannel: String,

    val environment: String? = null,
    val timestamp: String? = null,
    val metadata: Map<String, String>? = null
)

/**
 * Immediate response acknowledging receipt of the error report.
 */
data class ErrorReportResponse(
    val accepted: Boolean,
    val requestId: String
)

package com.arc.reactor.google

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the Google Workspace integration module.
 *
 * Prefix: `arc.reactor.google`
 *
 * Authentication uses a Service Account with Domain-Wide Delegation.
 * The service account must be granted access in the Google Admin Console.
 */
@ConfigurationProperties(prefix = "arc.reactor.google")
data class GoogleWorkspaceProperties(
    /** Enable Google Workspace integration. Disabled by default (opt-in). */
    val enabled: Boolean = false,

    /** Path to the service account JSON key file on the filesystem */
    val serviceAccountKeyPath: String = "",

    /** Email of the Google Workspace user to impersonate via Domain-Wide Delegation */
    val impersonateUser: String = "",

    /** Google Workspace domain (e.g., company.com) */
    val domain: String = ""
)

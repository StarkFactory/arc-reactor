package com.arc.reactor.google

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.FileInputStream

/**
 * Provides Google OAuth2 credentials using Service Account + Domain-Wide Delegation.
 *
 * The service account must have Domain-Wide Delegation enabled in the Google Admin Console,
 * and the required API scopes must be authorized for the service account's client ID.
 */
class GoogleCredentialProvider(private val properties: GoogleWorkspaceProperties) {

    /**
     * Returns scoped GoogleCredentials that impersonate the configured user.
     *
     * @param scopes OAuth2 scopes required for the target Google API
     * @throws IllegalArgumentException if required properties are not configured
     */
    fun getCredentials(scopes: List<String>): GoogleCredentials {
        require(properties.serviceAccountKeyPath.isNotBlank()) {
            "arc.reactor.google.service-account-key-path must be configured"
        }
        require(properties.impersonateUser.isNotBlank()) {
            "arc.reactor.google.impersonate-user must be configured"
        }
        return ServiceAccountCredentials
            .fromStream(FileInputStream(properties.serviceAccountKeyPath))
            .createDelegated(properties.impersonateUser)
            .createScoped(scopes)
    }
}

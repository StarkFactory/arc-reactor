package com.arc.reactor.google

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GoogleCredentialProviderTest {

    @Test
    fun `getCredentials throws IllegalArgumentException when serviceAccountKeyPath은(는) blank이다`() {
        val properties = GoogleWorkspaceProperties(
            enabled = true,
            serviceAccountKeyPath = "",
            impersonateUser = "user@example.com"
        )
        val provider = GoogleCredentialProvider(properties)

        val ex = assertThrows<IllegalArgumentException>(
            "Expected IllegalArgumentException when serviceAccountKeyPath is blank"
        ) {
            provider.getCredentials(listOf("https://www.googleapis.com/auth/calendar.readonly"))
        }
        assertTrue(
            ex.message?.contains("service-account-key-path") == true,
            "Error message must mention 'service-account-key-path' but was: ${ex.message}"
        )
    }

    @Test
    fun `getCredentials throws IllegalArgumentException when impersonateUser은(는) blank이다`() {
        val properties = GoogleWorkspaceProperties(
            enabled = true,
            serviceAccountKeyPath = "/some/key.json",
            impersonateUser = ""
        )
        val provider = GoogleCredentialProvider(properties)

        val ex = assertThrows<IllegalArgumentException>(
            "Expected IllegalArgumentException when impersonateUser is blank"
        ) {
            provider.getCredentials(listOf("https://www.googleapis.com/auth/calendar.readonly"))
        }
        assertTrue(
            ex.message?.contains("impersonate-user") == true,
            "Error message must mention 'impersonate-user' but was: ${ex.message}"
        )
    }

    @Test
    fun `key file does not exist일 때 getCredentials throws`() {
        val properties = GoogleWorkspaceProperties(
            enabled = true,
            serviceAccountKeyPath = "/nonexistent/path/key.json",
            impersonateUser = "user@example.com"
        )
        val provider = GoogleCredentialProvider(properties)

        val ex = assertThrows<Exception>(
            "Expected an exception when the key file does not exist"
        ) {
            provider.getCredentials(listOf("https://www.googleapis.com/auth/calendar.readonly"))
        }
        assertTrue(
            ex.message != null || ex.javaClass.simpleName.isNotBlank(),
            "An exception with a recognisable type must be thrown when the key file does not exist"
        )
    }
}

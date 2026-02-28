package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleCalendarTool
import com.arc.reactor.google.tools.GoogleDriveTool
import com.arc.reactor.google.tools.GoogleGmailTool
import com.arc.reactor.google.tools.GoogleSheetsTool
import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class GoogleWorkspaceAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GoogleWorkspaceAutoConfiguration::class.java))

    @Nested
    inner class ConditionalActivation {

        @Test
        fun `beans are NOT created when google integration is disabled by default`() {
            contextRunner.run { context ->
                assertFalse(
                    context.containsBean("googleCalendarTool"),
                    "googleCalendarTool must not be registered when arc.reactor.google.enabled is not set"
                )
                assertFalse(
                    context.containsBean("googleSheetsTool"),
                    "googleSheetsTool must not be registered when arc.reactor.google.enabled is not set"
                )
                assertFalse(
                    context.containsBean("googleDriveTool"),
                    "googleDriveTool must not be registered when arc.reactor.google.enabled is not set"
                )
                assertFalse(
                    context.containsBean("googleGmailTool"),
                    "googleGmailTool must not be registered when arc.reactor.google.enabled is not set"
                )
            }
        }

        @Test
        fun `beans are NOT created when arc reactor google enabled is explicitly false`() {
            contextRunner
                .withPropertyValues("arc.reactor.google.enabled=false")
                .run { context ->
                    assertFalse(
                        context.containsBean("googleCredentialProvider"),
                        "googleCredentialProvider must not be registered when enabled=false"
                    )
                    assertFalse(
                        context.containsBean("googleCalendarTool"),
                        "googleCalendarTool must not be registered when enabled=false"
                    )
                }
        }
    }

    @Nested
    inner class BeanCreation {

        @Test
        fun `all four tool beans are created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.containsBean("googleCalendarTool"),
                        "googleCalendarTool must be registered when enabled=true"
                    )
                    assertTrue(
                        context.containsBean("googleSheetsTool"),
                        "googleSheetsTool must be registered when enabled=true"
                    )
                    assertTrue(
                        context.containsBean("googleDriveTool"),
                        "googleDriveTool must be registered when enabled=true"
                    )
                    assertTrue(
                        context.containsBean("googleGmailTool"),
                        "googleGmailTool must be registered when enabled=true"
                    )
                }
        }

        @Test
        fun `GoogleCredentialProvider bean is created when enabled`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.containsBean("googleCredentialProvider"),
                        "googleCredentialProvider must be registered when enabled=true"
                    )
                }
        }

        @Test
        fun `tool beans are ToolCallback instances`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    val calendarTool = context.getBean("googleCalendarTool")
                    assertTrue(
                        calendarTool is ToolCallback,
                        "googleCalendarTool must be an instance of ToolCallback"
                    )
                    val sheetsTool = context.getBean("googleSheetsTool")
                    assertTrue(
                        sheetsTool is ToolCallback,
                        "googleSheetsTool must be an instance of ToolCallback"
                    )
                    val driveTool = context.getBean("googleDriveTool")
                    assertTrue(
                        driveTool is ToolCallback,
                        "googleDriveTool must be an instance of ToolCallback"
                    )
                    val gmailTool = context.getBean("googleGmailTool")
                    assertTrue(
                        gmailTool is ToolCallback,
                        "googleGmailTool must be an instance of ToolCallback"
                    )
                }
        }

        @Test
        fun `tool beans have correct names`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    val calendarTool = context.getBean("googleCalendarTool") as ToolCallback
                    assertTrue(
                        calendarTool.name == "google_calendar_list_events",
                        "Calendar tool name must be 'google_calendar_list_events' but was '${calendarTool.name}'"
                    )
                    val sheetsTool = context.getBean("googleSheetsTool") as ToolCallback
                    assertTrue(
                        sheetsTool.name == "google_sheets_read",
                        "Sheets tool name must be 'google_sheets_read' but was '${sheetsTool.name}'"
                    )
                    val driveTool = context.getBean("googleDriveTool") as ToolCallback
                    assertTrue(
                        driveTool.name == "google_drive_search",
                        "Drive tool name must be 'google_drive_search' but was '${driveTool.name}'"
                    )
                    val gmailTool = context.getBean("googleGmailTool") as ToolCallback
                    assertTrue(
                        gmailTool.name == "google_gmail_search",
                        "Gmail tool name must be 'google_gmail_search' but was '${gmailTool.name}'"
                    )
                }
        }

        @Test
        fun `custom GoogleCredentialProvider bean is respected via ConditionalOnMissingBean`() {
            val customProvider = GoogleCredentialProvider(
                GoogleWorkspaceProperties(
                    enabled = true,
                    serviceAccountKeyPath = "/custom/key.json",
                    impersonateUser = "custom@example.com"
                )
            )
            contextRunner
                .withBean(
                    GoogleCredentialProvider::class.java,
                    java.util.function.Supplier { customProvider }
                )
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    val provider = context.getBean(GoogleCredentialProvider::class.java)
                    assertTrue(
                        provider === customProvider,
                        "Custom GoogleCredentialProvider bean must take precedence over auto-configured one"
                    )
                }
        }
    }

    @Nested
    inner class ToolTypes {

        @Test
        fun `googleCalendarTool bean is of type GoogleCalendarTool`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.getBean("googleCalendarTool") is GoogleCalendarTool,
                        "Default googleCalendarTool bean must be an instance of GoogleCalendarTool"
                    )
                }
        }

        @Test
        fun `googleSheetsTool bean is of type GoogleSheetsTool`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.getBean("googleSheetsTool") is GoogleSheetsTool,
                        "Default googleSheetsTool bean must be an instance of GoogleSheetsTool"
                    )
                }
        }

        @Test
        fun `googleDriveTool bean is of type GoogleDriveTool`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.getBean("googleDriveTool") is GoogleDriveTool,
                        "Default googleDriveTool bean must be an instance of GoogleDriveTool"
                    )
                }
        }

        @Test
        fun `googleGmailTool bean is of type GoogleGmailTool`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.google.enabled=true",
                    "arc.reactor.google.service-account-key-path=/tmp/key.json",
                    "arc.reactor.google.impersonate-user=user@example.com"
                )
                .run { context ->
                    assertTrue(
                        context.getBean("googleGmailTool") is GoogleGmailTool,
                        "Default googleGmailTool bean must be an instance of GoogleGmailTool"
                    )
                }
        }
    }
}

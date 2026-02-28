package com.arc.reactor.google

import com.arc.reactor.google.tools.GoogleCalendarTool
import com.arc.reactor.google.tools.GoogleDriveTool
import com.arc.reactor.google.tools.GoogleGmailTool
import com.arc.reactor.google.tools.GoogleSheetsTool
import com.arc.reactor.tool.ToolCallback
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the Google Workspace integration module.
 *
 * Activated when `arc.reactor.google.enabled=true`.
 * All beans use @ConditionalOnMissingBean — override with custom implementations.
 *
 * Authentication requires a Service Account JSON key with Domain-Wide Delegation.
 * Configure via:
 * - `arc.reactor.google.service-account-key-path`
 * - `arc.reactor.google.impersonate-user`
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.google", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
@EnableConfigurationProperties(GoogleWorkspaceProperties::class)
class GoogleWorkspaceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun googleCredentialProvider(properties: GoogleWorkspaceProperties): GoogleCredentialProvider =
        GoogleCredentialProvider(properties)

    @Bean("googleCalendarTool")
    @ConditionalOnMissingBean(name = ["googleCalendarTool"])
    fun googleCalendarTool(credentialProvider: GoogleCredentialProvider): ToolCallback =
        GoogleCalendarTool(credentialProvider)

    @Bean("googleSheetsTool")
    @ConditionalOnMissingBean(name = ["googleSheetsTool"])
    fun googleSheetsTool(credentialProvider: GoogleCredentialProvider): ToolCallback =
        GoogleSheetsTool(credentialProvider)

    @Bean("googleDriveTool")
    @ConditionalOnMissingBean(name = ["googleDriveTool"])
    fun googleDriveTool(credentialProvider: GoogleCredentialProvider): ToolCallback =
        GoogleDriveTool(credentialProvider)

    @Bean("googleGmailTool")
    @ConditionalOnMissingBean(name = ["googleGmailTool"])
    fun googleGmailTool(credentialProvider: GoogleCredentialProvider): ToolCallback =
        GoogleGmailTool(credentialProvider)
}

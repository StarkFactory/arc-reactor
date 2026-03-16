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
 * Google Workspace 통합 모듈 자동 설정.
 *
 * `arc.reactor.google.enabled=true`일 때 활성화된다.
 * 모든 빈은 `@ConditionalOnMissingBean`으로 선언되어 사용자 정의 구현으로 교체 가능하다.
 *
 * 인증에는 Domain-Wide Delegation이 활성화된 Service Account JSON 키가 필요하다.
 * 설정 프로퍼티:
 * - `arc.reactor.google.service-account-key-path`
 * - `arc.reactor.google.impersonate-user`
 *
 * @see GoogleWorkspaceProperties
 * @see GoogleCredentialProvider
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

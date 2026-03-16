package com.arc.reactor.google

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.FileInputStream

/**
 * Service Account + Domain-Wide Delegation을 사용하여 Google OAuth2 자격 증명을 제공한다.
 *
 * Service Account는 Google Admin Console에서 Domain-Wide Delegation이 활성화되어 있어야 하며,
 * 필요한 API 스코프가 해당 Service Account의 클라이언트 ID에 승인되어 있어야 한다.
 *
 * @see GoogleWorkspaceAutoConfiguration 이 프로바이더를 빈으로 등록하는 자동 설정
 * @see GoogleWorkspaceProperties 인증 설정 프로퍼티
 */
class GoogleCredentialProvider(private val properties: GoogleWorkspaceProperties) {

    /**
     * 설정된 사용자를 위임(impersonate)하는 스코프 지정 GoogleCredentials를 반환한다.
     *
     * @param scopes 대상 Google API에 필요한 OAuth2 스코프
     * @throws IllegalArgumentException 필수 프로퍼티가 미설정된 경우
     */
    fun getCredentials(scopes: List<String>): GoogleCredentials {
        require(properties.serviceAccountKeyPath.isNotBlank()) {
            "arc.reactor.google.service-account-key-path must be configured"
        }
        require(properties.impersonateUser.isNotBlank()) {
            "arc.reactor.google.impersonate-user must be configured"
        }
        return FileInputStream(properties.serviceAccountKeyPath).use { stream ->
            ServiceAccountCredentials
                .fromStream(stream)
                .createDelegated(properties.impersonateUser)
                .createScoped(scopes)
        }
    }
}

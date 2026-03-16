package com.arc.reactor.google

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Google Workspace 통합 모듈 설정 프로퍼티.
 *
 * 프리픽스: `arc.reactor.google`
 *
 * Domain-Wide Delegation이 적용된 Service Account를 사용하여 인증한다.
 * Service Account는 Google Admin Console에서 접근이 허가되어 있어야 한다.
 *
 * @see GoogleCredentialProvider 자격 증명 생성
 * @see GoogleWorkspaceAutoConfiguration 자동 설정
 */
@ConfigurationProperties(prefix = "arc.reactor.google")
data class GoogleWorkspaceProperties(
    /** Google Workspace 통합 활성화 여부. 기본값: false (명시적 opt-in 필요). */
    val enabled: Boolean = false,

    /** 파일 시스템 상의 Service Account JSON 키 파일 경로 */
    val serviceAccountKeyPath: String = "",

    /** Domain-Wide Delegation을 통해 위임(impersonate)할 Google Workspace 사용자 이메일 */
    val impersonateUser: String = "",

    /** Google Workspace 도메인 (예: company.com) */
    val domain: String = ""
)

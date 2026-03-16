package com.arc.reactor.errorreport.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 프로덕션 서버에서 전송되는 오류 리포트 요청.
 *
 * @property stackTrace 오류 스택 트레이스 (필수, 최대 50,000자)
 * @property serviceName 오류가 발생한 서비스명 (필수)
 * @property repoSlug 소스 코드 리포지토리 slug (필수, 코드 분석에 사용)
 * @property slackChannel 분석 결과를 전송할 Slack 채널 (필수)
 * @property environment 배포 환경 (선택, 예: production, staging)
 * @property timestamp 오류 발생 시각 (선택)
 * @property metadata 추가 메타데이터 (선택)
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
 * 오류 리포트 접수 확인 즉시 응답.
 *
 * @property accepted 접수 성공 여부
 * @property requestId 이 오류 리포트 요청의 고유 식별자 (비동기 처리 추적용)
 */
data class ErrorReportResponse(
    val accepted: Boolean,
    val requestId: String
)

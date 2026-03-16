package com.arc.reactor.prompt

import java.time.Instant

/**
 * 프롬프트 템플릿 — 버전 관리된 시스템 프롬프트의 명명된 컨테이너.
 *
 * 템플릿은 고유한 [name]을 가지며 여러 [PromptVersion]을 포함한다.
 * 템플릿당 최대 하나의 버전만 [VersionStatus.ACTIVE] 상태일 수 있다.
 *
 * WHY: 시스템 프롬프트를 버전 관리하여 프롬프트 변경 이력을 추적하고,
 * PromptLab A/B 테스트에서 다른 버전을 비교할 수 있게 한다.
 *
 * @param id 고유 식별자 (UUID)
 * @param name 고유 이름 키 (예: "customer-support", "code-reviewer")
 * @param description 이 템플릿의 목적에 대한 설명
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see PromptVersion 프롬프트 버전
 * @see PromptTemplateStore 템플릿 저장소
 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 프롬프트 버전 — [PromptTemplate]의 특정 버전.
 *
 * 버전은 템플릿별로 순차 번호(1, 2, 3...)가 부여된다.
 * 상태 전이: [VersionStatus.DRAFT] -> [VersionStatus.ACTIVE] -> [VersionStatus.ARCHIVED].
 *
 * WHY: 버전 관리를 통해 프롬프트 변경 이력을 추적하고,
 * 문제 발생 시 이전 버전으로 롤백할 수 있다.
 *
 * @param id 고유 식별자 (UUID)
 * @param templateId [PromptTemplate.id]에 대한 외래 키
 * @param version 순차 버전 번호 (템플릿별 자동 증가)
 * @param content LLM에 전송되는 실제 시스템 프롬프트 텍스트
 * @param status 현재 수명주기 상태
 * @param changeLog 이 버전의 변경 설명
 * @param createdAt 생성 시각
 */
data class PromptVersion(
    val id: String,
    val templateId: String,
    val version: Int,
    val content: String,
    val status: VersionStatus = VersionStatus.DRAFT,
    val changeLog: String = "",
    val createdAt: Instant = Instant.now()
)

/**
 * 버전 수명주기 상태.
 *
 * 각 템플릿은 최대 하나의 [ACTIVE] 버전만 가질 수 있다.
 * 새 버전을 활성화하면 이전 활성 버전이 자동으로 아카이브된다.
 *
 * WHY: 단일 활성 버전 제약으로 시스템 프롬프트의 명확한 현재 상태를 보장한다.
 */
enum class VersionStatus {
    /** 작업 중 — 아직 운영에 배포되지 않음 */
    DRAFT,

    /** 운영에서 현재 제공 중 (템플릿당 최대 하나) */
    ACTIVE,

    /** 퇴역 — 이력을 위해 보존되나 더 이상 사용하지 않음 */
    ARCHIVED
}

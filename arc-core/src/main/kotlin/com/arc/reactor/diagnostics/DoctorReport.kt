package com.arc.reactor.diagnostics

import java.time.Instant

/**
 * [DoctorDiagnostics]가 생성하는 진단 보고서.
 *
 * Arc Reactor의 opt-in 기능들(R225~R235)이 현재 어떻게 활성화되어 있는지, 예상대로 동작
 * 하는지, 권장 설정과 어긋나는 부분은 없는지 요약한다.
 *
 * ## 사용 목적
 *
 * - 배포 후 설정 검증: `arc.reactor.approval.*` / `arc.reactor.tool.response.summarizer.*`
 *   / `arc.reactor.evaluation.metrics.enabled` 등 속성이 의도대로 반영되었는지 확인
 * - 트러블슈팅: "PII 마스킹이 적용 안 된 것 같은데?" → 보고서 한 번으로 원인 파악
 * - 관측 dashboard 초기 구성: 어떤 메트릭이 기록되고 있는지 목록 확인
 *
 * ## 구조
 *
 * - [sections]: 각 subsystem별 섹션 (Approval, ACI, Evaluation, Prompt Layer)
 * - [DoctorSection.status]: 섹션 전체 상태 (OK / WARN / ERROR / SKIPPED)
 * - [DoctorSection.checks]: 섹션 내 개별 체크 결과 리스트
 *
 * @property generatedAt 보고서 생성 시각
 * @property sections 진단 섹션 리스트
 */
data class DoctorReport(
    val generatedAt: Instant,
    val sections: List<DoctorSection>
) {

    /**
     * 모든 섹션이 정상(`OK` 또는 `SKIPPED`)이면 `true`.
     * `WARN` 또는 `ERROR`가 하나라도 있으면 `false`.
     */
    fun allHealthy(): Boolean = sections.all {
        it.status == DoctorStatus.OK || it.status == DoctorStatus.SKIPPED
    }

    /** `ERROR` 상태의 섹션이 하나라도 있는지 확인. */
    fun hasErrors(): Boolean = sections.any { it.status == DoctorStatus.ERROR }

    /** `WARN` 또는 `ERROR` 상태의 섹션이 있는지 확인. */
    fun hasWarningsOrErrors(): Boolean = sections.any {
        it.status == DoctorStatus.WARN || it.status == DoctorStatus.ERROR
    }

    /**
     * 사람이 읽을 수 있는 한 줄 요약.
     * 예: "4 섹션 — OK 3, WARN 1 (Approval: PII 마스킹 비활성)"
     */
    fun summary(): String {
        val total = sections.size
        val byStatus = sections.groupingBy { it.status }.eachCount()
        val counts = DoctorStatus.values()
            .filter { (byStatus[it] ?: 0) > 0 }
            .joinToString(", ") { "${it.name} ${byStatus[it]}" }
        return "$total 섹션 — $counts"
    }
}

/**
 * 단일 subsystem 진단 섹션.
 *
 * @property name 섹션 이름 (예: "Approval Context Resolver")
 * @property status 섹션 전체 상태
 * @property checks 개별 체크 결과 리스트
 * @property message 섹션 요약 메시지 (한 줄)
 */
data class DoctorSection(
    val name: String,
    val status: DoctorStatus,
    val checks: List<DoctorCheck>,
    val message: String
)

/**
 * 섹션 내 개별 체크 결과.
 *
 * @property name 체크 이름 (예: "resolver bean", "PII 마스킹")
 * @property status 체크 상태
 * @property detail 상세 메시지 (사람이 읽을 수 있는 설명)
 */
data class DoctorCheck(
    val name: String,
    val status: DoctorStatus,
    val detail: String
)

/**
 * 진단 상태 분류.
 */
enum class DoctorStatus {
    /** 정상 동작. */
    OK,

    /** 기능이 활성화되지 않음 (의도된 상태일 수도 있음). */
    SKIPPED,

    /** 경고 — 권장 설정과 어긋나거나 부분적 문제. */
    WARN,

    /** 오류 — 기능이 등록되었으나 제대로 동작하지 않음. */
    ERROR
}

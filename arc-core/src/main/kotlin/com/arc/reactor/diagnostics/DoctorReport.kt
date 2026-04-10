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

    /**
     * R239: 전체 진단 보고서를 사람이 읽을 수 있는 **멀티라인 한국어 텍스트**로 렌더링한다.
     *
     * ## 용도
     *
     * - 애플리케이션 기동 시 `logger.info { report.toHumanReadable() }`
     * - 관리자 CLI/REPL 출력
     * - 정기 cron으로 진단 결과를 파일에 dump
     *
     * ## 형식
     *
     * ```
     * === Arc Reactor Doctor Report ===
     * 생성 시각: 2026-04-11T12:00:00Z
     * 요약: 5 섹션 — OK 3, WARN 1, SKIPPED 1
     * 전체 상태: 경고 포함
     *
     * [OK] Approval Context Resolver
     *      활성 (RedactedApprovalContextResolver)
     *      [OK] resolver bean: 등록됨: RedactedApprovalContextResolver
     *      [OK] PII 마스킹 (R228): 활성
     *      [OK] sample resolve: 정상 응답
     *
     * [WARN] Tool Response Summarizer
     *      활성 (DefaultToolResponseSummarizer)
     *      [OK] summarizer bean: 등록됨: DefaultToolResponseSummarizer
     *      [WARN] PII 마스킹 (R231): 비활성 — 요약 결과에 PII 노출 위험
     *      [OK] sample summarize: 정상 응답
     * ```
     *
     * @param includeDetails `true`이면 각 섹션의 개별 check 상세 포함 (기본값)
     * @param lineSeparator 줄바꿈 문자 (기본 `\n`)
     */
    fun toHumanReadable(
        includeDetails: Boolean = true,
        lineSeparator: String = "\n"
    ): String = buildString {
        append("=== Arc Reactor Doctor Report ===").append(lineSeparator)
        append("생성 시각: ").append(generatedAt).append(lineSeparator)
        append("요약: ").append(summary()).append(lineSeparator)
        append("전체 상태: ").append(overallStatusLabel()).append(lineSeparator)
        append(lineSeparator)

        for ((index, section) in sections.withIndex()) {
            append('[').append(section.status.shortCode()).append("] ")
            append(section.name).append(lineSeparator)
            append("     ").append(section.message).append(lineSeparator)
            if (includeDetails) {
                for (check in section.checks) {
                    append("     [").append(check.status.shortCode()).append("] ")
                    append(check.name).append(": ").append(check.detail).append(lineSeparator)
                }
            }
            if (index < sections.size - 1) {
                append(lineSeparator)
            }
        }
    }

    /**
     * R239: Slack 메시지용 **축약 마크다운** 포맷.
     *
     * Slack의 mrkdwn 문법(`*bold*`, `_italic_`, `>quote`)을 사용하며, 섹션별 상태 라인만
     * 포함한다. 상세 check는 생략하여 Slack 메시지 길이 제한을 여유롭게 유지한다.
     *
     * ## 형식 예시
     *
     * ```
     * *Arc Reactor Doctor Report*
     * > 5 섹션 — OK 3, WARN 1, SKIPPED 1
     *
     * `[OK]` *Approval Context Resolver* — 활성 (RedactedApprovalContextResolver)
     * `[WARN]` *Tool Response Summarizer* — 활성 (DefaultToolResponseSummarizer)
     * `[SKIP]` *Evaluation Metrics Collector* — 비활성
     * `[OK]` *Response Cache* — 활성 (RedisSemanticResponseCache, tier=semantic)
     * `[OK]` *Prompt Layer Registry* — 무결성 확인됨
     * ```
     *
     * ## 사용 예
     *
     * ```kotlin
     * if (report.hasWarningsOrErrors()) {
     *     slackClient.postMessage(channel = "#ops", text = report.toSlackMarkdown())
     * }
     * ```
     */
    fun toSlackMarkdown(): String = buildString {
        append("*Arc Reactor Doctor Report*\n")
        append("> ").append(summary()).append('\n')
        append('\n')
        for (section in sections) {
            append('`').append('[').append(section.status.shortCode()).append(']').append('`')
            append(" *").append(section.name).append("* — ")
            append(section.message).append('\n')
        }
    }.trimEnd()

    /**
     * 전체 진단의 overall 상태 라벨을 반환한다.
     *
     * - `hasErrors()` → "오류 포함"
     * - `hasWarningsOrErrors()` → "경고 포함"
     * - 그 외 → "정상"
     */
    fun overallStatusLabel(): String = when {
        hasErrors() -> "오류 포함"
        hasWarningsOrErrors() -> "경고 포함"
        else -> "정상"
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
    ERROR;

    /**
     * 사람이 읽을 수 있는 한국어 라벨.
     *
     * R239: 로그, Slack 메시지, 대시보드 등에서 사용.
     */
    fun koreanLabel(): String = when (this) {
        OK -> "정상"
        SKIPPED -> "비활성"
        WARN -> "경고"
        ERROR -> "오류"
    }

    /**
     * 로그 프리픽스용 짧은 코드 (4자 이내).
     *
     * R239: `[OK]`, `[SKIP]`, `[WARN]`, `[ERR]` 형태로 프리픽스에 사용.
     */
    fun shortCode(): String = when (this) {
        OK -> "OK"
        SKIPPED -> "SKIP"
        WARN -> "WARN"
        ERROR -> "ERR"
    }
}

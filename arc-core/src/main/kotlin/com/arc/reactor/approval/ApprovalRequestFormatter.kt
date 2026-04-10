package com.arc.reactor.approval

/**
 * R240: [ToolApprovalRequest]와 [ApprovalSummary]를 사람이 읽을 수 있는 한국어 텍스트로
 * 렌더링하는 확장 함수 모음.
 *
 * ## 설계 원칙
 *
 * - **순수 additive** — 기존 필드/메서드 변경 없음. 확장 함수로만 제공
 * - **opt-in** — 사용자가 명시적으로 `toHumanReadable()` / `toSlackMarkdown()`을 호출해야만 동작
 * - **캐시 영향 없음** — 시스템 프롬프트 경로와 무관
 * - **컨텍스트 영향 없음** — 대화/스레드 컨텍스트(`ConversationManager`) 경로와 무관
 * - **MCP 영향 없음** — `ArcToolCallbackAdapter`, `McpToolRegistry` 경로와 무관
 *
 * ## 포맷 종류
 *
 * | 함수 | 용도 |
 * |------|------|
 * | [toHumanReadable] | CLI / 로그 / 관리자 화면용 멀티라인 텍스트 |
 * | [toSlackMarkdown] | Slack 메시지용 축약 mrkdwn |
 * | [toApprovalPromptLine] | 한 줄 승인 프롬프트 (예: REPL "이 작업을 승인하시겠습니까?") |
 *
 * ## 설계 평행
 *
 * `DoctorReport.toHumanReadable()` / `toSlackMarkdown()` (R239)과 동일한 패턴을 따른다.
 * 승인 UX와 진단 UX는 서로 다른 영역이지만, 사용자에게 상태/정보를 명확히 전달한다는
 * 공통 목표를 공유하므로 포맷터 설계도 일관되게 유지한다.
 */

/**
 * R240: 승인 요청을 **CLI/관리자 화면용 멀티라인 한국어 텍스트**로 렌더링한다.
 *
 * 컨텍스트가 없는 기존 요청도 정상 처리되며, 이 경우 4단계 구조화 섹션이 생략된다.
 *
 * ## 출력 예시
 *
 * ```
 * === 도구 승인 요청 ===
 * ID: req-abc123
 * 실행 ID: run-xyz789
 * 사용자: stark@example.com
 * 도구: jira_transition_issue
 * 요청 시각: 2026-04-11T12:30:00Z
 *
 * [컨텍스트]
 * 사유: 이슈 상태 전이 (완료)
 * 행동: JAR-36 이슈를 'Done' 상태로 전이
 * 영향: 1 Jira 이슈
 * 복구: 복구 가능
 *
 * [인수]
 *   issueIdOrKey: "JAR-36"
 *   transitionId: "31"
 * ```
 *
 * @param includeArguments `true`이면 도구 인수 상세 포함 (기본값)
 * @param lineSeparator 줄바꿈 문자 (기본 `\n`)
 */
fun ToolApprovalRequest.toHumanReadable(
    includeArguments: Boolean = true,
    lineSeparator: String = "\n"
): String = buildString {
    append("=== 도구 승인 요청 ===").append(lineSeparator)
    append("ID: ").append(id).append(lineSeparator)
    append("실행 ID: ").append(runId).append(lineSeparator)
    append("사용자: ").append(userId).append(lineSeparator)
    append("도구: ").append(toolName).append(lineSeparator)
    append("요청 시각: ").append(requestedAt).append(lineSeparator)

    val ctx = context
    if (ctx != null && ctx.hasAnyInformation()) {
        append(lineSeparator)
        append("[컨텍스트]").append(lineSeparator)
        ctx.reason?.takeIf { it.isNotBlank() }?.let {
            append("사유: ").append(it).append(lineSeparator)
        }
        ctx.action?.takeIf { it.isNotBlank() }?.let {
            append("행동: ").append(it).append(lineSeparator)
        }
        ctx.impactScope?.takeIf { it.isNotBlank() }?.let {
            append("영향: ").append(it).append(lineSeparator)
        }
        if (ctx.reversibility != Reversibility.UNKNOWN) {
            append("복구: ").append(ctx.reversibility.koreanLabel()).append(lineSeparator)
        }
    }

    if (includeArguments && arguments.isNotEmpty()) {
        append(lineSeparator)
        append("[인수]").append(lineSeparator)
        for ((key, value) in arguments) {
            append("  ").append(key).append(": ").append(formatArgumentValue(value)).append(lineSeparator)
        }
    }
}.trimEnd()

/**
 * R240: 승인 요청을 **Slack 메시지용 축약 mrkdwn**으로 렌더링한다.
 *
 * Slack mrkdwn 문법(`*bold*`, `_italic_`, `>quote`, `` `code` ``)을 사용하며,
 * 장문 인수는 생략하여 메시지 길이 제한을 여유롭게 유지한다.
 *
 * ## 출력 예시
 *
 * ```
 * *도구 승인 요청* — `jira_transition_issue`
 * > 이슈 상태 전이 (완료) · JAR-36 이슈를 'Done' 상태로 전이 · 1 Jira 이슈 · 복구 가능
 * _요청자: stark@example.com_ · _ID: req-abc123_
 * ```
 *
 * 컨텍스트가 없으면 quote 라인이 생략된다.
 */
fun ToolApprovalRequest.toSlackMarkdown(): String = buildString {
    append("*도구 승인 요청* — `").append(toolName).append('`').append('\n')

    val ctx = context
    val contextLine = ctx?.toOneLineSummary().orEmpty()
    if (contextLine.isNotBlank()) {
        append("> ").append(contextLine).append('\n')
    }

    append("_요청자: ").append(userId).append("_ · _ID: ").append(id).append('_')
}

/**
 * R240: 승인 요청을 **한 줄 프롬프트**로 렌더링한다.
 *
 * REPL이나 간단한 로그에서 "이 작업을 승인하시겠습니까?"와 같은 형태로 표시할 때 사용한다.
 *
 * ## 출력 예시
 *
 * ```
 * [복구 가능] jira_transition_issue — JAR-36 이슈를 'Done' 상태로 전이 (1 Jira 이슈)
 * ```
 *
 * 컨텍스트가 없으면 도구 이름만 표시된다:
 * ```
 * jira_transition_issue
 * ```
 */
fun ToolApprovalRequest.toApprovalPromptLine(): String {
    val ctx = context
    if (ctx == null || !ctx.hasAnyInformation()) {
        return toolName
    }
    return buildString {
        if (ctx.reversibility != Reversibility.UNKNOWN) {
            append('[').append(ctx.reversibility.koreanLabel()).append("] ")
        }
        append(toolName)
        val parts = mutableListOf<String>()
        ctx.action?.takeIf { it.isNotBlank() }?.let(parts::add)
        ctx.impactScope?.takeIf { it.isNotBlank() }?.let { parts += "($it)" }
        if (parts.isNotEmpty()) {
            append(" — ").append(parts.joinToString(" "))
        }
    }
}

/**
 * R240: [ApprovalSummary]를 **관리자 REST/CLI용 한국어 텍스트**로 렌더링한다.
 *
 * [ToolApprovalRequest.toHumanReadable]과 유사하지만, 현재 상태([ApprovalStatus]) 정보가
 * 포함되며 타임아웃 필드는 없다.
 */
fun ApprovalSummary.toHumanReadable(
    includeArguments: Boolean = true,
    lineSeparator: String = "\n"
): String = buildString {
    append("=== 승인 요약 ===").append(lineSeparator)
    append("ID: ").append(id).append(lineSeparator)
    append("실행 ID: ").append(runId).append(lineSeparator)
    append("사용자: ").append(userId).append(lineSeparator)
    append("도구: ").append(toolName).append(lineSeparator)
    append("상태: ").append(status.koreanLabel()).append(lineSeparator)
    append("요청 시각: ").append(requestedAt).append(lineSeparator)

    val ctx = context
    if (ctx != null && ctx.hasAnyInformation()) {
        append(lineSeparator)
        append("[컨텍스트]").append(lineSeparator)
        ctx.reason?.takeIf { it.isNotBlank() }?.let {
            append("사유: ").append(it).append(lineSeparator)
        }
        ctx.action?.takeIf { it.isNotBlank() }?.let {
            append("행동: ").append(it).append(lineSeparator)
        }
        ctx.impactScope?.takeIf { it.isNotBlank() }?.let {
            append("영향: ").append(it).append(lineSeparator)
        }
        if (ctx.reversibility != Reversibility.UNKNOWN) {
            append("복구: ").append(ctx.reversibility.koreanLabel()).append(lineSeparator)
        }
    }

    if (includeArguments && arguments.isNotEmpty()) {
        append(lineSeparator)
        append("[인수]").append(lineSeparator)
        for ((key, value) in arguments) {
            append("  ").append(key).append(": ").append(formatArgumentValue(value)).append(lineSeparator)
        }
    }
}.trimEnd()

/**
 * [ApprovalStatus]에 대한 한국어 라벨.
 *
 * R240: 포맷터가 사용자에게 현재 상태를 보여줄 때 사용한다.
 */
fun ApprovalStatus.koreanLabel(): String = when (this) {
    ApprovalStatus.PENDING -> "대기 중"
    ApprovalStatus.APPROVED -> "승인됨"
    ApprovalStatus.REJECTED -> "거부됨"
    ApprovalStatus.TIMED_OUT -> "타임아웃"
}

/**
 * 인수 값을 사람이 읽기 좋은 형태로 변환한다.
 *
 * - 문자열은 따옴표로 감싼다
 * - null은 `null` 문자열
 * - 그 외는 `toString()`
 * - 너무 긴 값(> 200자)은 `...`로 축약
 */
private fun formatArgumentValue(value: Any?): String {
    if (value == null) return "null"
    val text = when (value) {
        is String -> "\"$value\""
        else -> value.toString()
    }
    return if (text.length > 200) text.take(197) + "..." else text
}

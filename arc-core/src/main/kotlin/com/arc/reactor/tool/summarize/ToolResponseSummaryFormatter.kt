package com.arc.reactor.tool.summarize

/**
 * R241: [ToolResponseSummary]를 사람이 읽을 수 있는 한국어 텍스트로 렌더링하는 확장 함수 모음.
 *
 * ## 설계 원칙
 *
 * - **순수 additive** — 기존 필드/메서드 변경 없음. 확장 함수로만 제공
 * - **opt-in** — 사용자가 명시적으로 `toHumanReadable()` / `toSlackMarkdown()`을 호출해야만 동작
 * - **캐시 영향 없음** — 시스템 프롬프트 경로와 무관
 * - **컨텍스트 영향 없음** — 대화/스레드 컨텍스트 경로와 무관
 * - **MCP 영향 없음** — 원본 도구 응답(atlassian-mcp-server payload) 전혀 접근하지 않음
 *
 * ## 포맷 종류
 *
 * | 함수 | 용도 |
 * |------|------|
 * | [toHumanReadable] | CLI / 로그 / 관리자 화면용 멀티라인 텍스트 |
 * | [toSlackMarkdown] | Slack 메시지용 축약 mrkdwn |
 * | [toCompressionLine] | 한 줄 압축 지표 (예: "[LIST] 25건 · 88% 축약 (4096→512자)") |
 *
 * ## 설계 평행
 *
 * - R239 `DoctorReport.toHumanReadable()` / `toSlackMarkdown()`
 * - R240 `ToolApprovalRequest.toHumanReadable()` / `toSlackMarkdown()` / `toApprovalPromptLine()`
 * - **R241 `ToolResponseSummary.toHumanReadable()` / `toSlackMarkdown()` / `toCompressionLine()`**
 *
 * 세 포맷터가 동일한 패턴을 공유하여 사용자 멘탈 모델 일관성을 유지한다.
 */

/**
 * R241: 요약을 **CLI/관리자 화면용 멀티라인 한국어 텍스트**로 렌더링한다.
 *
 * ## 출력 예시
 *
 * ```
 * === 도구 응답 요약 ===
 * 도구: jira_search_issues
 * 전략: 목록 (LIST_TOP_N)
 * 원본 길이: 4,096자
 * 요약 길이: 512자
 * 압축률: 87%
 * 항목 수: 25
 * 주요 항목: JAR-36
 *
 * [요약 본문]
 * 1. JAR-36 — 버그 수정: ...
 * 2. JAR-42 — 기능 추가: ...
 * ...
 * ```
 *
 * @param toolName 호출된 도구 이름 (null이면 "도구:" 라인 생략)
 * @param includeBody `true`이면 요약 본문([text]) 포함 (기본값)
 * @param lineSeparator 줄바꿈 문자 (기본 `\n`)
 */
fun ToolResponseSummary.toHumanReadable(
    toolName: String? = null,
    includeBody: Boolean = true,
    lineSeparator: String = "\n"
): String = buildString {
    append("=== 도구 응답 요약 ===").append(lineSeparator)
    if (!toolName.isNullOrBlank()) {
        append("도구: ").append(toolName).append(lineSeparator)
    }
    append("전략: ").append(kind.koreanLabel()).append(" (").append(kind.name).append(')').append(lineSeparator)
    append("원본 길이: ").append(formatLength(originalLength)).append(lineSeparator)
    append("요약 길이: ").append(formatLength(text.length)).append(lineSeparator)
    append("압축률: ").append(compressionPercent()).append('%').append(lineSeparator)

    if (itemCount != null) {
        append("항목 수: ").append(itemCount).append(lineSeparator)
    }
    if (!primaryKey.isNullOrBlank()) {
        append("주요 항목: ").append(primaryKey).append(lineSeparator)
    }

    if (includeBody && text.isNotBlank()) {
        append(lineSeparator)
        append("[요약 본문]").append(lineSeparator)
        append(text).append(lineSeparator)
    }
}.trimEnd()

/**
 * R241: 요약을 **Slack 메시지용 축약 mrkdwn**으로 렌더링한다.
 *
 * Slack mrkdwn 문법(`*bold*`, `_italic_`, `>quote`, `` `code` ``)을 사용하며,
 * 긴 요약 본문은 quote 블록으로 감싸서 시각적으로 구분한다.
 *
 * ## 출력 예시
 *
 * ```
 * *도구 응답 요약* — `jira_search_issues`
 * `[LIST]` 25건 · 87% 축약 (4,096자 → 512자)
 * > 1. JAR-36 — 버그 수정: ...
 * > 2. JAR-42 — 기능 추가: ...
 * ```
 *
 * @param toolName 호출된 도구 이름 (null이면 타이틀만 출력)
 * @param includeBody `true`이면 요약 본문을 quote로 포함 (기본값)
 * @param maxBodyLines 본문 최대 라인 수 (기본 5, 초과 시 `_(+N행 생략)_` 표시)
 */
fun ToolResponseSummary.toSlackMarkdown(
    toolName: String? = null,
    includeBody: Boolean = true,
    maxBodyLines: Int = 5
): String = buildString {
    append("*도구 응답 요약*")
    if (!toolName.isNullOrBlank()) {
        append(" — `").append(toolName).append('`')
    }
    append('\n')

    append('`').append('[').append(kind.shortCode()).append(']').append('`')
    if (itemCount != null) {
        append(' ').append(itemCount).append("건")
    }
    append(" · ").append(compressionPercent()).append("% 축약 (")
    append(formatLength(originalLength)).append(" → ").append(formatLength(text.length)).append(')')

    if (includeBody && text.isNotBlank()) {
        append('\n')
        val lines = text.lines()
        val shown = lines.take(maxBodyLines)
        for (line in shown) {
            append("> ").append(line).append('\n')
        }
        if (lines.size > maxBodyLines) {
            append("_(+").append(lines.size - maxBodyLines).append("행 생략)_")
        } else {
            // 마지막 개행 제거
            setLength(length - 1)
        }
    }
}

/**
 * R241: 요약을 **한 줄 압축 지표**로 렌더링한다.
 *
 * 로그 한 줄, 이벤트 디스패치, 메트릭 태그 등에서 사용한다.
 *
 * ## 출력 예시
 *
 * ```
 * [LIST] 25건 · 87% 축약 (4,096자 → 512자)
 * [ERR] 87% 축약 (1,024자 → 128자)
 * [EMPTY] 0% 축약 (0자 → 0자)
 * [STRUCT] JAR-36 · 75% 축약 (1,000자 → 250자)
 * ```
 *
 * `itemCount`나 `primaryKey`가 있으면 포함, 없으면 생략한다.
 */
fun ToolResponseSummary.toCompressionLine(): String = buildString {
    append('[').append(kind.shortCode()).append(']')
    if (itemCount != null) {
        append(' ').append(itemCount).append("건")
    } else if (!primaryKey.isNullOrBlank()) {
        append(' ').append(primaryKey)
    }
    append(" · ").append(compressionPercent()).append("% 축약 (")
    append(formatLength(originalLength)).append(" → ").append(formatLength(text.length)).append(')')
}

/**
 * 문자 길이를 사람이 읽기 좋은 형태로 포맷한다.
 *
 * - 1000자 이상: 쉼표 천 단위 구분 + `자` 접미사
 * - 1000자 미만: 숫자 + `자` 접미사
 *
 * 예: `1000` → `"1,000자"`, `42` → `"42자"`
 */
private fun formatLength(length: Int): String {
    if (length < 1000) return "${length}자"
    // 쉼표 천 단위 구분 — Locale 독립적인 수동 포맷
    val digits = length.toString()
    val sb = StringBuilder()
    var count = 0
    for (i in digits.length - 1 downTo 0) {
        if (count > 0 && count % 3 == 0) sb.append(',')
        sb.append(digits[i])
        count++
    }
    return sb.reverse().append("자").toString()
}

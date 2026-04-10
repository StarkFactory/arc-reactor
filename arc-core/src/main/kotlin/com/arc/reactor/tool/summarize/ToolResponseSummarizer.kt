package com.arc.reactor.tool.summarize

/**
 * 도구 응답 요약 전략 — Agent-Computer Interface(ACI) 강화용.
 *
 * `docs/agent-work-directive.md` §3.2 Agent-Computer Interface 정비 원칙에 따라
 * 도구 출력을 "agent 친화적"으로 가공하여 요약한다. 이 계층의 목표는 **관측과 분석**
 * 이며, 원본 도구 응답 자체는 변경하지 않는다.
 *
 * ## 요약 전략 (kind 별)
 *
 * - **[SummaryKind.ERROR_CAUSE_FIRST]**: 에러 응답 — 원인을 앞에 배치
 * - **[SummaryKind.LIST_TOP_N]**: 리스트 응답 — "N건 중 상위 N건 요약"
 * - **[SummaryKind.TEXT_HEAD_TAIL]**: 긴 텍스트 — 앞 + 뒤 발췌
 * - **[SummaryKind.TEXT_FULL]**: 짧은 텍스트 — 전체 그대로
 * - **[SummaryKind.STRUCTURED]**: 구조화 객체 — 주요 필드만 요약
 *
 * ## MCP 호환성 최우선
 *
 * 이 계층은 **atlassian-mcp-server 응답의 원본 필드를 절대 삭제/변환하지 않는다**.
 * 요약은 원본과 별도로 [HookContext][com.arc.reactor.hook.model.HookContext.metadata]에만
 * 저장되며, 도구 응답이 LLM에 전달되는 경로는 전혀 변경되지 않는다.
 *
 * ## Redis 캐시 영향
 *
 * 0. `systemPrompt`, `scopeFingerprint`, `CacheKeyBuilder` 전혀 미수정.
 *
 * ## 컨텍스트 관리 영향
 *
 * 0. `MemoryStore`, `ConversationMessageTrimmer`, Slack 스레드 경로 미수정.
 * 요약은 관측 레이어로 히스토리에 섞이지 않는다.
 *
 * @see DefaultToolResponseSummarizer 기본 휴리스틱 구현체
 * @see ToolResponseSummarizerHook AfterToolCallHook 어댑터
 */
interface ToolResponseSummarizer {
    /**
     * 도구 응답을 요약한다.
     *
     * @param toolName 호출된 도구 이름
     * @param rawPayload 원본 도구 출력 (sanitize 후)
     * @param success 도구 호출 성공 여부
     * @return 요약 객체 또는 null (요약 불필요/불가 시)
     */
    fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary?
}

/**
 * 도구 응답 요약 결과.
 *
 * @property text 사람이 읽을 수 있는 요약 문자열
 * @property kind 요약 전략 분류
 * @property originalLength 원본 payload의 문자 길이
 * @property itemCount 리스트 유형일 때 항목 수 (선택)
 * @property primaryKey 리스트/구조화 객체에서 식별 가능한 첫 항목 (선택)
 */
data class ToolResponseSummary(
    val text: String,
    val kind: SummaryKind,
    val originalLength: Int,
    val itemCount: Int? = null,
    val primaryKey: String? = null
) {
    /**
     * R241: 원본 대비 요약 텍스트의 압축률(%)을 반환한다.
     *
     * 공식: `(originalLength - text.length) / originalLength * 100`
     *
     * - 원본이 0이면 0을 반환 (divide-by-zero 방지)
     * - 요약이 원본보다 긴 경우 음수를 반환 (드물지만 가능)
     * - 소수점을 버리고 정수로 반환 (로그/UI 표기에 적합)
     *
     * 예시:
     * - 원본 1000자 → 요약 250자 = 75%
     * - 원본 4096자 → 요약 512자 = 87%
     * - 원본 0자 → 0%
     *
     * @return 압축률 정수 (0~100 사이가 일반적, 음수 가능)
     */
    fun compressionPercent(): Int {
        if (originalLength <= 0) return 0
        val saved = originalLength - text.length
        return (saved.toDouble() / originalLength.toDouble() * 100).toInt()
    }
}

/**
 * 요약 전략 분류.
 */
enum class SummaryKind {
    /** 에러 응답 — 원인을 앞에 배치 */
    ERROR_CAUSE_FIRST,

    /** 리스트 응답 — 상위 N건 + 전체 개수 */
    LIST_TOP_N,

    /** 긴 텍스트 — 앞/뒤 발췌 */
    TEXT_HEAD_TAIL,

    /** 짧은 텍스트 — 전체 포함 */
    TEXT_FULL,

    /** 구조화 객체 — 주요 필드 요약 */
    STRUCTURED,

    /** 빈 응답 */
    EMPTY;

    /**
     * R241: 사람이 읽을 수 있는 한국어 라벨.
     *
     * CLI/로그/Slack 메시지 등에서 사용자에게 요약 전략을 표시할 때 사용한다.
     */
    fun koreanLabel(): String = when (this) {
        ERROR_CAUSE_FIRST -> "에러"
        LIST_TOP_N -> "목록"
        TEXT_HEAD_TAIL -> "긴 텍스트"
        TEXT_FULL -> "짧은 텍스트"
        STRUCTURED -> "구조화"
        EMPTY -> "빈 응답"
    }

    /**
     * R241: 로그 프리픽스용 짧은 코드 (5자 이내).
     *
     * `[ERR]`, `[LIST]`, `[HEAD]`, `[FULL]`, `[STRUCT]`, `[EMPTY]` 형태로 프리픽스에 사용.
     */
    fun shortCode(): String = when (this) {
        ERROR_CAUSE_FIRST -> "ERR"
        LIST_TOP_N -> "LIST"
        TEXT_HEAD_TAIL -> "HEAD"
        TEXT_FULL -> "FULL"
        STRUCTURED -> "STRUCT"
        EMPTY -> "EMPTY"
    }
}

/**
 * 아무 동작도 하지 않는 기본 [ToolResponseSummarizer] 구현체.
 *
 * 자동 구성 기본값. `arc.reactor.tool.response.summarizer.enabled=true`로 활성화하지 않는 한
 * 이 구현체가 주입된다.
 */
object NoOpToolResponseSummarizer : ToolResponseSummarizer {
    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? = null
}

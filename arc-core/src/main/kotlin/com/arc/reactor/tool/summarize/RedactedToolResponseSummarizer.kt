package com.arc.reactor.tool.summarize

/**
 * [ToolResponseSummarizer] 데코레이터 — 결과 [ToolResponseSummary]의 텍스트 필드에서
 * 민감 정보(PII)를 정규식 기반으로 마스킹한다.
 *
 * R228 [com.arc.reactor.approval.RedactedApprovalContextResolver]와 **완전히 평행한 패턴**
 * 을 ACI(Agent-Computer Interface) 요약 계층에 적용한 것이다.
 *
 * ## 배경
 *
 * R223의 `DefaultToolResponseSummarizer`는 도구 응답을 요약하며 식별자 필드(`key`/`id`/
 * `issueKey`/`title`/`name` 등)를 [ToolResponseSummary.primaryKey]로 추출한다. 문제는 이
 * 필드들이 다음과 같이 PII를 포함할 수 있다는 점이다:
 *
 * - Jira 담당자 이름/이메일이 `assignee.email`에 들어있는 도구 응답
 * - 사용자 제공 JQL에 `reporter = user@company.com` 형태로 이메일 포함
 * - Confluence 페이지 제목에 개인 이름/전화번호 포함
 * - Bitbucket 커밋 메시지에 토큰이나 자격 증명이 실수로 포함된 경우
 *
 * 이 정보들이 `toolSummary_*` 메타데이터(R223)에 저장되면 감사 로그, 관측 대시보드,
 * 저장소 dump 등을 통해 의도치 않게 노출될 수 있다.
 *
 * R231은 **데코레이터 패턴**으로 기존 summarizer 결과를 후처리하여 [text] 및
 * [primaryKey] 필드를 자동 마스킹한다. 원본 summarizer 로직은 수정하지 않는다.
 *
 * ## 마스킹 대상 필드
 *
 * - [ToolResponseSummary.text] — 사람이 읽을 수 있는 요약 문자열
 * - [ToolResponseSummary.primaryKey] — 추출된 첫 식별자 (nullable)
 *
 * 다음 필드는 마스킹 대상이 **아니다**:
 * - [ToolResponseSummary.kind] — enum (텍스트 아님)
 * - [ToolResponseSummary.originalLength] — Int
 * - [ToolResponseSummary.itemCount] — Int? (숫자)
 *
 * ## 기본 패턴 (R228과 동일)
 *
 * - 이메일: `[\w.+-]+@[\w-]+(?:\.[\w-]+)+`
 * - Bearer 토큰: `Bearer\s+[A-Za-z0-9\-_.=]+` (IGNORE_CASE)
 * - Atlassian granular 토큰: `ATATT3xFfGF0[A-Za-z0-9\-_=]+`
 * - Slack 토큰: `xox[baprs]-[A-Za-z0-9-]+`
 * - 한국 휴대폰 (국내): `01[0-9]-\d{3,4}-\d{4}`
 * - 한국 휴대폰 (국제): `\+?82-10-\d{3,4}-\d{4}`
 * - 주민등록번호: `\d{6}-[1-4]\d{6}`
 *
 * R228과 동일한 패턴을 복사하여 사용한다. 향후 공통 유틸로 리팩토링 가능하나 현재는
 * 두 데코레이터의 독립성을 유지한다 (approval과 ACI가 서로 다른 PII 우려를 가질 수 있음).
 *
 * ## 사용 예
 *
 * 단일 summarizer 래핑:
 *
 * ```kotlin
 * @Bean
 * fun toolResponseSummarizer(): ToolResponseSummarizer =
 *     RedactedToolResponseSummarizer(DefaultToolResponseSummarizer())
 * ```
 *
 * R230 체인 + R231 마스킹 조합:
 *
 * ```kotlin
 * @Bean
 * fun toolResponseSummarizer(): ToolResponseSummarizer = RedactedToolResponseSummarizer(
 *     ChainedToolResponseSummarizer(
 *         JiraSpecificSummarizer(),
 *         DefaultToolResponseSummarizer()
 *     )
 * )
 * ```
 *
 * 사용자 정의 패턴 추가:
 *
 * ```kotlin
 * @Bean
 * fun toolResponseSummarizer(): ToolResponseSummarizer = RedactedToolResponseSummarizer(
 *     delegate = DefaultToolResponseSummarizer(),
 *     additionalPatterns = listOf(
 *         Regex("""INTERNAL-\d{8}"""),
 *         Regex("""SECRET_[A-F0-9]{32}""")
 *     )
 * )
 * ```
 *
 * ## Fail-Safe
 *
 * 정규식 매칭 중 예외 발생 시 원본 텍스트를 그대로 반환하는 것이 아니라, 안전을 위해
 * **`"[REDACTED]"`로 전체 대체**한다. 이는 "의심스럽다면 가린다"는 보안 원칙을 따른다.
 * R228과 동일한 정책이다.
 *
 * ## 3대 최상위 제약 준수
 *
 * - **MCP 호환성**: atlassian-mcp-server 도구 응답 원본 payload 전혀 미수정. 데코레이터는
 *   원본 summarizer가 만든 요약 객체만 후처리
 * - **Redis 캐시**: `systemPrompt` 미수정 → scopeFingerprint 불변
 * - **컨텍스트 관리**: `HookContext.metadata`에 저장되는 요약 객체의 텍스트 필드만 마스킹,
 *   `MemoryStore`/`Trimmer` 경로 미수정
 *
 * @param delegate 원본 summarizer (체인 가능)
 * @param additionalPatterns 사용자 정의 추가 패턴 (기본 패턴에 덧붙임)
 * @param replacement 마스킹 대체 문자열 (기본 `"***"`)
 *
 * @see ToolResponseSummarizer 인터페이스
 * @see DefaultToolResponseSummarizer 기본 휴리스틱 구현
 * @see ChainedToolResponseSummarizer 체인 유틸
 * @see com.arc.reactor.approval.RedactedApprovalContextResolver R228 동일 패턴의 Approval 버전
 */
class RedactedToolResponseSummarizer(
    private val delegate: ToolResponseSummarizer,
    additionalPatterns: List<Regex> = emptyList(),
    private val replacement: String = DEFAULT_REPLACEMENT
) : ToolResponseSummarizer {

    /** 기본 + 사용자 추가 패턴. 생성 시점에 합쳐서 고정. */
    private val patterns: List<Regex> = DEFAULT_PATTERNS + additionalPatterns

    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? {
        val original = delegate.summarize(toolName, rawPayload, success) ?: return null
        return original.copy(
            text = redactOrFallback(original.text) ?: original.text,
            primaryKey = redactOrFallback(original.primaryKey)
            // kind, originalLength, itemCount는 그대로 유지 (숫자/enum)
        )
    }

    /**
     * 문자열을 마스킹한다. null 입력은 null 반환 (pass-through).
     * 마스킹 과정에서 예외 발생 시 안전을 위해 [SAFE_FALLBACK]을 반환한다.
     */
    private fun redactOrFallback(input: String?): String? {
        if (input == null) return null
        return try {
            redact(input)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            SAFE_FALLBACK
        }
    }

    /** 모든 패턴을 순차적으로 적용하여 매칭 부분을 [replacement]로 대체한다. */
    private fun redact(input: String): String {
        var result = input
        for (pattern in patterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }

    /** 등록된 패턴 개수 (테스트/디버깅용). */
    fun patternCount(): Int = patterns.size

    companion object {
        /** 기본 대체 문자열 — R228과 동일. */
        const val DEFAULT_REPLACEMENT: String = "***"

        /** 마스킹 실패 시 안전 fallback — R228과 동일. */
        const val SAFE_FALLBACK: String = "[REDACTED]"

        /**
         * 기본 PII 패턴 목록 (R228 `RedactedApprovalContextResolver`와 동일).
         *
         * 두 데코레이터가 독립된 패턴 리스트를 유지하는 이유:
         * 1. Approval 컨텍스트와 도구 응답 요약은 서로 다른 PII 우려를 가질 수 있다
         * 2. 한쪽 패턴 변경이 다른 쪽에 의도치 않은 영향을 주지 않도록 격리
         * 3. 향후 공통 유틸(`arc-core/.../support/PiiPatterns.kt`)로 추출 여지를 남김
         */
        val DEFAULT_PATTERNS: List<Regex> = listOf(
            Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
            Regex("""Bearer\s+[A-Za-z0-9\-_.=]+""", RegexOption.IGNORE_CASE),
            Regex("""ATATT3xFfGF0[A-Za-z0-9\-_=]+"""),
            Regex("""xox[baprs]-[A-Za-z0-9-]+"""),
            Regex("""01[0-9]-\d{3,4}-\d{4}"""),
            Regex("""\+?82-10-\d{3,4}-\d{4}"""),
            Regex("""\d{6}-[1-4]\d{6}""")
        )
    }
}

package com.arc.reactor.approval

/**
 * [ApprovalContextResolver] 데코레이터 — 결과 [ApprovalContext]의 텍스트 필드에서
 * 민감 정보(PII: Personally Identifiable Information)를 정규식 기반으로 마스킹한다.
 *
 * ## 배경
 *
 * 승인 요청은 감사 로그, REST API 응답, 운영 대시보드 등 여러 경로에 노출된다. R225의
 * [AtlassianApprovalContextResolver]는 도구 인수를 그대로 `action`/`impactScope` 문자열에
 * 포함시키므로, 예를 들어 `jira_search_my_issues_by_text(requesterEmail=user@company.com)`
 * 같은 호출은 이메일을 감사 로그에 노출할 수 있다.
 *
 * R228은 이를 방지하기 위해 **데코레이터 패턴**으로 기존 리졸버 결과를 후처리한다. 원본
 * 리졸버 로직은 수정하지 않고, 반환된 [ApprovalContext]의 텍스트 필드만 정규식으로
 * 스캔하여 매칭 부분을 `***`로 대체한다.
 *
 * ## 마스킹 대상 필드
 *
 * - [ApprovalContext.reason]
 * - [ApprovalContext.action]
 * - [ApprovalContext.impactScope]
 *
 * [ApprovalContext.reversibility]는 enum이므로 마스킹 대상이 아니다.
 *
 * ## 기본 패턴
 *
 * [DEFAULT_PATTERNS]에 정의된 정규식:
 * - **이메일**: `[\w.+-]+@[\w-]+\.[\w.-]+`
 * - **Bearer 토큰**: `Bearer\s+[A-Za-z0-9\-_.=]+`
 * - **Atlassian API 토큰**: `ATATT3xFfGF0[A-Za-z0-9\-_=]+` (R220 gotcha 참조)
 * - **Slack 토큰**: `xox[baprs]-[A-Za-z0-9-]+`
 * - **전화번호 (한국)**: `01[0-9]-\d{3,4}-\d{4}`, `\+?82-10-\d{3,4}-\d{4}`
 * - **주민번호**: `\d{6}-[1-4]\d{6}` (첫 번째 자리 1-4만 매칭, 오탐 최소화)
 *
 * ## 사용 예
 *
 * 단일 리졸버 래핑:
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver =
 *     RedactedApprovalContextResolver(AtlassianApprovalContextResolver())
 * ```
 *
 * 체인 + 마스킹 조합 (R226+R228):
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = RedactedApprovalContextResolver(
 *     ChainedApprovalContextResolver(
 *         AtlassianApprovalContextResolver(),
 *         HeuristicApprovalContextResolver()
 *     )
 * )
 * ```
 *
 * 사용자 정의 패턴 추가:
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = RedactedApprovalContextResolver(
 *     delegate = AtlassianApprovalContextResolver(),
 *     additionalPatterns = listOf(
 *         Regex("""INTERNAL-\d{8}"""),           // 사내 식별자
 *         Regex("""ACCT-[A-F0-9]{12}""")         // 계정 ID
 *     )
 * )
 * ```
 *
 * ## 3대 최상위 제약 준수
 *
 * - **MCP 호환성**: atlassian-mcp-server 도구 인수/응답 전혀 미수정. 원본 delegate가
 *   만든 컨텍스트만 후처리
 * - **Redis 캐시**: `systemPrompt` 미수정 → scopeFingerprint 불변
 * - **컨텍스트 관리**: `HookContext`/`MemoryStore`/`ConversationMessageTrimmer` 미수정.
 *   마스킹된 컨텍스트는 `ToolApprovalRequest.context`에만 저장되어 대화 이력에 섞이지 않음
 *
 * ## Fail-Open
 *
 * 개별 정규식 매칭 실패는 해당 패턴만 건너뛴다. 전체 마스킹 실패 시 원본 [ApprovalContext]를
 * 그대로 반환하는 것이 아니라, 안전을 위해 **`"[REDACTED]"`로 전체를 대체한다**. 이는
 * "마스킹 실패 시 기본값이 안전한 쪽"이라는 보안 원칙을 따르기 위함이다.
 *
 * @param delegate 원본 리졸버 (체인 가능)
 * @param additionalPatterns 사용자 정의 추가 패턴 (기본 패턴에 덧붙임)
 * @param replacement 마스킹 대체 문자열 (기본 `"***"`)
 *
 * @see ApprovalContextResolver
 * @see AtlassianApprovalContextResolver
 * @see ChainedApprovalContextResolver
 */
class RedactedApprovalContextResolver(
    private val delegate: ApprovalContextResolver,
    additionalPatterns: List<Regex> = emptyList(),
    private val replacement: String = DEFAULT_REPLACEMENT
) : ApprovalContextResolver {

    /** 기본 + 사용자 추가 패턴. 생성 시점에 합쳐서 고정. */
    private val patterns: List<Regex> = DEFAULT_PATTERNS + additionalPatterns

    override fun resolve(
        toolName: String,
        arguments: Map<String, Any?>
    ): ApprovalContext? {
        val original = delegate.resolve(toolName, arguments) ?: return null
        return original.copy(
            reason = redactOrFallback(original.reason),
            action = redactOrFallback(original.action),
            impactScope = redactOrFallback(original.impactScope)
            // reversibility는 enum이므로 그대로 유지
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
            // CancellationException은 Regex 연산에서 발생하지 않지만 안전을 위해
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
        /** 기본 대체 문자열. */
        const val DEFAULT_REPLACEMENT: String = "***"

        /** 마스킹 실패 시 안전 fallback (전체 대체). */
        const val SAFE_FALLBACK: String = "[REDACTED]"

        /**
         * 기본 PII 패턴 목록.
         *
         * 각 패턴은 독립적으로 적용되므로, 여러 패턴이 동일 문자열에 매칭되면 모두 대체된다.
         */
        val DEFAULT_PATTERNS: List<Regex> = listOf(
            // 이메일 — 일반적인 형식 (국내 도메인 포함)
            Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+"""),
            // Bearer 토큰 (JWT 포함)
            Regex("""Bearer\s+[A-Za-z0-9\-_.=]+""", RegexOption.IGNORE_CASE),
            // Atlassian API 토큰 (granular 토큰 prefix, R220 Atlassian API Auth 메모 참조)
            Regex("""ATATT3xFfGF0[A-Za-z0-9\-_=]+"""),
            // Slack 토큰 (bot/user/app/refresh/service)
            Regex("""xox[baprs]-[A-Za-z0-9-]+"""),
            // 한국 휴대폰 번호 (하이픈 포함)
            Regex("""01[0-9]-\d{3,4}-\d{4}"""),
            // 국제 표기 한국 휴대폰 (+82-10-xxxx-xxxx)
            Regex("""\+?82-10-\d{3,4}-\d{4}"""),
            // 주민등록번호 (오탐 최소화를 위해 생년월일 + 첫 자리 1-4만)
            Regex("""\d{6}-[1-4]\d{6}""")
        )
    }
}

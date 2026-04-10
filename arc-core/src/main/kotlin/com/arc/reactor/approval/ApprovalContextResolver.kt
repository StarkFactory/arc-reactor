package com.arc.reactor.approval

/**
 * 도구 호출에 대한 [ApprovalContext]를 생성하는 전략 인터페이스.
 *
 * `docs/agent-work-directive.md` §3.1 Tool Approval UX 강화 원칙의 4단계 구조화
 * (왜 / 무엇을 / 영향 범위 / 복구 가능성) 를 도구 이름과 인수로부터 계산한다.
 *
 * ## opt-in 원칙
 *
 * 기본적으로 Arc Reactor는 **resolver 빈을 제공하지 않는다**. 사용자가 자신의 도구 특성에
 * 맞는 구현체를 `@Bean`으로 등록해야만 컨텍스트 enrich가 동작한다. 이는 기존 승인 경로와
 * 완전히 호환되는 additive 확장이다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * @Component
 * class MyApprovalContextResolver : ApprovalContextResolver {
 *     override fun resolve(toolName: String, arguments: Map<String, Any?>): ApprovalContext? {
 *         return when {
 *             toolName.startsWith("delete_") -> ApprovalContext(
 *                 reason = "파괴적 작업",
 *                 action = "$toolName 실행: ${arguments["target"]}",
 *                 impactScope = arguments["target"]?.toString() ?: "알 수 없음",
 *                 reversibility = Reversibility.IRREVERSIBLE
 *             )
 *             else -> null
 *         }
 *     }
 * }
 * ```
 *
 * ## MCP 호환성
 *
 * 이 인터페이스는 atlassian-mcp-server 연동 경로와 완전히 독립적이다. 승인 정책 판단
 * (`ToolApprovalPolicy.requiresApproval`)이 `true`를 반환할 때만 resolver가 호출되며,
 * 도구 인수(`arguments`)는 전혀 수정되지 않고 enrich된 메타데이터만 승인 UX로 전달된다.
 *
 * ## Redis 캐시 영향
 *
 * 없음. 승인 경로는 캐시 경로와 독립적이다. `systemPrompt`에 영향을 주지 않는다.
 *
 * @see ApprovalContext 4단계 구조화 컨텍스트 모델
 * @see HeuristicApprovalContextResolver 이름 기반 휴리스틱 샘플 구현체
 */
fun interface ApprovalContextResolver {

    /**
     * 도구 호출에 대한 [ApprovalContext]를 생성한다.
     *
     * @param toolName 승인 대상 도구 이름
     * @param arguments LLM이 생성한 도구 호출 인수
     * @return 컨텍스트 (null이면 enrich 건너뜀, 호출자는 [ApprovalContext.EMPTY] 대신 null 처리)
     */
    fun resolve(toolName: String, arguments: Map<String, Any?>): ApprovalContext?
}

/**
 * 이름 기반 휴리스틱 [ApprovalContextResolver] 샘플 구현체.
 *
 * 도구 이름의 prefix/keyword 로 복구 가능성과 영향 범위를 추정한다. 실제 프로덕션에서는
 * 각 팀이 자신의 도구 이름 규약에 맞춰 자체 resolver를 구현하는 것이 권장되며, 이 클래스는
 * **참고용 샘플**이자 테스트용 기본 fallback 이다.
 *
 * ## 분류 규칙 (우선순위 순)
 *
 * 1. `delete_*`, `remove_*`, `drop_*`, `purge_*` → IRREVERSIBLE
 * 2. `process_refund`, `charge_*`, `transfer_*`, `pay_*` → IRREVERSIBLE (금전)
 * 3. `create_*`, `add_*`, `insert_*` → REVERSIBLE (생성은 되돌릴 수 있음)
 * 4. `update_*`, `modify_*`, `patch_*`, `edit_*` → PARTIALLY_REVERSIBLE
 * 5. `approve_*`, `reject_*`, `transition_*` → REVERSIBLE (상태 전이)
 * 6. 기타 → UNKNOWN
 *
 * ## 기본 동작
 *
 * - [resolve]는 항상 non-null [ApprovalContext]를 반환한다 (정책 적용 시점에 호출되므로 의미 있는
 *   출력을 보장)
 * - [action]은 "toolName: {주요 인수}" 형태로 구성
 * - [impactScope]는 인수 중 id/key/target/name 필드에서 추출
 * - [reason]은 reversibility 분류에 따라 자동 생성
 *
 * ## 사용 예
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = HeuristicApprovalContextResolver()
 * ```
 */
class HeuristicApprovalContextResolver : ApprovalContextResolver {

    override fun resolve(toolName: String, arguments: Map<String, Any?>): ApprovalContext {
        val reversibility = classifyReversibility(toolName)
        val impactScope = extractImpactScope(arguments)
        return ApprovalContext(
            reason = buildReason(toolName, reversibility),
            action = buildAction(toolName, arguments),
            impactScope = impactScope,
            reversibility = reversibility
        )
    }

    /** 도구 이름에서 복구 가능성을 분류한다. */
    internal fun classifyReversibility(toolName: String): Reversibility {
        val lower = toolName.lowercase()
        return when {
            lower.startsWithAny("delete_", "remove_", "drop_", "purge_") -> Reversibility.IRREVERSIBLE
            lower.containsAny("refund", "charge", "transfer", "pay", "withdraw") -> Reversibility.IRREVERSIBLE
            lower.startsWithAny("create_", "add_", "insert_") -> Reversibility.REVERSIBLE
            lower.startsWithAny("update_", "modify_", "patch_", "edit_") -> Reversibility.PARTIALLY_REVERSIBLE
            lower.startsWithAny("approve_", "reject_", "transition_") -> Reversibility.REVERSIBLE
            else -> Reversibility.UNKNOWN
        }
    }

    /** 복구 가능성 분류에 따른 사람이 읽을 수 있는 사유 문자열. */
    internal fun buildReason(toolName: String, reversibility: Reversibility): String {
        return when (reversibility) {
            Reversibility.IRREVERSIBLE -> "파괴적 또는 비가역적 작업: $toolName"
            Reversibility.PARTIALLY_REVERSIBLE -> "부분적으로만 복구 가능한 수정 작업: $toolName"
            Reversibility.REVERSIBLE -> "복구 가능한 작업: $toolName"
            Reversibility.UNKNOWN -> "정책 상 승인이 필요한 작업: $toolName"
        }
    }

    /** 도구 이름과 인수를 결합한 action 문자열. */
    internal fun buildAction(toolName: String, arguments: Map<String, Any?>): String {
        val primaryArg = PRIMARY_ARG_KEYS.asSequence()
            .mapNotNull { key -> arguments[key]?.toString()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
        return if (primaryArg != null) "$toolName($primaryArg)" else toolName
    }

    /**
     * 인수에서 영향 범위를 추출한다.
     * id/key/target/name/projectKey/issueKey 등의 필드를 찾아서 가장 먼저 매칭된 값을 사용.
     */
    internal fun extractImpactScope(arguments: Map<String, Any?>): String? {
        return IMPACT_SCOPE_KEYS.asSequence()
            .mapNotNull { key -> arguments[key]?.toString()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }

    private fun String.startsWithAny(vararg prefixes: String): Boolean =
        prefixes.any { this.startsWith(it) }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }

    companion object {
        /** impactScope 추출 시 우선순위순으로 확인하는 인수 키. */
        private val IMPACT_SCOPE_KEYS = listOf(
            "issueKey", "projectKey", "pullRequestId", "prId", "repoSlug", "repo",
            "target", "id", "key", "name", "orderId", "userId", "accountId"
        )

        /** action 문자열에 포함할 주요 인수 키. */
        private val PRIMARY_ARG_KEYS = listOf(
            "issueKey", "projectKey", "pullRequestId", "prId", "repoSlug", "repo",
            "target", "id", "key", "name", "query", "jql"
        )
    }
}

package com.arc.reactor.approval

import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 여러 [ApprovalContextResolver]를 순서대로 시도하여 첫 non-null 결과를 반환하는 조합 유틸.
 *
 * R225의 [AtlassianApprovalContextResolver]와 R221의 [HeuristicApprovalContextResolver]를
 * 함께 사용하는 일반적인 패턴을 지원하기 위해 도입되었다.
 *
 * ## 동작
 *
 * 생성자에 전달된 리졸버 리스트를 순서대로 `resolve()`하여:
 * - **첫 번째 non-null 결과가 나오면 즉시 반환** (short-circuit)
 * - 리졸버가 null을 반환하면 다음 리졸버로 진행
 * - 리졸버가 **예외를 던지면 로깅 후 다음 리졸버로 진행** (fail-open)
 * - 모든 리졸버가 null(또는 예외)이면 최종 null 반환
 *
 * ## 사용 예
 *
 * 기본 사용:
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = ChainedApprovalContextResolver(
 *     AtlassianApprovalContextResolver(),    // jira_/confluence_/bitbucket_* 우선
 *     HeuristicApprovalContextResolver()     // 그 외 일반 휴리스틱 fallback
 * )
 * ```
 *
 * 3개 이상 체이닝:
 *
 * ```kotlin
 * @Bean
 * fun approvalContextResolver(): ApprovalContextResolver = ChainedApprovalContextResolver(
 *     MyCustomResolver(),                     // 1순위: 사내 도구
 *     AtlassianApprovalContextResolver(),     // 2순위: atlassian-mcp-server
 *     HeuristicApprovalContextResolver()      // 3순위: 이름 기반 휴리스틱
 * )
 * ```
 *
 * ## Fail-Open 보장
 *
 * 특정 리졸버가 예외를 던지더라도 체인 전체가 실패하지 않고 다음 리졸버로 진행한다.
 * 개별 리졸버의 버그가 승인 흐름 자체를 막지 않도록 보호한다. 모든 예외는 `logger.warn`
 * 으로 기록된다.
 *
 * ## Thread Safety
 *
 * 리졸버 리스트는 생성자에서 복사본으로 고정된다. 외부에서 원본 리스트를 변경해도 영향이
 * 없으며, 동시 호출도 안전하다 (각 리졸버의 `resolve()`가 thread-safe 한 경우).
 *
 * @param resolvers 시도 순서대로 나열된 리졸버 리스트 (빈 리스트 허용)
 *
 * @see ApprovalContextResolver 인터페이스
 * @see AtlassianApprovalContextResolver atlassian-mcp-server 전용 리졸버
 * @see HeuristicApprovalContextResolver 이름 기반 일반 휴리스틱
 */
class ChainedApprovalContextResolver(
    resolvers: List<ApprovalContextResolver>
) : ApprovalContextResolver {

    /** 생성 시점에 복사한 리졸버 리스트 — 이후 불변. */
    private val resolvers: List<ApprovalContextResolver> = resolvers.toList()

    /**
     * varargs 생성자 — 간결한 사용을 위해 제공.
     *
     * ```kotlin
     * ChainedApprovalContextResolver(
     *     AtlassianApprovalContextResolver(),
     *     HeuristicApprovalContextResolver()
     * )
     * ```
     */
    constructor(vararg resolvers: ApprovalContextResolver) : this(resolvers.toList())

    override fun resolve(
        toolName: String,
        arguments: Map<String, Any?>
    ): ApprovalContext? {
        if (resolvers.isEmpty()) return null
        for ((index, resolver) in resolvers.withIndex()) {
            val result = tryResolve(resolver, toolName, arguments, index)
            if (result != null) return result
        }
        return null
    }

    /**
     * 단일 리졸버 호출을 fail-open으로 감싼다.
     * 예외 발생 시 로깅 후 null을 반환하여 체인이 다음 리졸버로 진행하도록 한다.
     */
    private fun tryResolve(
        resolver: ApprovalContextResolver,
        toolName: String,
        arguments: Map<String, Any?>,
        index: Int
    ): ApprovalContext? {
        return try {
            resolver.resolve(toolName, arguments)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) {
                "ChainedApprovalContextResolver: 리졸버 #$index (${resolver::class.simpleName}) 실패, " +
                    "다음 리졸버로 진행: tool=$toolName"
            }
            null
        }
    }

    /** 등록된 리졸버 개수를 반환한다 (테스트 및 디버깅용). */
    fun size(): Int = resolvers.size

    /** 체인이 비어있는지 확인한다. */
    fun isEmpty(): Boolean = resolvers.isEmpty()

    companion object {
        /** 빈 체인 — [resolve]가 항상 null을 반환한다. */
        val EMPTY: ChainedApprovalContextResolver = ChainedApprovalContextResolver()
    }
}

package com.arc.reactor.agent.multiagent

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Supervisor 에이전트 인터페이스.
 *
 * 사용자 쿼리를 분석하여 적절한 전문 에이전트에 위임한다.
 * LLM 호출 없이 순수 휴리스틱으로 라우팅을 결정한다.
 *
 * ## 실행 흐름
 * ```
 * 사용자 쿼리 → 키워드 분석 → 전문 에이전트 선택 → 위임 실행 → 결과 반환
 * ```
 *
 * ## 사용 예시
 * ```kotlin
 * val result = supervisorAgent.delegate(command)
 * // 내부적으로 "jira" 키워드 감지 → JiraAgent에 위임
 * ```
 *
 * @see AgentRegistry 전문 에이전트 조회
 * @see AgentSpec 전문 에이전트 사양
 */
interface SupervisorAgent {

    /**
     * 쿼리를 분석하여 적절한 전문 에이전트에 위임한다.
     *
     * @param command 사용자 에이전트 명령
     * @return 위임된 에이전트의 실행 결과
     */
    suspend fun delegate(command: AgentCommand): AgentResult
}

/**
 * 키워드 기반 기본 Supervisor 에이전트.
 *
 * [AgentRegistry]에서 쿼리와 매칭되는 전문 에이전트를 찾아 위임한다.
 * 매칭 에이전트가 없으면 전체 도구를 사용하는 기본 실행으로 폴백한다.
 * 복수 에이전트 매칭 시 순차 실행하여 결과를 병합한다.
 *
 * ## 메시지 전달
 * [AgentMessageBus]가 주입되면 각 에이전트 실행 후 결과를 메시지로 발행한다.
 * 다음 에이전트 실행 시 이전 에이전트의 결과를 메타데이터에 포함하여 전달한다.
 *
 * ## 라우팅 전략
 * 1. [AgentRegistry.findByCapability]로 쿼리와 매칭되는 에이전트 검색
 * 2. 단일 매칭 → 해당 에이전트에 위임
 * 3. 복수 매칭 → 순차 실행 후 결과 병합
 * 4. 매칭 없음 → 기본 AgentExecutor로 폴백
 *
 * @param agentExecutor 기본/폴백 에이전트 실행기
 * @param agentRegistry 전문 에이전트 레지스트리
 * @param maxDelegations 단일 요청에서 최대 위임 에이전트 수
 * @param messageBus 에이전트 간 메시지 버스 (선택)
 *
 * @see SupervisorAgent 인터페이스 정의
 * @see AgentRegistry 에이전트 검색
 * @see AgentMessageBus 에이전트 간 메시지 전달
 */
class DefaultSupervisorAgent(
    private val agentExecutor: AgentExecutor,
    private val agentRegistry: AgentRegistry,
    private val maxDelegations: Int = DEFAULT_MAX_DELEGATIONS,
    private val messageBus: AgentMessageBus? = null
) : SupervisorAgent {

    override suspend fun delegate(command: AgentCommand): AgentResult {
        val matched = agentRegistry.findByCapability(command.userPrompt)

        if (matched.isEmpty()) {
            logger.debug {
                "매칭 에이전트 없음, 기본 실행으로 폴백: " +
                    "query=${command.userPrompt.take(MAX_LOG_QUERY_LENGTH)}"
            }
            return agentExecutor.execute(command)
        }

        val targets = matched.take(maxDelegations)
        logger.info {
            "Supervisor 위임 결정: agents=${targets.map { it.id }}, " +
                "query=${command.userPrompt.take(MAX_LOG_QUERY_LENGTH)}"
        }

        return if (targets.size == 1) {
            executeSingle(command, targets.first())
        } else {
            executeMultiple(command, targets)
        }
    }

    /**
     * 단일 전문 에이전트에 위임하여 실행한다.
     */
    private suspend fun executeSingle(
        command: AgentCommand,
        spec: AgentSpec
    ): AgentResult {
        val delegated = buildDelegatedCommand(command, spec)
        logger.debug { "단일 위임 실행: agent=${spec.id}" }
        val result = agentExecutor.execute(delegated)
        publishResult(spec, result)
        return result
    }

    /**
     * 복수 전문 에이전트에 순차 위임하여 결과를 병합한다.
     * 각 에이전트 실행 후 결과를 메시지 버스에 발행하고,
     * 다음 에이전트에게 이전 결과를 컨텍스트로 전달한다.
     */
    private suspend fun executeMultiple(
        command: AgentCommand,
        specs: List<AgentSpec>
    ): AgentResult {
        val results = mutableListOf<Pair<AgentSpec, AgentResult>>()
        val allToolsUsed = mutableListOf<String>()

        for (spec in specs) {
            val delegated = buildDelegatedCommand(
                command, spec, results
            )
            logger.debug {
                "멀티 위임 실행: agent=${spec.id} " +
                    "(${results.size + 1}/${specs.size})"
            }
            val result = agentExecutor.execute(delegated)
            results.add(spec to result)
            allToolsUsed.addAll(result.toolsUsed)
            publishResult(spec, result)
        }

        return mergeResults(results, allToolsUsed)
    }

    /**
     * 에이전트 실행 결과를 메시지 버스에 발행한다.
     * 메시지 버스가 없으면 무시한다.
     */
    private suspend fun publishResult(
        spec: AgentSpec,
        result: AgentResult
    ) {
        if (messageBus == null) return
        val message = AgentMessage(
            sourceAgentId = spec.id,
            targetAgentId = null,
            content = result.content.orEmpty(),
            metadata = mapOf(
                "success" to result.success,
                "toolsUsed" to result.toolsUsed,
                "durationMs" to result.durationMs
            )
        )
        messageBus.publish(message)
    }

    /**
     * 전문 에이전트의 사양에 맞게 명령을 재구성한다.
     *
     * 시스템 프롬프트 오버라이드, 실행 모드, 도구 필터링 힌트를
     * 메타데이터에 포함한다.
     */
    private fun buildDelegatedCommand(
        command: AgentCommand,
        spec: AgentSpec,
        previousResults: List<Pair<AgentSpec, AgentResult>> = emptyList()
    ): AgentCommand {
        val metadata = LinkedHashMap(command.metadata)
        metadata["delegatedAgentId"] = spec.id
        metadata["delegatedAgentName"] = spec.name
        metadata["allowedToolNames"] = spec.toolNames
        injectPreviousResults(metadata, previousResults)

        return command.copy(
            systemPrompt = spec.systemPromptOverride
                ?: command.systemPrompt,
            mode = spec.mode,
            metadata = metadata
        )
    }

    /**
     * 이전 에이전트들의 실행 결과를 메타데이터에 주입한다.
     * 다음 에이전트가 이전 에이전트의 결과를 컨텍스트로 참조할 수 있다.
     */
    private fun injectPreviousResults(
        metadata: MutableMap<String, Any>,
        previousResults: List<Pair<AgentSpec, AgentResult>>
    ) {
        if (previousResults.isEmpty()) return
        val summaries = previousResults.map { (prevSpec, prevResult) ->
            mapOf(
                "agentId" to prevSpec.id,
                "agentName" to prevSpec.name,
                "success" to prevResult.success,
                "content" to prevResult.content.orEmpty()
            )
        }
        metadata["previousAgentResults"] = summaries
    }

    /**
     * 복수 에이전트 결과를 하나로 병합한다.
     *
     * 모든 결과가 성공이면 내용을 결합하여 반환한다.
     * 일부 실패 시에도 성공 결과가 있으면 부분 성공으로 처리한다.
     */
    private fun mergeResults(
        results: List<Pair<AgentSpec, AgentResult>>,
        allToolsUsed: List<String>
    ): AgentResult {
        val successResults = results.filter { (_, r) -> r.success }
        if (successResults.isEmpty()) {
            return AgentResult.failure(
                errorMessage = "모든 위임 에이전트가 실패했습니다"
            )
        }

        val merged = successResults.joinToString("\n\n") { (spec, result) ->
            "[${spec.name}]\n${result.content.orEmpty()}"
        }
        val totalDuration = results.sumOf { (_, r) -> r.durationMs }

        val metadata = LinkedHashMap<String, Any>()
        metadata["delegatedAgents"] = results.map { (spec, _) -> spec.id }
        metadata["successCount"] = successResults.size
        metadata["totalCount"] = results.size

        return AgentResult(
            success = true,
            content = merged,
            toolsUsed = allToolsUsed.distinct(),
            durationMs = totalDuration,
            metadata = metadata
        )
    }

    companion object {
        /** 로그에 포함할 쿼리 최대 길이 */
        private const val MAX_LOG_QUERY_LENGTH = 80

        /** 단일 요청에서 최대 위임 에이전트 수 기본값 */
        const val DEFAULT_MAX_DELEGATIONS = 3
    }
}

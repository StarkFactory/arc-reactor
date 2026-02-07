package com.arc.reactor.agent.multi

import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback

/**
 * 멀티에이전트 노드 정의
 *
 * 하나의 에이전트 역할을 정의합니다. 각 노드는 고유한 시스템 프롬프트와 도구를 가집니다.
 *
 * @param name 노드 이름 (예: "researcher", "writer", "reviewer")
 * @param systemPrompt 이 에이전트의 역할을 정의하는 시스템 프롬프트
 * @param description 이 에이전트가 하는 일 (Supervisor 패턴에서 라우팅 시 참조)
 * @param tools ToolCallback 방식 도구 목록
 * @param localTools LocalTool 방식 도구 목록
 * @param maxToolCalls 이 에이전트의 최대 도구 호출 횟수
 */
data class AgentNode(
    val name: String,
    val systemPrompt: String,
    val description: String = "",
    val tools: List<ToolCallback> = emptyList(),
    val localTools: List<LocalTool> = emptyList(),
    val maxToolCalls: Int = 10
)

/**
 * 멀티에이전트 실행 결과
 *
 * @param success 전체 실행 성공 여부
 * @param finalResult 최종 결과 (마지막 노드 또는 합산 결과)
 * @param nodeResults 각 노드별 실행 결과 (순서 보장)
 * @param totalDurationMs 전체 실행 시간
 */
data class MultiAgentResult(
    val success: Boolean,
    val finalResult: AgentResult,
    val nodeResults: List<NodeResult> = emptyList(),
    val totalDurationMs: Long = 0
)

/**
 * 개별 노드 실행 결과
 */
data class NodeResult(
    val nodeName: String,
    val result: AgentResult,
    val durationMs: Long = 0
)

/**
 * 병렬 실행 결과 병합 전략
 */
fun interface ResultMerger {
    /**
     * 여러 노드의 결과를 하나로 병합합니다.
     *
     * @param results 각 노드의 실행 결과
     * @return 병합된 최종 콘텐츠
     */
    fun merge(results: List<NodeResult>): String

    companion object {
        /** 줄바꿈으로 구분하여 결합 */
        val JOIN_WITH_NEWLINE = ResultMerger { results ->
            results.joinToString("\n\n") { "[${it.nodeName}]\n${it.result.content ?: ""}" }
        }

        /** 마지막 성공 결과만 사용 */
        val LAST_SUCCESS = ResultMerger { results ->
            results.lastOrNull { it.result.success }?.result?.content ?: "No successful results"
        }
    }
}

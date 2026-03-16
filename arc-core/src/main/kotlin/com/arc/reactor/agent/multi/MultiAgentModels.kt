package com.arc.reactor.agent.multi

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback

/**
 * 멀티 에이전트 노드 정의.
 *
 * 단일 에이전트 역할을 정의한다. 각 노드는 자체 시스템 프롬프트와 도구를 가진다.
 *
 * @param name 노드 이름 (예: "researcher", "writer", "reviewer")
 * @param systemPrompt 이 에이전트의 역할을 정의하는 시스템 프롬프트
 * @param description 이 에이전트가 하는 일 (Supervisor 패턴에서 라우팅 참조용)
 * @param tools ToolCallback 기반 도구 목록
 * @param localTools LocalTool 기반 도구 목록
 * @param maxToolCalls 이 에이전트의 최대 도구 호출 횟수
 * @param timeoutMs 노드별 타임아웃 (밀리초). null이면 전역 기본값 사용.
 */
data class AgentNode(
    val name: String,
    val systemPrompt: String,
    val description: String = "",
    val tools: List<ToolCallback> = emptyList(),
    val localTools: List<LocalTool> = emptyList(),
    val maxToolCalls: Int = 10,
    val timeoutMs: Long? = null
)

/**
 * 멀티 에이전트 실행 결과.
 *
 * @param success 전체 실행 성공 여부
 * @param finalResult 최종 결과 (마지막 노드 출력 또는 병합 결과)
 * @param nodeResults 각 노드의 실행 결과 (순서 보존)
 * @param totalDurationMs 총 실행 시간
 * @param failedNodes 실패한 노드의 상세 정보 (모두 성공하면 빈 목록)
 */
data class MultiAgentResult(
    val success: Boolean,
    val finalResult: AgentResult,
    val nodeResults: List<NodeResult> = emptyList(),
    val totalDurationMs: Long = 0,
    val failedNodes: List<FailedNodeInfo> = emptyList()
) {
    /** 모든 노드에서 사용된 총 토큰 수 */
    val totalTokensUsed: Int
        get() = nodeResults.sumOf { it.tokensUsed }
}

/**
 * 개별 노드 실행 결과.
 */
data class NodeResult(
    val nodeName: String,
    val result: AgentResult,
    val durationMs: Long = 0,
    val tokensUsed: Int = 0
)

/**
 * 구조화된 에러 보고를 위한 실패 노드 정보.
 *
 * @param nodeName 실패한 노드 이름
 * @param index 실행 목록에서의 실패 노드 인덱스
 * @param errorCode 에이전트 결과의 에러 코드 (가용 시)
 * @param errorMessage 실패를 설명하는 에러 메시지
 */
data class FailedNodeInfo(
    val nodeName: String,
    val index: Int,
    val errorCode: AgentErrorCode? = null,
    val errorMessage: String? = null
)

/**
 * 병렬 실행 결과의 병합 전략.
 */
fun interface ResultMerger {
    /**
     * 여러 노드의 결과를 하나로 병합한다.
     *
     * @param results 각 노드의 실행 결과
     * @return 병합된 최종 내용
     */
    fun merge(results: List<NodeResult>): String

    companion object {
        /** 줄바꿈 구분자로 결합 */
        val JOIN_WITH_NEWLINE = ResultMerger { results ->
            results.joinToString("\n\n") { "[${it.nodeName}]\n${it.result.content ?: ""}" }
        }

        /** 마지막 성공 결과만 사용 */
        val LAST_SUCCESS = ResultMerger { results ->
            results.lastOrNull { it.result.success }?.result?.content ?: "No successful results"
        }
    }
}

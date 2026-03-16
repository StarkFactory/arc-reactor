package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import org.springframework.ai.chat.client.ChatClient

/**
 * 멀티 에이전트 사용 예시 -- 보고서 파이프라인 (순차 패턴)
 *
 * ## 이 예시가 보여주는 것
 * 각 에이전트의 출력이 다음 단계로 전달되는 3단계 파이프라인:
 * 1. **Researcher** 정보를 수집
 * 2. **Writer** 구조화된 보고서를 작성
 * 3. **Reviewer** 검토 및 개선
 *
 * ## 사용법
 * ```kotlin
 * @Bean
 * fun reportPipeline(chatClient: ChatClient, props: AgentProperties) =
 *     ReportPipelineExample(chatClient, props)
 *
 * @PostMapping("/api/report")
 * suspend fun report(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = reportPipeline.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.SequentialOrchestrator
 */
// @Component  <- 자동 등록하려면 주석 해제
class ReportPipelineExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    suspend fun handle(
        message: String,
        userId: String? = null
    ): MultiAgentResult {
        return MultiAgent.sequential()
            .node("researcher") {
                systemPrompt =
                    "Research the given topic thoroughly. " +
                    "Provide key findings with supporting data."
            }
            .node("writer") {
                systemPrompt =
                    "Based on the research provided, " +
                    "write a well-structured report."
            }
            .node("reviewer") {
                systemPrompt =
                    "Review and improve the report. " +
                    "Fix errors and enhance clarity."
            }
            .execute(
                command = AgentCommand(
                    systemPrompt = "",
                    userPrompt = message,
                    userId = userId
                ),
                agentFactory = ::createAgent
            )
    }

    private fun createAgent(node: AgentNode): AgentExecutor {
        return SpringAiAgentExecutor(
            chatClient = chatClient,
            properties = properties,
            toolCallbacks = node.tools,
            localTools = node.localTools
        )
    }
}

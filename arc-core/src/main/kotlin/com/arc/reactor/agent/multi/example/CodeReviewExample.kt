package com.arc.reactor.agent.multi.example

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.multi.AgentNode
import com.arc.reactor.agent.multi.MultiAgent
import com.arc.reactor.agent.multi.MultiAgentResult
import com.arc.reactor.agent.multi.ResultMerger
import org.springframework.ai.chat.client.ChatClient

/**
 * 멀티 에이전트 사용 예시 -- 코드 리뷰 (병렬 패턴)
 *
 * ## 이 예시가 보여주는 것
 * 세 리뷰어가 서로 다른 관점에서 동시에 코드를 분석한다:
 * - **Security**: 취약점 분석
 * - **Style**: 코드 스타일 및 가독성
 * - **Logic**: 논리 에러 및 엣지 케이스
 *
 * 결과는 단일 통합 리뷰로 병합된다.
 *
 * ## 사용법
 * ```kotlin
 * @Bean
 * fun codeReview(chatClient: ChatClient, props: AgentProperties) =
 *     CodeReviewExample(chatClient, props)
 *
 * @PostMapping("/api/review")
 * suspend fun review(@RequestBody request: ChatRequest): ChatResponse {
 *     val result = codeReview.handle(request.message, request.userId)
 *     return ChatResponse(content = result.finalResult.content, success = result.success)
 * }
 * ```
 *
 * @see com.arc.reactor.agent.multi.ParallelOrchestrator
 */
// @Component  <- 자동 등록하려면 주석 해제
class CodeReviewExample(
    private val chatClient: ChatClient,
    private val properties: AgentProperties
) {

    suspend fun handle(
        message: String,
        userId: String? = null
    ): MultiAgentResult {
        return MultiAgent.parallel(
            merger = ResultMerger.JOIN_WITH_NEWLINE,
            failFast = false
        )
            .node("security") {
                systemPrompt = "Analyze security vulnerabilities only."
            }
            .node("style") {
                systemPrompt = "Review code style and readability only."
            }
            .node("logic") {
                systemPrompt =
                    "Check for logic errors and edge cases only."
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

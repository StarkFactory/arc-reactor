package com.arc.reactor.agent.impl

import com.arc.reactor.memory.TokenEstimator
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage

private val logger = KotlinLogging.logger {}

/**
 * 대화 히스토리를 컨텍스트 예산 내에 맞추기 위해 트리밍하는 trimmer.
 *
 * AssistantMessage(toolCalls) + ToolResponseMessage 쌍의 무결성을 보존하면서
 * 오래된 메시지를 우선적으로 제거한다.
 *
 * 트리밍 단계:
 * 1. **Phase 1**: 선행 SystemMessage(계층적 메모리 사실/내러티브)와 마지막 UserMessage를
 *    보호하면서 가장 오래된 메시지부터 제거
 * 2. **Phase 1.5**: 최신 도구 관측값이 트리밍되지 않도록 선행 메모리 SystemMessage를 먼저 제거
 * 3. **Phase 2**: 마지막 UserMessage 이후의 도구 상호작용 쌍 제거 (예산 초과 시)
 *
 * @param maxContextWindowTokens 최대 컨텍스트 윈도우 토큰 수
 * @param outputReserveTokens 출력 응답을 위해 예약할 토큰 수
 * @param tokenEstimator 텍스트의 토큰 수를 추정하는 estimator
 * @see ManualReActLoopExecutor 매 LLM 호출 전 메시지 트리밍 수행
 * @see StreamingReActLoopExecutor 스트리밍 모드에서도 동일하게 트리밍 수행
 */
internal class ConversationMessageTrimmer(
    private val maxContextWindowTokens: Int,
    private val outputReserveTokens: Int,
    private val tokenEstimator: TokenEstimator
) {
    /**
     * 이전 trim() 호출에서 계산한 메시지별 토큰 수 캐시.
     * 메시지 identity(===)로 캐시 히트 판단하여 증분 계산만 수행한다.
     * ReAct 루프에서 매 반복 호출 시 기존 메시지 재계산을 방지한다.
     */
    private val tokenCache = java.util.IdentityHashMap<Message, Int>()

    companion object {
        /** 메시지당 구조적 오버헤드 (역할 태그, 구분자, 메타데이터) — 추정 토큰 수. */
        const val MESSAGE_STRUCTURE_OVERHEAD = 20
    }

    /**
     * 메시지 목록을 컨텍스트 예산에 맞게 트리밍한다.
     *
     * @param messages 트리밍할 메시지 리스트 (in-place 수정)
     * @param systemPrompt 시스템 프롬프트 (토큰 예산 계산에 사용)
     * @param toolTokenReserve 도구 정의를 위해 추가 예약할 토큰 수
     */
    fun trim(messages: MutableList<Message>, systemPrompt: String, toolTokenReserve: Int = 0) {
        val systemTokens = tokenEstimator.estimate(systemPrompt)
        val budget = maxContextWindowTokens - systemTokens - outputReserveTokens - toolTokenReserve

        // ── 예산이 음수이면 마지막 UserMessage만 보존 ──
        if (budget <= 0) {
            logger.warn {
                "Context budget is non-positive ($budget). " +
                    "system=$systemTokens, outputReserve=$outputReserveTokens, max=$maxContextWindowTokens"
            }
            val lastUserMsgIndex = messages.indexOfLast { it is UserMessage }
            if (lastUserMsgIndex >= 0 && messages.size > 1) {
                val userMsg = messages[lastUserMsgIndex]
                messages.clear()
                messages.add(userMsg)
            }
            return
        }

        val messageTokens = messages.mapTo(ArrayList(messages.size)) { msg ->
            tokenCache.getOrPut(msg) { estimateMessageTokens(msg) }
        }
        var totalTokens = messageTokens.sum()
        // Phase 1: 오래된 히스토리 제거 (선행 SystemMessage와 마지막 UserMessage 보호)
        totalTokens = trimOldHistory(messages, messageTokens, totalTokens, budget)
        // Phase 1.5: 최신 도구 컨텍스트 보존을 위해 선행 SystemMessage 우선 제거
        totalTokens = trimLeadingMemoryMessages(messages, messageTokens, totalTokens, budget)
        // Phase 2: 마지막 UserMessage 이후의 도구 히스토리 쌍 제거
        trimToolHistory(messages, messageTokens, totalTokens, budget)
        // 제거된 메시지의 캐시 엔트리 정리
        tokenCache.keys.retainAll(messages.toSet())
    }

    /**
     * Phase 1: 가장 오래된 메시지부터 제거한다.
     * 선행 SystemMessage(계층적 메모리 사실/내러티브)와 마지막 UserMessage를 보호한다.
     */
    private fun trimOldHistory(
        messages: MutableList<Message>,
        messageTokens: MutableList<Int>,
        currentTokens: Int,
        budget: Int
    ): Int {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val skipCount = messages.indexOfFirst { it !is SystemMessage }
                .let { if (it < 0) messages.size else it }
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            if (protectedIdx <= skipCount) break

            val removable = messages.subList(skipCount, messages.size)
            val removeCount = calculateRemoveGroupSize(removable)
            if (removeCount <= 0 || skipCount + removeCount > protectedIdx) break

            var removedTokens = 0
            repeat(removeCount) {
                if (skipCount < messages.size && messages.size > 1) {
                    removedTokens += messageTokens.removeAt(skipCount)
                    messages.removeAt(skipCount)
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (old history). Remaining tokens: $totalTokens/$budget" }
        }
        return totalTokens
    }

    /** Phase 2: 마지막 UserMessage 이후의 도구 상호작용 쌍을 제거한다 (예산 초과 시). */
    private fun trimToolHistory(
        messages: MutableList<Message>,
        messageTokens: MutableList<Int>,
        currentTokens: Int,
        budget: Int
    ) {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val protectedIdx = messages.indexOfLast { it is UserMessage }.coerceAtLeast(0)
            val removeStartIdx = protectedIdx + 1
            if (removeStartIdx > messages.size - 1) break

            val subList = messages.subList(removeStartIdx, messages.size)
            val removeCount = calculateRemoveGroupSize(subList)
            if (removeCount <= 0 || removeStartIdx + removeCount > messages.size) break

            var removedTokens = 0
            repeat(removeCount) {
                if (removeStartIdx < messages.size) {
                    removedTokens += messageTokens.removeAt(removeStartIdx)
                    messages.removeAt(removeStartIdx)
                }
            }
            totalTokens -= removedTokens
            logger.debug { "Trimmed $removeCount messages (tool history). Remaining tokens: $totalTokens/$budget" }
        }
    }

    /**
     * 최신 도구 관측값이 트리밍되지 않도록, 선행 메모리 SystemMessage를 먼저 제거한다.
     *
     * 최신 tool-call/tool-response 컨텍스트가 다음 LLM 호출에 보이도록
     * 선행 메모리 SystemMessage를 우선적으로 drop한다.
     */
    private fun trimLeadingMemoryMessages(
        messages: MutableList<Message>,
        messageTokens: MutableList<Int>,
        currentTokens: Int,
        budget: Int
    ): Int {
        var totalTokens = currentTokens
        while (totalTokens > budget && messages.size > 1) {
            val lastUserIdx = messages.indexOfLast { it is UserMessage }
            if (lastUserIdx < 0 || lastUserIdx >= messages.lastIndex) break
            if (messages.firstOrNull() !is SystemMessage) break

            totalTokens -= messageTokens.removeAt(0)
            messages.removeAt(0)
            logger.debug {
                "Trimmed 1 message (leading system for fresh tool history). Remaining tokens: $totalTokens/$budget"
            }
        }
        return totalTokens
    }

    /**
     * 선두에서 그룹 단위로 제거해야 할 메시지 수를 계산한다.
     *
     * AssistantMessage(toolCalls) 뒤에 ToolResponseMessage가 오면 쌍으로 제거해야
     * 유효한 메시지 순서를 유지할 수 있다 (메시지 쌍 무결성).
     */
    private fun calculateRemoveGroupSize(messages: List<Message>): Int {
        if (messages.isEmpty()) return 0
        val first = messages[0]

        // 도구 호출이 있는 AssistantMessage -> 쌍을 이루는 ToolResponseMessage도 함께 제거
        if (first is AssistantMessage && !first.toolCalls.isNullOrEmpty()) {
            return if (messages.size > 1 && messages[1] is ToolResponseMessage) 2 else 1
        }

        // 선행 AssistantMessage 없는 ToolResponseMessage (고아) -> 단독 제거
        if (first is ToolResponseMessage) return 1

        // 일반 UserMessage 또는 AssistantMessage -> 단독 제거
        return 1
    }

    /** 메시지의 추정 토큰 수를 계산한다 (내용 토큰 + 구조적 오버헤드). */
    private fun estimateMessageTokens(message: Message): Int {
        val contentTokens = when (message) {
            is UserMessage -> tokenEstimator.estimate(message.text)
            is AssistantMessage -> {
                val textTokens = tokenEstimator.estimate(message.text ?: "")
                val toolCallTokens = message.toolCalls.sumOf {
                    tokenEstimator.estimate(it.name() + it.arguments())
                }
                textTokens + toolCallTokens
            }
            is SystemMessage -> tokenEstimator.estimate(message.text)
            is ToolResponseMessage -> message.responses.sumOf { tokenEstimator.estimate(it.responseData()) }
            else -> tokenEstimator.estimate(message.text ?: "")
        }
        return contentTokens + MESSAGE_STRUCTURE_OVERHEAD
    }
}

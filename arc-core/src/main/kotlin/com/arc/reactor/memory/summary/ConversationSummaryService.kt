package com.arc.reactor.memory.summary

import com.arc.reactor.agent.model.Message

/**
 * 대화 이력을 구조화된 팩트와 서술형 요약으로 요약하는 서비스.
 *
 * @see LlmConversationSummaryService LLM 기반 구현체
 */
interface ConversationSummaryService {

    /**
     * 대화 메시지 목록을 요약한다.
     *
     * @param messages 요약할 메시지 목록 (시간순)
     * @param existingFacts 이전에 추출된 팩트 (병합/갱신 대상)
     * @return 서술 요약과 팩트가 포함된 요약 결과
     */
    suspend fun summarize(
        messages: List<Message>,
        existingFacts: List<StructuredFact> = emptyList()
    ): SummarizationResult
}

package com.arc.reactor.memory.summary

import com.arc.reactor.agent.model.Message

/**
 * Service that summarizes conversation history into structured facts
 * and a narrative summary.
 *
 * @see LlmConversationSummaryService for LLM-based implementation
 */
interface ConversationSummaryService {

    /**
     * Summarize a list of conversation messages.
     *
     * @param messages Messages to summarize (chronological order)
     * @param existingFacts Previously extracted facts to merge/update
     * @return Summarization result with narrative and facts
     */
    suspend fun summarize(
        messages: List<Message>,
        existingFacts: List<StructuredFact> = emptyList()
    ): SummarizationResult
}

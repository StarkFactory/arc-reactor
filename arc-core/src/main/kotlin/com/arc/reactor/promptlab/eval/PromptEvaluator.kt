package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.TestQuery

interface PromptEvaluator {
    suspend fun evaluate(response: String, query: TestQuery): EvaluationResult
}

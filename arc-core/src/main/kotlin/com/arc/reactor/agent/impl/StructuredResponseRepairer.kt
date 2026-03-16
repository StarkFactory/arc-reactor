package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

/**
 * LLM의 구조화 응답(JSON/YAML)을 검증하고, 유효하지 않으면 LLM을 통해 복구를 시도하는 repairer.
 *
 * 처리 흐름:
 * 1. TEXT 형식이면 검증 없이 성공 반환
 * 2. 마크다운 코드 펜스 제거 후 형식 검증
 * 3. 유효하면 성공 반환
 * 4. 유효하지 않으면 LLM에 복구 요청 → 복구된 콘텐츠 재검증
 * 5. 복구도 실패하면 INVALID_RESPONSE 에러 반환
 *
 * @see StructuredOutputValidator 형식 검증 (JSON/YAML 파싱)
 * @see SpringAiAgentExecutor ReAct 루프 완료 후 구조화 응답 검증에 사용
 * @see ResponseFormat 지원 형식: TEXT, JSON, YAML
 */
internal class StructuredResponseRepairer(
    private val errorMessageResolver: ErrorMessageResolver,
    private val resolveChatClient: (AgentCommand) -> ChatClient,
    private val structuredOutputValidator: StructuredOutputValidator = StructuredOutputValidator()
) {

    /**
     * 구조화 응답을 검증하고, 필요 시 복구를 시도한다.
     *
     * @param rawContent LLM이 생성한 원본 응답
     * @param format 기대하는 응답 형식
     * @param command 에이전트 명령 (복구 시 ChatClient 해석에 사용)
     * @param tokenUsage 토큰 사용량 (성공 결과에 포함)
     * @param toolsUsed 사용된 도구 목록 (성공 결과에 포함)
     * @return 검증 통과 시 성공 결과, 복구 실패 시 에러 결과
     */
    suspend fun validateAndRepair(
        rawContent: String,
        format: ResponseFormat,
        command: AgentCommand,
        tokenUsage: TokenUsage?,
        toolsUsed: List<String>
    ): AgentResult {
        // ── 단계 1: TEXT 형식은 검증 불필요 ──
        if (format == ResponseFormat.TEXT) {
            return AgentResult.success(content = rawContent, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        // ── 단계 2: 코드 펜스 제거 후 형식 검증 ──
        val stripped = structuredOutputValidator.stripMarkdownCodeFence(rawContent)

        if (structuredOutputValidator.isValidFormat(stripped, format)) {
            return AgentResult.success(content = stripped, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        // ── 단계 3: LLM을 통한 복구 시도 ──
        logger.warn { "Invalid $format response detected, attempting repair" }
        val repaired = attemptRepair(stripped, format, command)
        if (repaired != null && structuredOutputValidator.isValidFormat(repaired, format)) {
            return AgentResult.success(content = repaired, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        logger.error { "Structured output validation failed after repair attempt" }
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.INVALID_RESPONSE, null),
            errorCode = AgentErrorCode.INVALID_RESPONSE
        )
    }

    /**
     * 유효하지 않은 구조화 콘텐츠를 LLM에 전송하여 복구를 시도한다.
     *
     * @return 복구된 콘텐츠 (코드 펜스 제거 후). 복구 실패 시 null
     */
    private suspend fun attemptRepair(
        invalidContent: String,
        format: ResponseFormat,
        command: AgentCommand
    ): String? {
        return runSuspendCatchingNonCancellation {
            val formatName = format.name
            val repairPrompt = "The following $formatName is invalid. " +
                "Fix it and return ONLY valid $formatName with no explanation or code fences:\n\n$invalidContent"

            val activeChatClient = resolveChatClient(command)
            val response = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                activeChatClient
                    .prompt()
                    .user(repairPrompt)
                    .call()
                    .chatResponse()
            }
            val repairedContent = response?.results?.firstOrNull()?.output?.text
            if (repairedContent != null) structuredOutputValidator.stripMarkdownCodeFence(repairedContent) else null
        }.getOrElse { e ->
            logger.warn(e) { "Repair attempt failed" }
            null
        }
    }
}

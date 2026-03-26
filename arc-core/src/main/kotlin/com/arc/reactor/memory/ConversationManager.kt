package com.arc.reactor.memory

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.agent.impl.ToolResponsePayloadNormalizer
import com.arc.reactor.memory.summary.ConversationSummary
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tracing.ArcReactorTracer
import com.arc.reactor.tracing.NoOpArcReactorTracer
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.beans.factory.DisposableBean
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

/**
 * 대화 이력의 생명주기를 관리하는 인터페이스.
 *
 * - MemoryStore에서 대화 이력을 로드한다
 * - 에이전트 실행 후 대화 이력을 저장한다
 * - Arc Reactor 메시지와 Spring AI 메시지 간 변환을 수행한다
 * - 선택적으로 계층적 메모리(facts + narrative + 최근 윈도우)를 적용한다
 */
interface ConversationManager {

    /**
     * 커맨드의 대화 이력을 Spring AI 메시지 목록으로 로드한다.
     *
     * conversationHistory가 직접 제공되면 우선 사용하고,
     * 그렇지 않으면 sessionId를 사용하여 MemoryStore에서 조회한다.
     *
     * 계층적 메모리가 활성화되어 있고 대화가 트리거 임계값을 초과하면,
     * 단순 takeLast 대신 [Facts SystemMessage] + [Narrative SystemMessage] + [최근 N개 메시지]를 반환한다.
     */
    suspend fun loadHistory(command: AgentCommand): List<Message>

    /**
     * 에이전트 실행 결과를 MemoryStore에 저장한다.
     * 실패한 결과는 저장하지 않는다.
     *
     * 계층적 메모리가 활성화되어 있으면 대화가 트리거 임계값을 초과할 때
     * 비동기 요약을 트리거한다.
     */
    suspend fun saveHistory(command: AgentCommand, result: AgentResult)

    /**
     * 스트리밍 결과를 MemoryStore에 저장한다.
     */
    suspend fun saveStreamingHistory(command: AgentCommand, content: String)

    /**
     * 주어진 세션의 활성 비동기 요약 작업을 취소한다.
     * 세션 삭제 시 고아 요약(orphan summary)을 방지하기 위해 호출된다.
     */
    fun cancelActiveSummarization(sessionId: String) {
        // 비동기 요약이 없는 구현체를 위한 기본 no-op
    }
}

/**
 * MemoryStore 기반의 기본 ConversationManager 구현체.
 * 선택적 계층적 메모리를 지원한다.
 *
 * [summaryStore]와 [summaryService]가 모두 제공되고 요약이 활성화되면,
 * 오래된 메시지를 구조화된 facts와 narrative 요약으로 압축하고
 * 최근 메시지는 원문 그대로 보존한다.
 *
 * ## 계층적 메모리의 장점
 * - 전체 대화 이력을 LLM에 보내지 않아도 맥락을 유지할 수 있다
 * - Facts는 정확한 수치/결정 사항을, Narrative는 대화 흐름을 보존한다
 * - 토큰 비용을 크게 절감하면서도 대화 품질을 유지한다
 */
class DefaultConversationManager(
    private val memoryStore: MemoryStore?,
    private val properties: AgentProperties,
    private val summaryStore: ConversationSummaryStore? = null,
    private val summaryService: ConversationSummaryService? = null,
    private val tracer: ArcReactorTracer = NoOpArcReactorTracer()
) : ConversationManager, DisposableBean {

    /** 요약 관련 설정 프로퍼티 바로가기 */
    private val summaryProps get() = properties.memory.summary

    /** 비동기 요약 작업을 위한 코루틴 스코프. SupervisorJob으로 개별 작업 실패가 전파되지 않는다. */
    private val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 세션별 활성 요약 작업을 추적하여 중복 실행을 방지한다. 완료/만료 시 자동 정리. */
    private val activeSummarizations: Cache<String, Job> = Caffeine.newBuilder()
        .maximumSize(5_000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .removalListener<String, Job> { key, job, cause ->
            job?.cancel()
            logger.debug { "요약 작업 제거: session=$key, cause=$cause" }
        }
        .build()

    /**
     * 세션별 저장 뮤텍스. user+assistant 메시지 쌍이 원자적으로 기록되도록 보장한다.
     * 이 뮤텍스가 없으면 같은 세션에 대한 두 동시 요청이 다음과 같이 인터리빙될 수 있다:
     * user1, user2, assistant2, assistant1 — 대화 순서가 깨진다.
     * 코루틴 Mutex를 사용하여 suspend 컨텍스트에서 스레드를 차단하지 않는다.
     * 30분 미접근 시 자동 만료되어 메모리 누수를 방지한다.
     */
    private val sessionSaveLocks: Cache<String, Mutex> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(30, TimeUnit.MINUTES)
        .build()

    override suspend fun loadHistory(command: AgentCommand): List<Message> {
        if (command.conversationHistory.isNotEmpty()) {
            return command.conversationHistory.map { toSpringAiMessage(it) }
        }
        val sessionId = command.metadata["sessionId"]?.toString()
        if (sessionId == null) {
            logger.debug { "No sessionId in metadata" }
            return emptyList()
        }
        val span = tracer.startSpan(
            "arc.memory.load",
            mapOf("session.id" to sessionId)
        )
        return try {
            // 세션 소유권 검증: 다른 사용자의 대화 이력 접근 차단
            try {
                verifySessionOwnership(sessionId, command.userId)
            } catch (e: SessionOwnershipException) {
                span.setAttribute("memory.ownership.denied", "true")
                span.close()
                return emptyList()
            } catch (e: SessionOwnershipVerificationException) {
                span.setAttribute("memory.ownership.verification_failed", "true")
                span.close()
                logger.warn { "세션 소유권 DB 조회 실패 — fail-close로 빈 이력 반환: sessionId=$sessionId" }
                return emptyList()
            }
            val allMessages = withContext(Dispatchers.IO) {
                memoryStore?.get(sessionId)?.getHistory()
            }
            if (allMessages == null) {
                logger.debug { "No memory found for session $sessionId" }
                span.setAttribute("memory.message.count", "0")
                return emptyList()
            }
            logger.debug { "Loaded ${allMessages.size} messages for session $sessionId" }
            span.setAttribute("memory.message.count", allMessages.size.toString())
            loadFromHistory(sessionId, allMessages)
        } catch (e: Exception) {
            e.throwIfCancellation()
            span.setError(e)
            throw e
        } finally {
            span.close()
        }
    }

    /** 로드된 메시지에 요약 또는 takeLast를 적용하여 반환한다. */
    private suspend fun loadFromHistory(
        sessionId: String,
        allMessages: List<com.arc.reactor.agent.model.Message>
    ): List<Message> {
        if (!isSummaryEnabled() || allMessages.size <= summaryProps.triggerMessageCount) {
            return allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }
        return try {
            buildHierarchicalHistory(sessionId, allMessages)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Hierarchical memory failed for session $sessionId, falling back" }
            allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }
    }

    override suspend fun saveHistory(command: AgentCommand, result: AgentResult) {
        val assistantContent = if (result.success) result.content else null
        val savedCount = saveMessages(command.metadata, command.userId, command.userPrompt, assistantContent)
        triggerAsyncSummarization(command.metadata, savedCount)
    }

    override suspend fun saveStreamingHistory(command: AgentCommand, content: String) {
        val savedCount = saveMessages(command.metadata, command.userId, command.userPrompt, content.ifEmpty { null })
        triggerAsyncSummarization(command.metadata, savedCount)
    }

    /**
     * 전체 메시지에서 "요약할 구간"과 "최근 윈도우"를 나누는 분할 인덱스를 계산한다.
     * (전체 메시지 수 - 최근 유지할 메시지 수)로 산출하며, 최소 0을 보장한다.
     */
    private fun calculateSplitIndex(totalSize: Int): Int =
        (totalSize - summaryProps.recentMessageCount).coerceAtLeast(0)

    /**
     * 계층적 대화 이력을 빌드한다.
     *
     * 1) 메시지를 "요약할 구간"과 "최근 윈도우"로 분할
     * 2) 요약 구간에 대해 기존 요약이 있으면 재사용, 없으면 LLM으로 새로 생성
     * 3) [Facts SystemMessage] + [Narrative SystemMessage] + [최근 메시지]를 합쳐 반환
     * 4) 요약이 비어있으면(facts, narrative 모두 없음) takeLast로 안전하게 폴백
     */
    private suspend fun buildHierarchicalHistory(
        sessionId: String,
        allMessages: List<com.arc.reactor.agent.model.Message>
    ): List<Message> {
        val splitIndex = calculateSplitIndex(allMessages.size)

        // 분할 인덱스가 0이면 모든 메시지가 최근 윈도우에 포함 — 요약 불필요
        if (splitIndex == 0) {
            return allMessages.map { toSpringAiMessage(it) }
        }

        val messagesToSummarize = allMessages.subList(0, splitIndex)
        val recentMessages = allMessages.subList(splitIndex, allMessages.size)

        val existingSummary = summaryStore?.get(sessionId)

        // 기존 요약이 분할 인덱스 이상까지 커버하면 재사용, 아니면 새로 생성
        val summary = if (existingSummary != null && existingSummary.summarizedUpToIndex >= splitIndex) {
            existingSummary
        } else {
            summarizeAndStore(sessionId, messagesToSummarize, existingSummary)
        }

        val result = mutableListOf<Message>()

        // Facts: 정확한 수치, 결정 사항, 조건 등 구조화된 정보
        if (summary.facts.isNotEmpty()) {
            val factsText = summary.facts.joinToString("\n") { "- ${it.key}: ${it.value}" }
            result.add(SystemMessage("Conversation Facts:\n$factsText"))
        }

        // Narrative: 대화 흐름, 톤, 미해결 사항 등 서술형 요약
        if (summary.narrative.isNotBlank()) {
            result.add(SystemMessage("Conversation Summary:\n${summary.narrative}"))
        }

        // 요약이 비어있으면 맥락이 손실되므로 안전하게 takeLast로 폴백한다
        if (result.isEmpty()) {
            logger.warn {
                "Summary empty: facts=${summary.facts.size}, " +
                    "narrative=${summary.narrative.length} for session $sessionId, falling back to takeLast"
            }
            return allMessages.takeLast(properties.llm.maxConversationTurns * 2)
                .map { toSpringAiMessage(it) }
        }

        // 최근 메시지들은 원문 그대로 추가
        for (msg in recentMessages) {
            result.add(toSpringAiMessage(msg))
        }

        return result
    }

    /**
     * LLM으로 요약을 생성하고 저장소에 저장한다.
     */
    private suspend fun summarizeAndStore(
        sessionId: String,
        messages: List<com.arc.reactor.agent.model.Message>,
        existingSummary: ConversationSummary?
    ): ConversationSummary {
        val service = summaryService
            ?: throw IllegalStateException("SummaryService is required for hierarchical memory")

        val summaryResult = service.summarize(messages, existingSummary?.facts.orEmpty())
        val summary = ConversationSummary(
            sessionId = sessionId,
            narrative = summaryResult.narrative,
            facts = summaryResult.facts,
            summarizedUpToIndex = messages.size,
            createdAt = existingSummary?.createdAt ?: Instant.now(),
            updatedAt = Instant.now()
        )
        summaryStore?.save(summary)
        return summary
    }

    override fun cancelActiveSummarization(sessionId: String) {
        activeSummarizations.getIfPresent(sessionId)?.cancel()
        activeSummarizations.invalidate(sessionId)
    }

    /**
     * 비동기로 요약을 트리거한다.
     *
     * 대화가 트리거 임계값을 초과하면 백그라운드에서 요약을 생성한다.
     * 같은 세션에 대한 이전 요약 작업이 있으면 취소하고 새로 시작한다.
     * 실패해도 다음 loadHistory 호출 시 재시도되므로 로깅만 한다.
     */
    /**
     * 비동기로 요약을 트리거한다.
     *
     * [postSaveMessageCount]로 임계값을 사전 판단하여
     * 불필요한 store 재로드(DB 왕복)를 방지한다.
     * 실제 요약에 필요한 전체 히스토리는 백그라운드 코루틴 내부에서 로드한다.
     */
    private suspend fun triggerAsyncSummarization(metadata: Map<String, Any>, postSaveMessageCount: Int) {
        if (!isSummaryEnabled()) return
        if (postSaveMessageCount <= summaryProps.triggerMessageCount) return

        val sessionId = metadata["sessionId"]?.toString() ?: return

        val splitIndex = calculateSplitIndex(postSaveMessageCount)
        if (splitIndex == 0) return

        // 이전 요약 작업이 있으면 취소한다
        activeSummarizations.getIfPresent(sessionId)?.cancel()

        val job = asyncScope.launch {
            try {
                // 전체 히스토리는 백그라운드에서 로드 (호출 경로의 DB 왕복 제거)
                val memory = memoryStore?.get(sessionId) ?: return@launch
                val allMessages = memory.getHistory()
                val actualSplitIndex = calculateSplitIndex(allMessages.size)
                if (actualSplitIndex == 0) return@launch

                val messagesToSummarize = allMessages.subList(0, actualSplitIndex)
                val existingSummary = summaryStore?.get(sessionId)
                summarizeAndStore(sessionId, messagesToSummarize, existingSummary)
                logger.debug { "Async summarization completed for session $sessionId" }
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug(e) { "Async summarization failed for session $sessionId (will retry on next load)" }
            }
        }
        activeSummarizations.put(sessionId, job)
        job.invokeOnCompletion { activeSummarizations.invalidate(sessionId) }
    }

    /** 요약 기능이 활성화되었는지 확인한다. 세 가지 조건이 모두 충족되어야 한다. */
    private fun isSummaryEnabled(): Boolean =
        summaryProps.enabled && summaryStore != null && summaryService != null

    /** 빈 소멸 시 비동기 스코프와 캐시를 정리한다. */
    override fun destroy() {
        asyncScope.cancel()
        activeSummarizations.invalidateAll()
        sessionSaveLocks.invalidateAll()
    }

    /**
     * user + assistant 메시지 쌍을 MemoryStore에 원자적으로 저장한다.
     *
     * 세션별 코루틴 Mutex로 동시 요청 간 메시지 인터리빙을 방지한다.
     * suspend 컨텍스트에서 스레드를 차단하지 않는다.
     */
    /**
     * 세션 소유권을 검증한다.
     *
     * 세션에 이미 소유자가 있고, 요청자의 userId와 다르면 로그 경고 후 빈 이력을 반환하도록
     * 빈 리스트를 반환한다. 소유자가 없거나 일치하면 통과.
     * memoryStore가 없으면 검증 불가이므로 통과.
     */
    private suspend fun verifySessionOwnership(sessionId: String, userId: String?) {
        if (userId == null) return
        val store = memoryStore ?: return
        val owner = try {
            withContext(Dispatchers.IO) {
                store.getSessionOwner(sessionId)
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "세션 소유권 조회 실패 — fail-close로 접근 차단: sessionId=$sessionId" }
            throw SessionOwnershipVerificationException(sessionId, e)
        } ?: return
        if (owner != userId) {
            logger.warn {
                "세션 소유권 불일치: session=$sessionId, owner=$owner, requester=$userId"
            }
            throw SessionOwnershipException(sessionId, userId)
        }
    }

    /**
     * 메시지를 저장하고, 저장 후 세션의 총 메시지 수를 반환한다.
     * 반환값은 [triggerAsyncSummarization]의 사전 임계값 판단에 사용되어
     * 불필요한 store 재로드를 방지한다.
     */
    private suspend fun saveMessages(
        metadata: Map<String, Any>,
        userId: String?,
        userPrompt: String,
        assistantContent: String?
    ): Int {
        val sessionId = metadata["sessionId"]?.toString() ?: run {
            logger.debug { "Skipping save: no sessionId in metadata" }
            return 0
        }
        val store = memoryStore ?: run {
            logger.debug { "Skipping save: memoryStore is null" }
            return 0
        }
        val resolvedUserId = userId ?: "anonymous"
        val messageCount = if (assistantContent != null) 2 else 1

        val span = tracer.startSpan(
            "arc.memory.save",
            mapOf(
                "session.id" to sessionId,
                "memory.message.count" to messageCount.toString()
            )
        )
        try {
            val mutex = sessionSaveLocks.get(sessionId) { Mutex() }
            mutex.withLock {
                withContext(Dispatchers.IO) {
                    store.addMessage(sessionId, "user", userPrompt, resolvedUserId)
                    if (assistantContent != null) {
                        store.addMessage(sessionId, "assistant", assistantContent, resolvedUserId)
                    }
                }
                logger.debug { "Saved conversation for session $sessionId" }
            }
            // 저장 후 메시지 수 반환 (요약 임계값 사전 판단용)
            return store.get(sessionId)?.getHistory()?.size ?: messageCount
        } catch (e: Exception) {
            e.throwIfCancellation()
            span.setError(e)
            logger.error(e) { "Failed to save conversation history for session $sessionId" }
            return 0
        } finally {
            span.close()
        }
    }

    companion object {
        /**
         * Arc Reactor 메시지를 Spring AI 메시지로 변환한다.
         * MessageRole에 따라 적절한 Spring AI 메시지 타입으로 매핑한다.
         */
        fun toSpringAiMessage(msg: com.arc.reactor.agent.model.Message): Message {
            return when (msg.role) {
                MessageRole.USER -> MediaConverter.buildUserMessage(msg.content, msg.media)
                MessageRole.ASSISTANT -> AssistantMessage.builder().content(msg.content).build()
                MessageRole.SYSTEM -> SystemMessage(msg.content)
                MessageRole.TOOL -> ToolResponseMessage.builder()
                    .responses(
                        listOf(
                            ToolResponseMessage.ToolResponse(
                                "",
                                "tool",
                                ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(msg.content)
                            )
                        )
                    )
                    .build()
            }
        }
    }
}

/**
 * 세션 소유권 불일치 시 발생하는 예외.
 *
 * 요청자의 userId가 세션의 소유자와 다를 때 발생한다.
 * Guard 파이프라인에서 적절히 처리되어야 한다.
 */
class SessionOwnershipException(
    sessionId: String,
    requesterId: String
) : SecurityException("세션 접근 거부: session=$sessionId, requester=$requesterId")

/**
 * 세션 소유권 DB 조회 실패 시 발생하는 예외.
 * fail-close로 빈 이력을 반환하되, 500 대신 안전하게 처리한다.
 */
class SessionOwnershipVerificationException(
    sessionId: String,
    cause: Throwable
) : RuntimeException("세션 소유권 검증 DB 조회 실패: sessionId=$sessionId", cause)

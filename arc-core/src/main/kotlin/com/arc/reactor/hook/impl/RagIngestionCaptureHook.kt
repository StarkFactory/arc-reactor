package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyProvider
import com.arc.reactor.rag.ingestion.toDocuments
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import org.springframework.ai.vectorstore.VectorStore
import java.time.Instant
import java.util.UUID
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private val logger = KotlinLogging.logger {}

/**
 * RAG 수집 캡처 Hook
 *
 * 성공적인 사용자 Q&A를 RAG 수집 후보로 캡처하는 AfterAgentCompleteHook이다.
 *
 * ## 동작 모드
 * - **기본(requireReview=true)**: PENDING 상태로 저장하여 관리자 리뷰를 기다린다
 * - **자동 수집(requireReview=false + VectorStore 존재)**: 즉시 벡터 저장소에 수집하고 INGESTED로 기록
 *
 * ## 캡처 조건
 * - 에이전트 실행 성공
 * - 응답이 비어있지 않음
 * - 수집 정책이 활성화됨
 * - 질의와 응답이 최소 길이를 충족
 * - 허용된 채널에서의 요청
 * - 차단 패턴에 매칭되지 않음
 * - 동일 runId의 중복 캡처가 아님
 *
 * ## Order가 260인 이유
 * 피드백 캡처(250) 이후 실행된다. 두 Hook 모두 에이전트 응답에 영향을 주지 않으며,
 * RAG 수집은 피드백보다 우선순위가 낮다.
 *
 * @param policyProvider RAG 수집 정책 제공자
 * @param candidateStore 수집 후보 저장소
 * @param vectorStore 벡터 저장소 (자동 수집 모드용, 선택사항)
 * @param documentChunker 문서 청킹기 (선택사항)
 *
 * @see com.arc.reactor.hook.AfterAgentCompleteHook 에이전트 완료 후 Hook 인터페이스
 */
class RagIngestionCaptureHook(
    private val policyProvider: RagIngestionPolicyProvider,
    private val candidateStore: RagIngestionCandidateStore,
    private val vectorStore: VectorStore? = null,
    private val documentChunker: DocumentChunker? = null
) : AfterAgentCompleteHook {

    /** 정규식 컴파일 결과 캐시 (bounded, 1시간 TTL) */
    private val regexCache = Caffeine.newBuilder()
        .maximumSize(256)
        .expireAfterAccess(1, TimeUnit.HOURS)
        .build<String, Regex>()

    override val order: Int = 260

    // Fail-Open: RAG 수집 실패가 에이전트 응답에 영향을 주면 안 됨
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            // ── 단계 1: 기본 조건 확인 ──
            if (!response.success) return
            val answer = response.response?.trim().orEmpty()
            if (answer.isBlank()) return

            // ── 단계 2: 수집 정책 확인 ──
            val policy = policyProvider.current()
            if (!policy.enabled) return

            val query = context.userPrompt.trim()
            if (query.length < policy.minQueryChars) return
            if (answer.length < policy.minResponseChars) return

            // ── 단계 3: 채널 필터링 ──
            val channel = resolveChannel(context)
            if (!isAllowedChannel(channel, policy.allowedChannels)) return

            // ── 단계 4: 차단 패턴 확인 ──
            if (matchesBlockedPattern(query, answer, policy.blockedPatterns)) return

            // ── 단계 5: 중복 확인 ──
            val existing = candidateStore.findByRunId(context.runId)
            if (existing != null) return

            // ── 단계 6: 후보 생성 ──
            val baseCandidate = RagIngestionCandidate(
                runId = context.runId,
                userId = context.userId,
                sessionId = context.metadata["sessionId"]?.toString(),
                channel = channel,
                query = query,
                response = answer,
                status = RagIngestionCandidateStatus.PENDING,
                capturedAt = Instant.now()
            )

            // ── 단계 7: 리뷰 필요 여부에 따라 분기 ──
            if (policy.requireReview || vectorStore == null) {
                // 관리자 리뷰 대기: PENDING 상태로 저장
                candidateStore.save(baseCandidate)
                return
            }

            // ── 단계 8: 자동 수집 (리뷰 불필요 + VectorStore 존재) ──
            val documentId = UUID.randomUUID().toString()
            val documents = baseCandidate.toDocuments(
                documentId = documentId,
                chunker = documentChunker
            )
            vectorStore.add(documents)
            candidateStore.save(
                baseCandidate.copy(
                    status = RagIngestionCandidateStatus.INGESTED,
                    reviewedAt = Instant.now(),
                    reviewedBy = "system:auto",
                    reviewComment = "auto-ingested(requireReview=false)",
                    ingestedDocumentId = documentId
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to capture rag ingestion candidate for runId=${context.runId}" }
        }
    }

    /** 채널 정보를 메타데이터 또는 HookContext에서 조회한다 */
    private fun resolveChannel(context: HookContext): String? {
        val fromMetadata = context.metadata["channelId"]?.toString()?.trim()
        if (!fromMetadata.isNullOrBlank()) return fromMetadata.lowercase()
        return context.channel?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
    }

    /** 허용된 채널 목록에 포함되는지 확인한다 (빈 목록이면 모두 허용) */
    private fun isAllowedChannel(channel: String?, allowedChannels: Set<String>): Boolean {
        if (allowedChannels.isEmpty()) return true
        if (channel == null) return false
        return allowedChannels.contains(channel.lowercase())
    }

    /** 질의 또는 응답이 차단 패턴에 매칭되는지 확인한다 */
    private fun matchesBlockedPattern(query: String, response: String, blockedPatterns: Set<String>): Boolean {
        if (blockedPatterns.isEmpty()) return false
        for (rawPattern in blockedPatterns) {
            val pattern = compileRegex(rawPattern) ?: continue
            if (pattern.containsMatchIn(query) || pattern.containsMatchIn(response)) {
                return true
            }
        }
        return false
    }

    /** 정규식을 컴파일한다. Caffeine 캐시를 사용하여 중복 컴파일을 방지한다. */
    private fun compileRegex(rawPattern: String): Regex? {
        return regexCache.getIfPresent(rawPattern)
            ?: try {
                Pattern.compile(rawPattern, Pattern.CASE_INSENSITIVE).toRegex()
                    .also { regexCache.put(rawPattern, it) }
            } catch (e: PatternSyntaxException) {
                logger.warn { "Ignoring invalid blocked regex pattern: $rawPattern" }
                null
            }
    }
}

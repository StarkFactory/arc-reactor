package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolRouteMatchEngine
import com.arc.reactor.agent.config.ToolRoutingConfig
import com.arc.reactor.agent.model.AgentCommand

/**
 * RAG 검색 필요 여부를 빠르게 판단하는 키워드 기반 분류기.
 *
 * 모든 요청에 RAG 검색을 실행하면 단순 수학("1+1은?")이나 도구 기반 워크스페이스 쿼리("Jira 이슈 보여줘")에도
 * 1~1.5초의 불필요한 지연이 발생한다. 이 분류기는 정규식/키워드 매칭만으로 1ms 이내에 판단하여
 * RAG가 불필요한 요청의 지연을 제거한다.
 *
 * 판단 기준:
 * 1. 메타데이터에 `ragRequired=true` 또는 `ragFilters`가 있으면 항상 검색 실행
 * 2. 워크스페이스 도구 라우트가 매칭되면 RAG 생략 (도구 라우팅 우선)
 * 3. 프롬프트에 지식 쿼리 키워드(문서, knowledge, 가이드 등)가 포함되면 검색 실행
 * 4. 위 어느 것에도 해당하지 않으면 검색 생략 (단순 질문/일반 지식)
 *
 * @see AgentExecutionCoordinator RAG 검색 전 이 분류기로 사전 필터링
 * @see RagContextRetriever 실제 RAG 검색 수행
 */
internal object RagRelevanceClassifier {

    /**
     * 주어진 명령에 대해 RAG 검색이 필요한지 판단한다.
     *
     * @param command 에이전트 실행 명령
     * @return RAG 검색이 필요하면 true, 생략해도 되면 false
     */
    fun isRagRequired(command: AgentCommand): Boolean {
        if (command.metadata["ragRequired"] == true) return true
        if (hasExplicitRagFilters(command.metadata)) return true

        val prompt = command.userPrompt
        if (prompt.isBlank()) return false

        // 워크스페이스 도구 라우트가 매칭되면 RAG 생략 — 도구가 직접 데이터를 가져온다.
        if (matchesWorkspaceToolRoute(prompt)) return false

        // 지식 쿼리 키워드가 있을 때만 RAG 실행.
        val lowerPrompt = prompt.lowercase()
        return RAG_TRIGGER_KEYWORDS.any { lowerPrompt.contains(it) }
    }

    /** 메타데이터에 명시적 RAG 필터가 포함되어 있는지 확인한다. */
    private fun hasExplicitRagFilters(metadata: Map<String, Any>): Boolean {
        if (metadata.containsKey("ragFilters")) return true
        return metadata.keys.any { it.startsWith("rag.filter.") }
    }

    /**
     * 프롬프트가 워크스페이스 도구 라우트와 매칭되는지 확인한다.
     * 매칭되면 도구가 직접 데이터를 조회하므로 RAG가 불필요하다.
     */
    private fun matchesWorkspaceToolRoute(prompt: String): Boolean {
        val config = ToolRoutingConfig.loadFromClasspath()
        for (category in WORKSPACE_CATEGORIES) {
            if (ToolRouteMatchEngine.findFirstMatch(
                    category, prompt, config
                ) != null
            ) {
                return true
            }
        }
        return false
    }

    /** 도구 라우팅이 처리하는 워크스페이스 카테고리. RAG보다 우선한다. */
    private val WORKSPACE_CATEGORIES = listOf(
        "workContext", "work", "jira", "bitbucket", "swagger", "confluence"
    )

    /**
     * RAG 검색을 트리거하는 지식 쿼리 키워드.
     * 문서/지식 베이스 검색이 필요한 질문을 식별한다.
     */
    private val RAG_TRIGGER_KEYWORDS: Set<String> = setOf(
        // 문서/지식 관련 (한국어)
        "문서", "가이드", "규정", "런북", "사내", "지식",
        "나와 있", "나와있", "에 따르면", "에 의하면",
        "참고", "매뉴얼", "절차", "프로세스",
        // 기술 아키텍처 관련 (한국어) — 인제스트한 기술 문서 검색용
        "파이프라인", "아키텍처", "메커니즘", "동작 원리", "작동 방식",
        // 문서/지식 관련 (영어)
        "knowledge", "document", "guideline", "runbook", "manual",
        "internal doc", "policy", "procedure", "reference",
        "architecture", "pipeline", "mechanism", "how does",
        // 명시적 RAG 트리거
        "knowledge base", "지식 베이스", "지식베이스"
        // 참고: "confluence", "wiki" 등 workspace 도구 키워드는 여기서 제거됨.
        // 도구 라우팅(SemanticToolSelector)이 처리하며, RAG 경합을 방지한다.
        // 도구 라우트가 먼저 매칭되면 RAG를 생략한다.
    )
}

package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolRoute
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

        val lowerPrompt = prompt.lowercase()
        val hasRagKeyword = RAG_TRIGGER_KEYWORDS.any { lowerPrompt.contains(it) }

        // 워크스페이스 도구 라우트 매칭 확인 — 모든 매칭 라우트를 수집한다.
        val matchedRoutes = findAllWorkspaceRouteMatches(prompt)

        if (matchedRoutes.isNotEmpty()) {
            // 구체적 라우트(requiredKeywords 보유)가 하나라도 매칭되면 도구 우선 → RAG 생략.
            // "refund policy에 대해 알려줘" → unknown_term_lookup(포괄) + confluence_answer(구체적) 동시 매칭 → RAG 생략.
            val hasSpecificMatch = matchedRoutes.any { it.requiredKeywords.isNotEmpty() }
            if (hasSpecificMatch) return false

            // 포괄 라우트(requiredKeywords 없음)만 매칭되면 RAG 키워드 우선.
            // "장애 대응 런북 확인해줘" → jira_generic(포괄) 매칭이지만 "런북" RAG 키워드 → RAG 실행.
            if (hasRagKeyword) return true

            // RAG 키워드 없이 포괄 라우트 매칭 → 도구 우선.
            return false
        }

        // 도구 라우트 미매칭 → RAG 키워드가 있으면 RAG 실행.
        return hasRagKeyword
    }

    /** 메타데이터에 명시적 RAG 필터가 포함되어 있는지 확인한다. */
    private fun hasExplicitRagFilters(metadata: Map<String, Any>): Boolean {
        if (metadata.containsKey("ragFilters")) return true
        return metadata.keys.any { it.startsWith("rag.filter.") }
    }

    /**
     * 프롬프트가 워크스페이스 도구 라우트와 매칭되는 모든 라우트를 반환한다.
     * 구체적 라우트(requiredKeywords 보유)와 포괄 라우트를 구분하여
     * RAG 실행 여부를 정확하게 판단하기 위함이다.
     */
    private fun findAllWorkspaceRouteMatches(prompt: String): List<ToolRoute> {
        val config = ToolRoutingConfig.loadFromClasspath()
        val result = mutableListOf<ToolRoute>()
        for (category in WORKSPACE_CATEGORIES) {
            result.addAll(ToolRouteMatchEngine.findAllMatches(category, prompt, config))
        }
        return result
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
        // 도메인 개념 (한국어) — 벡터 스토어에 인제스트된 기술 주제
        "등록 방법", "설정 방법", "구성", "설명해",
        "react", "mcp", "guard", "hook",
        "루프", "라우팅", "캐시", "메모리",
        // 문서/지식 관련 (영어)
        "knowledge", "document", "guideline", "runbook", "manual",
        "internal doc", "policy", "procedure", "reference",
        "architecture", "pipeline", "mechanism", "how does",
        "how to", "what is", "explain", "setup", "configure",
        // 명시적 RAG 트리거
        "knowledge base", "지식 베이스", "지식베이스"
        // 참고: "confluence", "wiki" 등 workspace 도구 키워드는 여기서 제거됨.
        // 도구 라우팅(SemanticToolSelector)이 처리하며, RAG 경합을 방지한다.
        // 도구 라우트가 먼저 매칭되면 RAG를 생략한다.
    )
}

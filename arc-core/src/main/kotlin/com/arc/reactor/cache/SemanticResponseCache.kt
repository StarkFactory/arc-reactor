package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand

/**
 * 의미적(semantic) 캐시 조회/저장을 위한 선택적 확장 인터페이스.
 *
 * 정확한(exact) 키 매칭이 실패할 때, 의미적으로 유사한 프롬프트에 대해
 * 캐시된 응답을 반환할 수 있다. 요청 스코프 경계를 준수한다.
 *
 * ## 왜 의미적 캐시인가?
 * - "반품 정책이 뭐야?"와 "반품 정책을 알려줘"는 같은 질문이다
 * - 정확한 키 매칭으로는 이런 변형을 캐시 히트할 수 없다
 * - 임베딩 유사도로 프롬프트 간 의미적 동등성을 판단한다
 */
interface SemanticResponseCache : ResponseCache {

    /**
     * 의미적 유사도 기반으로 캐시를 조회한다.
     * 먼저 정확한 키로 조회하고, 실패하면 스코프 내 의미적 유사 항목을 탐색한다.
     */
    suspend fun getSemantic(command: AgentCommand, toolNames: List<String>, exactKey: String): CachedResponse?

    /**
     * 의미적 캐시에 응답을 저장한다.
     * 프롬프트 임베딩과 스코프 핑거프린트를 함께 저장하여
     * 이후 의미적 검색에 활용한다.
     */
    suspend fun putSemantic(
        command: AgentCommand,
        toolNames: List<String>,
        exactKey: String,
        response: CachedResponse
    )
}

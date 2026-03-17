package com.arc.reactor.controller

import com.arc.reactor.a2a.AgentCard
import com.arc.reactor.a2a.AgentCardProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A2A 에이전트 카드 컨트롤러.
 *
 * Google A2A 프로토콜의 `/.well-known/agent-card.json` 엔드포인트를 제공한다.
 * 다른 에이전트나 클라이언트가 이 에이전트의 능력을 자동으로 발견할 수 있다.
 *
 * [AgentCardProvider] 빈이 존재할 때만 활성화된다.
 * (`arc.reactor.a2a.enabled=true` 설정 필요)
 *
 * @param agentCardProvider 에이전트 카드 생성 프로바이더
 * @see AgentCard 에이전트 카드 데이터 모델
 * @see AgentCardProvider 카드 생성 인터페이스
 */
@RestController
@Tag(name = "A2A", description = "Google A2A 프로토콜 — 에이전트 능력 광고")
@ConditionalOnBean(AgentCardProvider::class)
class AgentCardController(
    private val agentCardProvider: AgentCardProvider
) {

    /**
     * 에이전트 카드를 JSON으로 반환한다.
     *
     * 이 엔드포인트는 인증 없이 접근 가능하도록 설계되었다.
     * A2A 프로토콜에서 에이전트 발견은 공개 엔드포인트로 동작해야 하기 때문이다.
     *
     * @return 에이전트 카드 JSON
     */
    @GetMapping("/.well-known/agent-card.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "A2A 에이전트 카드 조회", description = "에이전트의 이름, 버전, 능력 목록을 JSON으로 반환한다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "에이전트 카드 반환 성공")
    )
    fun getAgentCard(): AgentCard = agentCardProvider.generate()
}

package com.arc.reactor.a2a

import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.tool.ToolCallback
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A2A 에이전트 카드.
 *
 * Google A2A(Agent-to-Agent) 프로토콜의 에이전트 능력 광고(Agent Card)를 표현한다.
 * 에이전트가 자신의 이름, 버전, 지원 도구, 입출력 형식 등 메타데이터를
 * 표준 JSON으로 노출하여 다른 에이전트나 클라이언트가 자동으로 발견할 수 있게 한다.
 *
 * ## A2A 프로토콜 개요
 * - `/.well-known/agent-card.json` 엔드포인트로 노출
 * - 에이전트 간 자동 발견 및 능력 교환 지원
 *
 * @param name 에이전트 이름
 * @param version 에이전트 버전
 * @param description 에이전트 설명
 * @param capabilities 에이전트가 제공하는 능력 목록
 * @param supportedInputFormats 지원하는 입력 형식 목록
 * @param supportedOutputFormats 지원하는 출력 형식 목록
 * @see AgentCapability 개별 능력 정의
 * @see AgentCardProvider 카드 생성 인터페이스
 */
data class AgentCard(
    val name: String,
    val version: String,
    val description: String,
    val capabilities: List<AgentCapability>,
    val supportedInputFormats: List<String> = listOf("text", "json"),
    val supportedOutputFormats: List<String> = listOf("text", "json", "yaml")
)

/**
 * 에이전트 능력 정의.
 *
 * 에이전트가 수행할 수 있는 개별 기능을 기술한다.
 * 도구 또는 페르소나에서 자동 생성되거나, 수동으로 정의할 수 있다.
 *
 * @param name 능력 이름 (도구 이름 또는 페르소나 이름)
 * @param description 능력 설명
 * @param inputSchema 입력 JSON Schema (도구인 경우). null이면 스키마 미제공.
 */
data class AgentCapability(
    val name: String,
    val description: String,
    val inputSchema: String? = null
)

/**
 * 에이전트 카드 프로바이더 인터페이스.
 *
 * 등록된 도구와 페르소나 정보를 수집하여 [AgentCard]를 생성한다.
 * 사용자가 커스텀 프로바이더를 제공하여 카드 내용을 자유롭게 구성할 수 있다.
 *
 * @see DefaultAgentCardProvider 기본 구현
 */
interface AgentCardProvider {
    /**
     * 에이전트 카드를 생성한다.
     *
     * @return 현재 등록된 도구와 페르소나를 기반으로 생성된 에이전트 카드
     */
    fun generate(): AgentCard
}

/**
 * A2A 에이전트 카드 설정.
 *
 * `arc.reactor.a2a.*` 접두사로 바인딩된다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     a2a:
 *       enabled: true
 *       agent-name: my-agent
 *       agent-version: 1.0.0
 *       agent-description: "AI 에이전트 서비스"
 * ```
 *
 * @param enabled A2A 에이전트 카드 활성화 여부. 기본 비활성 (opt-in).
 * @param agentName 에이전트 이름. 기본값 "arc-reactor".
 * @param agentVersion 에이전트 버전. 기본값 "1.0.0".
 * @param agentDescription 에이전트 설명.
 */
data class A2aProperties(
    /** A2A 에이전트 카드 활성화 여부. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 에이전트 이름. */
    val agentName: String = "arc-reactor",

    /** 에이전트 버전. */
    val agentVersion: String = "1.0.0",

    /** 에이전트 설명. */
    val agentDescription: String = "Arc Reactor AI Agent"
)

/**
 * 기본 에이전트 카드 프로바이더.
 *
 * 등록된 도구 목록과 페르소나 저장소에서 정보를 수집하여
 * [AgentCard]를 자동 생성한다.
 *
 * - 도구: 이름, 설명, inputSchema를 [AgentCapability]로 변환
 * - 페르소나: 활성 페르소나의 이름과 설명을 [AgentCapability]로 변환
 *
 * @param properties A2A 설정
 * @param tools 등록된 도구 목록
 * @param personaStore 페르소나 저장소
 * @see AgentCardProvider 프로바이더 인터페이스
 */
class DefaultAgentCardProvider(
    private val properties: A2aProperties,
    private val tools: List<ToolCallback>,
    private val personaStore: PersonaStore
) : AgentCardProvider {

    override fun generate(): AgentCard {
        val capabilities = mutableListOf<AgentCapability>()

        // 도구에서 능력 수집
        for (tool in tools) {
            capabilities.add(
                AgentCapability(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema
                )
            )
        }

        // 페르소나에서 능력 수집
        try {
            val personas = personaStore.list()
            for (persona in personas) {
                if (!persona.isActive) continue
                capabilities.add(
                    AgentCapability(
                        name = "persona:${persona.name}",
                        description = persona.description ?: persona.name
                    )
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "A2A: 페르소나 조회 실패, 도구 기반 카드만 생성" }
        }

        return AgentCard(
            name = properties.agentName,
            version = properties.agentVersion,
            description = properties.agentDescription,
            capabilities = capabilities
        )
    }
}

package com.arc.reactor.agent.plan

/**
 * LLM이 생성하는 계획의 단일 단계.
 *
 * 계획-실행 모드에서 LLM은 사용자 요청을 분석하여 이 데이터 클래스의 리스트를 JSON으로 출력한다.
 *
 * @property tool 호출할 도구 이름
 * @property args 도구에 전달할 인수
 * @property description 이 단계에 대한 설명
 * @see com.arc.reactor.agent.impl.PlanExecuteStrategy 이 데이터를 사용하는 전략
 * @see PlanValidator 이 데이터를 검증하는 검증기
 */
data class PlanStep(
    val tool: String = "",
    val args: Map<String, Any?> = emptyMap(),
    val description: String = ""
)

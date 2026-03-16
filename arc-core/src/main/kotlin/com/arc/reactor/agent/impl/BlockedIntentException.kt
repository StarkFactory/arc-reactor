package com.arc.reactor.agent.impl

/**
 * 분류된 인텐트가 차단 목록에 포함되어 있을 때 발생하는 예외.
 *
 * [PreExecutionResolver.resolveIntent]에서 차단 대상 인텐트로 분류되면 이 예외를 던지며,
 * [SpringAiAgentExecutor]에서 GUARD_REJECTED 에러 코드로 처리한다.
 *
 * @param intentName 차단된 인텐트 이름
 * @see PreExecutionResolver 인텐트 해석 시 차단 여부 확인
 * @see SpringAiAgentExecutor 이 예외를 catch하여 에러 응답 생성
 */
class BlockedIntentException(
    val intentName: String
) : Exception("Intent '$intentName' is blocked by policy")

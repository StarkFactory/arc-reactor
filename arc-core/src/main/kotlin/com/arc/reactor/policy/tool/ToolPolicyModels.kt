package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyProperties
import java.time.Instant

/**
 * 도구 정책 설정 데이터 클래스
 *
 * 동적 정책이 활성화되면 관리자가 런타임에 관리할 수 있다.
 * 의도적으로 전역(사용자별 아님) 설정으로, 기업의 "하나의 에이전트" 배포에 맞춘다.
 *
 * @property enabled 정책 활성화 여부
 * @property writeToolNames 쓰기(부작용 있는) 도구 이름 집합
 * @property denyWriteChannels 쓰기 도구가 차단되는 채널 집합
 * @property allowWriteToolNamesInDenyChannels 거부 채널에서도 허용되는 전역 예외 도구 집합
 * @property allowWriteToolNamesByChannel 채널별 허용 도구 매핑 (채널 → 도구 이름 집합)
 * @property denyWriteMessage 거부 시 사용자에게 표시할 메시지
 * @property createdAt 정책 생성 시각
 * @property updatedAt 정책 마지막 수정 시각
 *
 * @see ToolPolicyProvider 이 정책을 런타임에 제공하는 Provider
 * @see ToolExecutionPolicyEngine 이 정책을 평가하는 엔진
 */
data class ToolPolicy(
    val enabled: Boolean,
    val writeToolNames: Set<String>,
    val denyWriteChannels: Set<String>,
    val allowWriteToolNamesInDenyChannels: Set<String>,
    val allowWriteToolNamesByChannel: Map<String, Set<String>>,
    val denyWriteMessage: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        /** application.yml 속성에서 ToolPolicy를 생성한다 */
        fun fromProperties(props: ToolPolicyProperties): ToolPolicy = ToolPolicy(
            enabled = props.enabled,
            writeToolNames = props.writeToolNames,
            denyWriteChannels = props.denyWriteChannels,
            allowWriteToolNamesInDenyChannels = props.allowWriteToolNamesInDenyChannels,
            allowWriteToolNamesByChannel = props.allowWriteToolNamesByChannel,
            denyWriteMessage = props.denyWriteMessage
        )
    }
}

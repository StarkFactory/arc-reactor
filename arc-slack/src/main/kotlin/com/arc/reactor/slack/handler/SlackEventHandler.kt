package com.arc.reactor.slack.handler

import com.arc.reactor.slack.model.SlackEventCommand

/**
 * Slack 이벤트 처리 인터페이스.
 *
 * 멘션, 메시지, 채널 메시지(선행적), 리액션 이벤트 처리를 정의한다.
 * 커스텀 빈으로 등록하여 기본 동작([DefaultSlackEventHandler])을 교체할 수 있다.
 *
 * @see DefaultSlackEventHandler
 */
interface SlackEventHandler {

    /**
     * @mention 이벤트(app_mention)를 처리한다.
     * 사용자가 채널이나 DM에서 봇을 멘션했을 때 호출된다.
     */
    suspend fun handleAppMention(command: SlackEventCommand)

    /**
     * 봇이 참여 중인 스레드의 메시지 이벤트를 처리한다.
     * 최초 멘션 이후의 스레드 답글에 대해 호출된다.
     */
    suspend fun handleMessage(command: SlackEventCommand)

    /**
     * 선행적 지원을 위한 채널 메시지를 처리한다.
     * 선행적 모니터링이 활성화된 채널의 최상위 메시지에 대해 호출된다.
     *
     * @return 에이전트가 응답했으면 true, 거부(유용한 맥락 없음)했으면 false
     */
    suspend fun handleChannelMessage(command: SlackEventCommand): Boolean = false

    /**
     * 피드백 수집을 위한 reaction_added 이벤트를 처리한다.
     * 사용자가 추적 중인 봇 응답에 thumbsup/thumbsdown 리액션을 추가했을 때 호출된다.
     */
    suspend fun handleReaction(
        userId: String,
        channelId: String,
        messageTs: String,
        reaction: String,
        sessionId: String,
        userPrompt: String
    ) {}
}

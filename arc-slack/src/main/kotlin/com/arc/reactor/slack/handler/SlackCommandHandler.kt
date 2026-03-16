package com.arc.reactor.slack.handler

import com.arc.reactor.slack.model.SlackSlashCommand

/**
 * Slack 슬래시 명령 처리 인터페이스.
 *
 * 커스텀 빈으로 등록하여 기본 동작을 교체할 수 있다.
 *
 * @see DefaultSlackCommandHandler
 */
interface SlackCommandHandler {
    suspend fun handleSlashCommand(command: SlackSlashCommand)
}

package com.arc.reactor.scheduler

/**
 * Incoming Webhook을 통해 Microsoft Teams 채널에 메시지를 전송한다.
 *
 * WHY: 스케줄러와 Teams 모듈의 의존성을 분리하기 위한 함수형 인터페이스.
 * 스케줄러가 Teams SDK에 직접 의존하지 않고, 실행 결과를 Teams로 전달할 수 있게 한다.
 *
 * @see DynamicSchedulerService 스케줄러에서의 활용
 * @see SlackMessageSender Slack 메시지 전송 인터페이스
 */
fun interface TeamsMessageSender {
    /**
     * Teams 채널에 메시지를 전송한다.
     *
     * @param webhookUrl Teams Incoming Webhook URL
     * @param text 전송할 메시지 텍스트
     */
    fun sendMessage(webhookUrl: String, text: String)
}

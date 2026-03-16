package com.arc.reactor.slack.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Slack 통합 모듈 설정 프로퍼티.
 *
 * 프리픽스: `arc.reactor.slack`
 *
 * 전송 모드, 토큰, 서명 검증, 동시성, backpressure, 이벤트 중복 제거,
 * 스레드 추적, 선행적 모니터링, 리액션 피드백 등 전체 Slack 동작을 제어한다.
 */
@ConfigurationProperties(prefix = "arc.reactor.slack")
data class SlackProperties(
    /** Slack 통합 활성화 여부. 기본값: false (명시적 opt-in 필요) */
    val enabled: Boolean = false,

    /**
     * Slack 전송 모드.
     * - EVENTS_API: HTTP 엔드포인트를 통해 페이로드를 수신한다
     * - SOCKET_MODE: WebSocket을 통해 페이로드를 수신한다 (공개 콜백 URL 불필요)
     */
    val transportMode: SlackTransportMode = SlackTransportMode.SOCKET_MODE,

    /** Slack Bot User OAuth 토큰 (xoxb-...) */
    val botToken: String = "",

    /** Socket Mode용 Slack App-Level 토큰 (xapp-...) */
    val appToken: String = "",

    /** 요청 서명 검증용 Slack signing secret */
    val signingSecret: String = "",

    /** 수신 Slack 요청에 대해 HMAC-SHA256 서명 검증을 활성화한다 */
    val signatureVerificationEnabled: Boolean = true,

    /** 서명 검증 시 허용하는 최대 시간 오차 (초) */
    val timestampToleranceSeconds: Long = 300,

    /** 최대 동시 Slack 이벤트 처리 수 */
    val maxConcurrentRequests: Int = 5,

    /** 에이전트 실행 요청 타임아웃 (밀리초) */
    val requestTimeoutMs: Long = 30000,

    /**
     * true이면 처리 세마포어가 포화 상태일 때 즉시 거부한다.
     * 버스트 트래픽 발생 시 코루틴 큐 적체를 방지한다.
     */
    val failFastOnSaturation: Boolean = true,

    /**
     * true이면 이벤트/명령이 드롭될 때 Slack으로 사용 중(busy) 알림을 전송한다.
     * 고부하 환경에서는 아웃바운드 트래픽 증폭을 방지하기 위해 false로 유지한다.
     */
    val notifyOnDrop: Boolean = false,

    /** Slack Web API의 재시도 가능한 오류(429/5xx) 발생 시 최대 재시도 횟수 */
    val apiMaxRetries: Int = 2,

    /** Retry-After 헤더가 없을 때 기본 재시도 지연 (밀리초) */
    val apiRetryDefaultDelayMs: Long = 1000,

    /** Slack users.info에서 요청자 이메일을 조회하여 AgentCommand 메타데이터에 첨부한다 */
    val userEmailResolutionEnabled: Boolean = true,

    /** 조회된 Slack 사용자 이메일의 인메모리 캐시 TTL (초) */
    val userEmailCacheTtlSeconds: Long = 3600,

    /** 조회된 Slack 사용자 이메일의 최대 인메모리 캐시 엔트리 수 */
    val userEmailCacheMaxEntries: Int = 20000,

    /** event_id 기반 인메모리 중복 제거 활성화 (Slack Events API용) */
    val eventDedupEnabled: Boolean = true,

    /** 중복 제거 event_id 캐시 보관 기간 (초) */
    val eventDedupTtlSeconds: Long = 600,

    /** 중복 제거를 위해 보관하는 최대 event_id 엔트리 수 */
    val eventDedupMaxEntries: Int = 10000,

    /** Arc Reactor가 개시한 Slack 스레드를 추적하여 관련 없는 스레드 부작용을 방지한다 */
    val threadTrackingEnabled: Boolean = true,

    /** 추적 중인 Slack 스레드의 보관 기간 (초) */
    val threadTrackingTtlSeconds: Long = 86400,

    /** 최대 인메모리 추적 스레드 엔트리 수 */
    val threadTrackingMaxEntries: Int = 20000,

    /**
     * true이면 thread_ts 없는 최상위 DM(channel_type=im/mpim)도 처리한다.
     * 스레드 전용 동작을 유지하려면 false로 유지한다.
     */
    val processDirectMessagesWithoutThread: Boolean = false,

    /** Socket Mode WebSocket 백엔드 구현체 */
    val socketBackend: SlackSocketBackend = SlackSocketBackend.JAVA_WEBSOCKET,

    /** Socket Mode 시작 시 연결 실패에 대한 초기 재시도 지연 (밀리초) */
    val socketConnectRetryInitialDelayMs: Long = 1000,

    /** Socket Mode 시작 시 연결 실패에 대한 최대 재시도 지연 (밀리초) */
    val socketConnectRetryMaxDelayMs: Long = 30000,

    /** 선행적 채널 모니터링 활성화. 봇이 메시지를 관찰하고 관련 시 도움을 제안한다. */
    val proactiveEnabled: Boolean = false,

    /** 선행적 모니터링이 활성화된 채널 ID 목록. 비어 있으면 proactiveEnabled=true여도 비활성화. */
    val proactiveChannelIds: List<String> = emptyList(),

    /** LLM 비용 급증을 방지하기 위한 최대 동시 선행적 평가 수 */
    val proactiveMaxConcurrent: Int = 2,

    /** 봇 응답에 대한 Slack 이모지 리액션(thumbsup/thumbsdown)으로 피드백을 수집한다 */
    val reactionFeedbackEnabled: Boolean = true,

    /** 사용자별 장기 기억(팀, 역할, 선호도)을 에이전트 시스템 프롬프트에 주입한다 */
    val userMemoryEnabled: Boolean = true
)

enum class SlackTransportMode {
    EVENTS_API,
    SOCKET_MODE
}

enum class SlackSocketBackend {
    JAVA_WEBSOCKET,
    TYRUS
}

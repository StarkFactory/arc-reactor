package com.arc.reactor.slack.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SlackProperties] 기본값 및 data class 계약 테스트.
 *
 * 설정 프로퍼티는 런타임에 묵시적으로 사용되므로 기본값 변경은 silent 장애로 이어진다.
 * 각 프로퍼티의 기본값이 의도한 값을 유지하는지 회귀 테스트한다.
 */
class SlackPropertiesTest {

    @Nested
    inner class `활성화 및 전송 모드 기본값` {

        @Test
        fun `enabled 기본값은 false이어야 한다`() {
            val props = SlackProperties()

            assertFalse(props.enabled) {
                "Slack 통합은 명시적 opt-in이 필요하므로 기본값이 false여야 한다. actual: ${props.enabled}"
            }
        }

        @Test
        fun `transportMode 기본값은 SOCKET_MODE이어야 한다`() {
            val props = SlackProperties()

            assertEquals(SlackTransportMode.SOCKET_MODE, props.transportMode) {
                "공개 콜백 URL 없이 동작 가능한 SOCKET_MODE가 기본 전송 모드여야 한다. actual: ${props.transportMode}"
            }
        }

        @Test
        fun `socketBackend 기본값은 JAVA_WEBSOCKET이어야 한다`() {
            val props = SlackProperties()

            assertEquals(SlackSocketBackend.JAVA_WEBSOCKET, props.socketBackend) {
                "기본 Socket Mode 백엔드는 JAVA_WEBSOCKET이어야 한다. actual: ${props.socketBackend}"
            }
        }
    }

    @Nested
    inner class `토큰 및 서명 검증 기본값` {

        @Test
        fun `botToken 기본값은 빈 문자열이어야 한다`() {
            val props = SlackProperties()

            assertEquals("", props.botToken) {
                "botToken은 기본값이 없으므로 빈 문자열이어야 한다. actual: ${props.botToken}"
            }
        }

        @Test
        fun `appToken 기본값은 빈 문자열이어야 한다`() {
            val props = SlackProperties()

            assertEquals("", props.appToken) {
                "appToken은 기본값이 없으므로 빈 문자열이어야 한다. actual: ${props.appToken}"
            }
        }

        @Test
        fun `signingSecret 기본값은 빈 문자열이어야 한다`() {
            val props = SlackProperties()

            assertEquals("", props.signingSecret) {
                "signingSecret은 기본값이 없으므로 빈 문자열이어야 한다. actual: ${props.signingSecret}"
            }
        }

        @Test
        fun `signatureVerificationEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.signatureVerificationEnabled) {
                "보안상 서명 검증은 기본 활성화되어야 한다. actual: ${props.signatureVerificationEnabled}"
            }
        }

        @Test
        fun `timestampToleranceSeconds 기본값은 300이어야 한다`() {
            val props = SlackProperties()

            assertEquals(300L, props.timestampToleranceSeconds) {
                "타임스탬프 허용 오차는 5분(300초)이 기본값이어야 한다. actual: ${props.timestampToleranceSeconds}"
            }
        }
    }

    @Nested
    inner class `동시성 및 타임아웃 기본값` {

        @Test
        fun `maxConcurrentRequests 기본값은 5이어야 한다`() {
            val props = SlackProperties()

            assertEquals(5, props.maxConcurrentRequests) {
                "최대 동시 처리 수는 5가 기본값이어야 한다. actual: ${props.maxConcurrentRequests}"
            }
        }

        @Test
        fun `requestTimeoutMs 기본값은 30000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(30_000L, props.requestTimeoutMs) {
                "에이전트 실행 타임아웃은 30초(30000ms)가 기본값이어야 한다. actual: ${props.requestTimeoutMs}"
            }
        }

        @Test
        fun `failFastOnSaturation 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.failFastOnSaturation) {
                "버스트 트래픽 시 코루틴 큐 적체 방지를 위해 기본 활성화여야 한다. actual: ${props.failFastOnSaturation}"
            }
        }

        @Test
        fun `notifyOnDrop 기본값은 false이어야 한다`() {
            val props = SlackProperties()

            assertFalse(props.notifyOnDrop) {
                "고부하 환경에서 아웃바운드 트래픽 증폭 방지를 위해 기본 비활성화여야 한다. actual: ${props.notifyOnDrop}"
            }
        }
    }

    @Nested
    inner class `API 재시도 기본값` {

        @Test
        fun `apiMaxRetries 기본값은 2이어야 한다`() {
            val props = SlackProperties()

            assertEquals(2, props.apiMaxRetries) {
                "Slack Web API 최대 재시도 횟수는 2가 기본값이어야 한다. actual: ${props.apiMaxRetries}"
            }
        }

        @Test
        fun `apiRetryDefaultDelayMs 기본값은 1000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(1_000L, props.apiRetryDefaultDelayMs) {
                "재시도 기본 지연은 1초(1000ms)여야 한다. actual: ${props.apiRetryDefaultDelayMs}"
            }
        }
    }

    @Nested
    inner class `사용자 이메일 캐시 기본값` {

        @Test
        fun `userEmailResolutionEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.userEmailResolutionEnabled) {
                "AgentCommand 메타데이터 보강을 위해 이메일 조회는 기본 활성화여야 한다. actual: ${props.userEmailResolutionEnabled}"
            }
        }

        @Test
        fun `userEmailCacheTtlSeconds 기본값은 3600이어야 한다`() {
            val props = SlackProperties()

            assertEquals(3_600L, props.userEmailCacheTtlSeconds) {
                "사용자 이메일 캐시 TTL은 1시간(3600초)이 기본값이어야 한다. actual: ${props.userEmailCacheTtlSeconds}"
            }
        }

        @Test
        fun `userEmailCacheMaxEntries 기본값은 20000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(20_000, props.userEmailCacheMaxEntries) {
                "사용자 이메일 캐시 최대 엔트리 수는 20000이어야 한다. actual: ${props.userEmailCacheMaxEntries}"
            }
        }
    }

    @Nested
    inner class `이벤트 중복 제거 기본값` {

        @Test
        fun `eventDedupEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.eventDedupEnabled) {
                "Events API 중복 처리 방지를 위해 기본 활성화여야 한다. actual: ${props.eventDedupEnabled}"
            }
        }

        @Test
        fun `eventDedupTtlSeconds 기본값은 600이어야 한다`() {
            val props = SlackProperties()

            assertEquals(600L, props.eventDedupTtlSeconds) {
                "중복 제거 event_id 보관 기간은 10분(600초)이 기본값이어야 한다. actual: ${props.eventDedupTtlSeconds}"
            }
        }

        @Test
        fun `eventDedupMaxEntries 기본값은 10000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(10_000, props.eventDedupMaxEntries) {
                "중복 제거 최대 엔트리 수는 10000이어야 한다. actual: ${props.eventDedupMaxEntries}"
            }
        }
    }

    @Nested
    inner class `스레드 추적 기본값` {

        @Test
        fun `threadTrackingEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.threadTrackingEnabled) {
                "관련 없는 스레드 부작용 방지를 위해 스레드 추적은 기본 활성화여야 한다. actual: ${props.threadTrackingEnabled}"
            }
        }

        @Test
        fun `threadTrackingTtlSeconds 기본값은 86400이어야 한다`() {
            val props = SlackProperties()

            assertEquals(86_400L, props.threadTrackingTtlSeconds) {
                "스레드 추적 보관 기간은 1일(86400초)이 기본값이어야 한다. actual: ${props.threadTrackingTtlSeconds}"
            }
        }

        @Test
        fun `threadTrackingMaxEntries 기본값은 20000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(20_000, props.threadTrackingMaxEntries) {
                "스레드 추적 최대 엔트리 수는 20000이어야 한다. actual: ${props.threadTrackingMaxEntries}"
            }
        }

        @Test
        fun `processDirectMessagesWithoutThread 기본값은 false이어야 한다`() {
            val props = SlackProperties()

            assertFalse(props.processDirectMessagesWithoutThread) {
                "스레드 전용 동작 유지를 위해 DM 비스레드 처리는 기본 비활성화여야 한다. actual: ${props.processDirectMessagesWithoutThread}"
            }
        }
    }

    @Nested
    inner class `선행적 모니터링 기본값` {

        @Test
        fun `proactiveEnabled 기본값은 false이어야 한다`() {
            val props = SlackProperties()

            assertFalse(props.proactiveEnabled) {
                "선행적 채널 모니터링은 명시적 opt-in이 필요하므로 기본 비활성화여야 한다. actual: ${props.proactiveEnabled}"
            }
        }

        @Test
        fun `proactiveChannelIds 기본값은 빈 리스트이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.proactiveChannelIds.isEmpty()) {
                "모니터링 채널 목록은 기본값이 빈 리스트여야 한다. actual: ${props.proactiveChannelIds}"
            }
        }

        @Test
        fun `proactiveMaxConcurrent 기본값은 2이어야 한다`() {
            val props = SlackProperties()

            assertEquals(2, props.proactiveMaxConcurrent) {
                "LLM 비용 급증 방지를 위한 최대 동시 선행적 평가 수는 2가 기본값이어야 한다. actual: ${props.proactiveMaxConcurrent}"
            }
        }
    }

    @Nested
    inner class `피드백 및 메모리 기본값` {

        @Test
        fun `reactionFeedbackEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.reactionFeedbackEnabled) {
                "이모지 리액션 피드백 수집은 기본 활성화여야 한다. actual: ${props.reactionFeedbackEnabled}"
            }
        }

        @Test
        fun `userMemoryEnabled 기본값은 true이어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.userMemoryEnabled) {
                "사용자별 장기 기억 주입은 기본 활성화여야 한다. actual: ${props.userMemoryEnabled}"
            }
        }
    }

    @Nested
    inner class `response_url 재시도 기본값` {

        @Test
        fun `responseUrlMaxRetries 기본값은 3이어야 한다`() {
            val props = SlackProperties()

            assertEquals(3, props.responseUrlMaxRetries) {
                "response_url 최대 재시도 횟수는 3이 기본값이어야 한다. actual: ${props.responseUrlMaxRetries}"
            }
        }

        @Test
        fun `responseUrlInitialDelayMs 기본값은 500이어야 한다`() {
            val props = SlackProperties()

            assertEquals(500L, props.responseUrlInitialDelayMs) {
                "response_url 재시도 초기 지연은 500ms가 기본값이어야 한다. actual: ${props.responseUrlInitialDelayMs}"
            }
        }

        @Test
        fun `responseUrlMaxDelayMs 기본값은 8000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(8_000L, props.responseUrlMaxDelayMs) {
                "response_url 재시도 최대 지연은 8초(8000ms)가 기본값이어야 한다. actual: ${props.responseUrlMaxDelayMs}"
            }
        }
    }

    @Nested
    inner class `사용자별 레이트 리밋 기본값` {

        @Test
        fun `userRateLimitEnabled 기본값은 false이어야 한다`() {
            val props = SlackProperties()

            assertFalse(props.userRateLimitEnabled) {
                "사용자별 레이트 리밋은 명시적 opt-in이므로 기본 비활성화여야 한다. actual: ${props.userRateLimitEnabled}"
            }
        }

        @Test
        fun `userRateLimitMaxPerMinute 기본값은 10이어야 한다`() {
            val props = SlackProperties()

            assertEquals(10, props.userRateLimitMaxPerMinute) {
                "사용자당 분당 최대 요청 수는 10이 기본값이어야 한다. actual: ${props.userRateLimitMaxPerMinute}"
            }
        }
    }

    @Nested
    inner class `Socket Mode 재시도 기본값` {

        @Test
        fun `socketConnectRetryInitialDelayMs 기본값은 1000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(1_000L, props.socketConnectRetryInitialDelayMs) {
                "Socket Mode 초기 재시도 지연은 1초(1000ms)가 기본값이어야 한다. actual: ${props.socketConnectRetryInitialDelayMs}"
            }
        }

        @Test
        fun `socketConnectRetryMaxDelayMs 기본값은 30000이어야 한다`() {
            val props = SlackProperties()

            assertEquals(30_000L, props.socketConnectRetryMaxDelayMs) {
                "Socket Mode 최대 재시도 지연은 30초(30000ms)가 기본값이어야 한다. actual: ${props.socketConnectRetryMaxDelayMs}"
            }
        }
    }

    @Nested
    inner class `data class 계약` {

        @Test
        fun `copy로 특정 필드만 변경할 수 있다`() {
            val original = SlackProperties()
            val modified = original.copy(enabled = true, botToken = "xoxb-test-token")

            assertTrue(modified.enabled) {
                "copy 후 enabled가 true로 변경되어야 한다. actual: ${modified.enabled}"
            }
            assertEquals("xoxb-test-token", modified.botToken) {
                "copy 후 botToken이 변경되어야 한다. actual: ${modified.botToken}"
            }
            assertEquals(original.maxConcurrentRequests, modified.maxConcurrentRequests) {
                "copy 시 변경하지 않은 maxConcurrentRequests는 원본과 동일해야 한다."
            }
        }

        @Test
        fun `동일한 프로퍼티를 가진 두 인스턴스는 equals가 true이어야 한다`() {
            val a = SlackProperties(botToken = "xoxb-abc")
            val b = SlackProperties(botToken = "xoxb-abc")

            assertEquals(a, b) {
                "동일한 프로퍼티를 가진 SlackProperties 인스턴스는 equals=true여야 한다."
            }
        }

        @Test
        fun `프로퍼티가 다른 두 인스턴스는 equals가 false이어야 한다`() {
            val a = SlackProperties(botToken = "xoxb-abc")
            val b = SlackProperties(botToken = "xoxb-xyz")

            assertNotEquals(a, b) {
                "botToken이 다른 SlackProperties 인스턴스는 equals=false여야 한다."
            }
        }

        @Test
        fun `toString에 클래스명이 포함되어야 한다`() {
            val props = SlackProperties()

            assertTrue(props.toString().contains("SlackProperties")) {
                "data class toString()은 클래스명을 포함해야 한다. actual: ${props.toString()}"
            }
        }
    }

    @Nested
    inner class `enum 완전성` {

        @Test
        fun `SlackTransportMode는 EVENTS_API와 SOCKET_MODE 두 값을 가져야 한다`() {
            val values = SlackTransportMode.entries

            assertEquals(2, values.size) {
                "SlackTransportMode는 정확히 2개의 값을 가져야 한다. actual: $values"
            }
            assertTrue(SlackTransportMode.EVENTS_API in values) {
                "EVENTS_API가 SlackTransportMode에 존재해야 한다."
            }
            assertTrue(SlackTransportMode.SOCKET_MODE in values) {
                "SOCKET_MODE가 SlackTransportMode에 존재해야 한다."
            }
        }

        @Test
        fun `SlackSocketBackend는 JAVA_WEBSOCKET과 TYRUS 두 값을 가져야 한다`() {
            val values = SlackSocketBackend.entries

            assertEquals(2, values.size) {
                "SlackSocketBackend는 정확히 2개의 값을 가져야 한다. actual: $values"
            }
            assertTrue(SlackSocketBackend.JAVA_WEBSOCKET in values) {
                "JAVA_WEBSOCKET이 SlackSocketBackend에 존재해야 한다."
            }
            assertTrue(SlackSocketBackend.TYRUS in values) {
                "TYRUS가 SlackSocketBackend에 존재해야 한다."
            }
        }

        @Test
        fun `SlackTransportMode valueOf는 대소문자 일치 문자열로 동작한다`() {
            assertEquals(SlackTransportMode.EVENTS_API, SlackTransportMode.valueOf("EVENTS_API")) {
                "EVENTS_API 문자열로 valueOf가 정확한 enum을 반환해야 한다."
            }
            assertEquals(SlackTransportMode.SOCKET_MODE, SlackTransportMode.valueOf("SOCKET_MODE")) {
                "SOCKET_MODE 문자열로 valueOf가 정확한 enum을 반환해야 한다."
            }
        }

        @Test
        fun `SlackSocketBackend valueOf는 대소문자 일치 문자열로 동작한다`() {
            assertEquals(SlackSocketBackend.JAVA_WEBSOCKET, SlackSocketBackend.valueOf("JAVA_WEBSOCKET")) {
                "JAVA_WEBSOCKET 문자열로 valueOf가 정확한 enum을 반환해야 한다."
            }
            assertEquals(SlackSocketBackend.TYRUS, SlackSocketBackend.valueOf("TYRUS")) {
                "TYRUS 문자열로 valueOf가 정확한 enum을 반환해야 한다."
            }
        }
    }
}

package com.arc.reactor.agent.metrics

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [redactQuerySignal] 및 관련 모델 단위 테스트.
 *
 * 대상: PII 익명화, 쿼리 정규화, 클러스터 ID 일관성,
 *       Question/Prompt 레이블 분류, 빈 쿼리 처리.
 */
class ResponseValueInsightsTest {

    @Nested
    inner class redactQuerySignal_기본_동작 {

        @Test
        fun `비어있지 않은 쿼리는 RedactedQuerySignal을 반환한다`() {
            val signal = redactQuerySignal("오늘 날씨 알려줘")

            assertNotNull(signal, "비어있지 않은 쿼리는 신호를 반환해야 한다")
        }

        @Test
        fun `빈 쿼리는 null을 반환한다`() {
            val signal = redactQuerySignal("")

            assertNull(signal, "빈 쿼리는 null을 반환해야 한다")
        }

        @Test
        fun `공백만 있는 쿼리는 null을 반환한다`() {
            val signal = redactQuerySignal("   ")

            assertNull(signal, "공백만 있는 쿼리는 null을 반환해야 한다")
        }

        @Test
        fun `탭과 줄바꿈만 있는 쿼리는 null을 반환한다`() {
            val signal = redactQuerySignal("\t\n\r")

            assertNull(signal, "화이트스페이스만 있는 쿼리는 null을 반환해야 한다")
        }
    }

    @Nested
    inner class 클러스터_ID_생성 {

        @Test
        fun `clusterId는 12자리 16진수 문자열이다`() {
            val signal = redactQuerySignal("테스트 쿼리")!!

            signal.clusterId shouldHaveLength 12
            signal.clusterId.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
        }

        @Test
        fun `동일한 쿼리는 동일한 clusterId를 생성한다`() {
            val query = "오늘 날씨 어때?"
            val signal1 = redactQuerySignal(query)!!
            val signal2 = redactQuerySignal(query)!!

            signal1.clusterId shouldBe signal2.clusterId
        }

        @Test
        fun `다른 쿼리는 다른 clusterId를 생성한다`() {
            val signal1 = redactQuerySignal("쿼리 A")!!
            val signal2 = redactQuerySignal("쿼리 B")!!

            (signal1.clusterId != signal2.clusterId) shouldBe true
        }

        @Test
        fun `대소문자가 다른 동일 쿼리는 같은 clusterId를 생성한다`() {
            val signal1 = redactQuerySignal("Hello World")!!
            val signal2 = redactQuerySignal("HELLO WORLD")!!

            signal1.clusterId shouldBe signal2.clusterId
        }

        @Test
        fun `앞뒤 공백이 있는 쿼리는 트림 후 동일한 clusterId를 생성한다`() {
            val signal1 = redactQuerySignal("안녕하세요")!!
            val signal2 = redactQuerySignal("  안녕하세요  ")!!

            signal1.clusterId shouldBe signal2.clusterId
        }

        @Test
        fun `내부 연속 공백은 단일 공백으로 정규화된다`() {
            val signal1 = redactQuerySignal("hello world")!!
            val signal2 = redactQuerySignal("hello   world")!!

            signal1.clusterId shouldBe signal2.clusterId
        }
    }

    @Nested
    inner class Question_vs_Prompt_레이블 {

        @Test
        fun `물음표로 끝나는 쿼리는 Question 레이블을 가진다`() {
            val signal = redactQuerySignal("오늘 날씨 어때?")!!

            signal.label shouldContain "Question"
        }

        @Test
        fun `물음표로 끝나지 않는 쿼리는 Prompt 레이블을 가진다`() {
            val signal = redactQuerySignal("이메일 초안 작성해줘")!!

            signal.label shouldContain "Prompt"
        }

        @Test
        fun `영어 물음표로 끝나는 쿼리는 Question이다`() {
            val signal = redactQuerySignal("What is the weather today?")!!

            signal.label shouldContain "Question"
        }

        @Test
        fun `레이블에 clusterId가 포함된다`() {
            val signal = redactQuerySignal("테스트")!!

            signal.label shouldContain signal.clusterId
        }

        @Test
        fun `레이블 형식은 '종류 cluster 아이디'이다`() {
            val signal = redactQuerySignal("테스트 요청")!!

            signal.label shouldBe "Prompt cluster ${signal.clusterId}"
        }

        @Test
        fun `Question 레이블 형식은 'Question cluster 아이디'이다`() {
            val signal = redactQuerySignal("이게 맞나요?")!!

            signal.label shouldBe "Question cluster ${signal.clusterId}"
        }
    }

    @Nested
    inner class PII_보호 {

        @Test
        fun `원본 쿼리 내용이 clusterId에 포함되지 않는다`() {
            val privateData = "김철수 주민번호 901010-1234567"
            val signal = redactQuerySignal(privateData)!!

            signal.clusterId.contains("김철수") shouldBe false
            signal.clusterId.contains("901010") shouldBe false
        }

        @Test
        fun `원본 쿼리 내용이 label에 포함되지 않는다`() {
            val privateData = "비밀번호는 password123 입니다"
            val signal = redactQuerySignal(privateData)!!

            signal.label.contains("비밀번호") shouldBe false
            signal.label.contains("password123") shouldBe false
        }
    }

    @Nested
    inner class ResponseValueSummary_모델 {

        @Test
        fun `기본값으로 생성된 ResponseValueSummary는 모든 카운트가 0이다`() {
            val summary = ResponseValueSummary()

            summary.observedResponses shouldBe 0L
            summary.groundedResponses shouldBe 0L
            summary.blockedResponses shouldBe 0L
            summary.interactiveResponses shouldBe 0L
            summary.scheduledResponses shouldBe 0L
        }

        @Test
        fun `지정된 값으로 ResponseValueSummary를 생성할 수 있다`() {
            val summary = ResponseValueSummary(
                observedResponses = 100L,
                groundedResponses = 80L,
                blockedResponses = 5L
            )

            summary.observedResponses shouldBe 100L
            summary.groundedResponses shouldBe 80L
            summary.blockedResponses shouldBe 5L
        }

        @Test
        fun `answerModeCounts와 channelCounts 기본값은 빈 맵이다`() {
            val summary = ResponseValueSummary()

            summary.answerModeCounts shouldBe emptyMap()
            summary.channelCounts shouldBe emptyMap()
        }
    }

    @Nested
    inner class MissingQueryInsight_모델 {

        @Test
        fun `MissingQueryInsight를 올바르게 생성한다`() {
            val now = java.time.Instant.now()
            val insight = MissingQueryInsight(
                queryCluster = "abc123",
                queryLabel = "Question cluster abc123",
                count = 5L,
                lastOccurredAt = now,
                blockReason = "INJECTION_DETECTED"
            )

            insight.queryCluster shouldBe "abc123"
            insight.count shouldBe 5L
            insight.blockReason shouldBe "INJECTION_DETECTED"
        }

        @Test
        fun `blockReason 기본값은 null이다`() {
            val insight = MissingQueryInsight(
                queryCluster = "def456",
                queryLabel = "Prompt cluster def456",
                count = 1L,
                lastOccurredAt = java.time.Instant.now()
            )

            assertNull(insight.blockReason, "blockReason 기본값은 null이어야 한다")
        }
    }
}

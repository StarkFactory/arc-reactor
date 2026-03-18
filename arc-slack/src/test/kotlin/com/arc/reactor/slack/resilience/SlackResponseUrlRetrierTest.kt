package com.arc.reactor.slack.resilience

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException

/**
 * [SlackResponseUrlRetrier]의 재시도 로직 테스트.
 *
 * 5xx/네트워크 에러 재시도, 4xx 즉시 전파, 지수 백오프 지연을 검증한다.
 */
class SlackResponseUrlRetrierTest {

    private val retrier = SlackResponseUrlRetrier(
        maxRetries = 3,
        initialDelayMs = 10,
        maxDelayMs = 100
    )

    @Nested
    inner class SuccessScenarios {

        @Test
        fun `첫 시도 성공 시 즉시 반환한다`() = runTest {
            val result = retrier.withRetry { "ok" }
            result shouldBe "ok"
        }

        @Test
        fun `5xx 실패 후 재시도에서 성공한다`() = runTest {
            var attempt = 0
            val result = retrier.withRetry {
                attempt++
                if (attempt == 1) throw create5xxException()
                "recovered"
            }
            result shouldBe "recovered"
            attempt shouldBe 2
        }

        @Test
        fun `IOException 실패 후 재시도에서 성공한다`() = runTest {
            var attempt = 0
            val result = retrier.withRetry {
                attempt++
                if (attempt == 1) throw IOException("connection reset")
                "recovered"
            }
            result shouldBe "recovered"
            attempt shouldBe 2
        }
    }

    @Nested
    inner class FailureScenarios {

        @Test
        fun `4xx 에러는 재시도하지 않고 즉시 전파한다`() = runTest {
            var attempt = 0
            shouldThrow<WebClientResponseException> {
                retrier.withRetry {
                    attempt++
                    throw create4xxException()
                }
            }
            attempt shouldBe 1
        }

        @Test
        fun `maxRetries 초과 시 마지막 예외를 전파한다`() = runTest {
            var attempt = 0
            shouldThrow<WebClientResponseException> {
                retrier.withRetry {
                    attempt++
                    throw create5xxException()
                }
            }
            attempt shouldBe 4 // 1 initial + 3 retries
        }

        @Test
        fun `일반 예외는 재시도하지 않고 즉시 전파한다`() = runTest {
            var attempt = 0
            shouldThrow<IllegalStateException> {
                retrier.withRetry {
                    attempt++
                    throw IllegalStateException("unexpected")
                }
            }
            attempt shouldBe 1
        }
    }

    @Nested
    inner class BackoffBehavior {

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `연속 5xx 실패 시 지수 백오프가 적용되어야 한다`() = runTest {
            val retrier = SlackResponseUrlRetrier(
                maxRetries = 3,
                initialDelayMs = 100,
                maxDelayMs = 10000
            )

            val delays = collectRetryDelays(retrier)

            delays.size shouldBe 3
            delays[0] shouldBe 100L
            delays[1] shouldBe 200L
            delays[2] shouldBe 400L
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        @Test
        fun `maxDelayMs 캡이 적용되어야 한다`() = runTest {
            val retrier = SlackResponseUrlRetrier(
                maxRetries = 5,
                initialDelayMs = 500,
                maxDelayMs = 1000
            )

            val delays = collectRetryDelays(retrier)

            delays.forEach { delay ->
                delay shouldBeLessThanOrEqual 1000L
            }
        }

        @Test
        fun `CancellationException은 재시도하지 않고 전파되어야 한다`() = runTest {
            var attempt = 0

            shouldThrow<CancellationException> {
                retrier.withRetry {
                    attempt++
                    throw CancellationException("job cancelled")
                }
            }

            attempt shouldBe 1
        }
    }

    @Nested
    inner class RetryCountConfiguration {

        @Test
        fun `maxRetries가 다른 인스턴스는 해당 횟수만큼 재시도한다`() = runTest {
            val retrier = SlackResponseUrlRetrier(
                maxRetries = 5,
                initialDelayMs = 50,
                maxDelayMs = 80
            )
            var attempt = 0

            shouldThrow<IOException> {
                retrier.withRetry {
                    attempt++
                    throw IOException("timeout")
                }
            }

            attempt shouldBe 6 // 1 initial + 5 retries
        }
    }

    /** 모든 재시도가 실패하도록 5xx를 던지며, 각 재시도 간 지연 시간을 수집한다. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun TestScope.collectRetryDelays(
        retrier: SlackResponseUrlRetrier
    ): List<Long> {
        val delays = mutableListOf<Long>()
        var lastTimestamp = testScheduler.currentTime

        shouldThrow<WebClientResponseException> {
            retrier.withRetry {
                val now = testScheduler.currentTime
                if (lastTimestamp != now) {
                    delays.add(now - lastTimestamp)
                    lastTimestamp = now
                }
                throw create5xxException()
            }
        }

        return delays
    }

    private fun create5xxException(): WebClientResponseException =
        WebClientResponseException.create(
            502, "Bad Gateway", HttpHeaders.EMPTY, ByteArray(0), null
        )

    private fun create4xxException(): WebClientResponseException =
        WebClientResponseException.create(
            400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null
        )
}

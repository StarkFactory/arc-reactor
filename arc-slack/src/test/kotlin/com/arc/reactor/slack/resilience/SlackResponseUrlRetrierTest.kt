package com.arc.reactor.slack.resilience

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

    private fun create5xxException(): WebClientResponseException =
        WebClientResponseException.create(
            502, "Bad Gateway", HttpHeaders.EMPTY, ByteArray(0), null
        )

    private fun create4xxException(): WebClientResponseException =
        WebClientResponseException.create(
            400, "Bad Request", HttpHeaders.EMPTY, ByteArray(0), null
        )
}

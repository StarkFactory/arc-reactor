package com.arc.reactor.health

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.actuate.health.Status

/**
 * VectorStoreHealthIndicator에 대한 테스트.
 *
 * VectorStore 헬스 체크 로직을 검증합니다.
 */
class VectorStoreHealthIndicatorTest {

    private val vectorStore = mockk<VectorStore>()
    private val indicator = VectorStoreHealthIndicator(vectorStore)

    @Test
    fun `similarity search succeeds일 때 health은(는) UP이다`() {
        every { vectorStore.similaritySearch(any<SearchRequest>()) } returns emptyList()

        val health = indicator.health()

        health.status shouldBe Status.UP
        health.details["latencyMs"].shouldBeInstanceOf<Long>()
    }

    @Test
    fun `similarity search fails일 때 health은(는) DOWN이다`() {
        every {
            vectorStore.similaritySearch(any<SearchRequest>())
        } throws RuntimeException("Connection refused")

        val health = indicator.health()

        health.status shouldBe Status.DOWN
    }

    @Test
    fun `헬스 includes non-negative latency on success`() {
        every { vectorStore.similaritySearch(any<SearchRequest>()) } returns emptyList()

        val health = indicator.health()
        val latency = health.details["latencyMs"] as Long

        latency shouldBeGreaterThanOrEqual 0L
    }

    @Test
    fun `exception details are included in DOWN health`() {
        val errorMessage = "VectorStore backend unavailable"
        every {
            vectorStore.similaritySearch(any<SearchRequest>())
        } throws RuntimeException(errorMessage)

        val health = indicator.health()

        health.status shouldBe Status.DOWN
        health.details["error"]?.toString() shouldBe "java.lang.RuntimeException: $errorMessage"
    }
}

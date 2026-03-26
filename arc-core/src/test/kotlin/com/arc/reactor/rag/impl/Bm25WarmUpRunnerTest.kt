package com.arc.reactor.rag.impl

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.DefaultApplicationArguments

/**
 * Bm25WarmUpRunner에 대한 테스트.
 *
 * 서비스 시작 시 VectorStore → BM25 인덱스 워밍업 로직을 검증한다.
 * toRetrievedDocument()의 메타데이터 파싱 분기 (distance, score, 폴백)도 포함.
 */
class Bm25WarmUpRunnerTest {

    private val pipeline = mockk<HybridRagPipeline>(relaxed = true)
    private val args = DefaultApplicationArguments()

    private fun objectProvider(store: VectorStore?): ObjectProvider<VectorStore> {
        val provider = mockk<ObjectProvider<VectorStore>>()
        every { provider.ifAvailable } returns store
        return provider
    }

    @Nested
    inner class SkipConditions {

        @Test
        fun `VectorStore가 없으면 워밍업을 건너뛴다`() {
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(null))

            runner.run(args)

            verify(exactly = 0) { pipeline.indexDocuments(any()) }
        }

        @Test
        fun `VectorStore가 비어있으면 워밍업을 건너뛴다`() {
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns emptyList()
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 0) { pipeline.indexDocuments(any()) }
        }
    }

    @Nested
    inner class SuccessfulWarmUp {

        @Test
        fun `distance 메타데이터로 스코어를 추출한다`() {
            val doc = Document.builder().id("d1").text("content-1")
                .metadata(mapOf("distance" to 0.85, "source" to "wiki"))
                .build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { docs ->
                    docs.size == 1 &&
                        docs[0].id == "d1" &&
                        docs[0].score == 0.85 &&
                        docs[0].source == "wiki" &&
                        docs[0].content == "content-1"
                })
            }
        }

        @Test
        fun `score 메타데이터로 폴백하여 스코어를 추출한다`() {
            val doc = Document.builder().id("d2").text("content-2")
                .metadata(mapOf("score" to 0.72))
                .build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { docs ->
                    docs.size == 1 && docs[0].score == 0.72
                })
            }
        }

        @Test
        fun `메타데이터에 distance도 score도 없으면 0점으로 폴백한다`() {
            val doc = Document.builder().id("d3").text("content-3")
                .metadata(mapOf("other" to "value"))
                .build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { docs ->
                    docs.size == 1 && docs[0].score == 0.0
                })
            }
        }

        @Test
        fun `distance가 문자열이어도 파싱한다`() {
            val doc = Document.builder().id("d4").text("text")
                .metadata(mapOf("distance" to "0.95"))
                .build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { it[0].score == 0.95 })
            }
        }

        @Test
        fun `여러 문서를 한 번에 인덱싱한다`() {
            val docs = (1..5).map { i ->
                Document.builder().id("d$i").text("content-$i")
                    .metadata(mapOf("score" to i * 0.1))
                    .build()
            }
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns docs
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { it.size == 5 })
            }
        }

        @Test
        fun `source 메타데이터가 없으면 null로 설정한다`() {
            val doc = Document.builder().id("d5").text("").metadata(emptyMap<String, Any>()).build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            runner.run(args)

            verify(exactly = 1) {
                pipeline.indexDocuments(match { it[0].content == "" && it[0].source == null })
            }
        }
    }

    @Nested
    inner class ErrorIsolation {

        @Test
        fun `VectorStore 예외 시 서비스 시작을 차단하지 않는다`() {
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } throws RuntimeException("connection refused")
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            // 예외가 전파되지 않아야 한다
            runner.run(args)

            verify(exactly = 0) { pipeline.indexDocuments(any()) }
        }

        @Test
        fun `indexDocuments 예외 시 서비스 시작을 차단하지 않는다`() {
            val doc = Document.builder().id("d1").text("text").metadata(emptyMap<String, Any>()).build()
            val store = mockk<VectorStore>()
            every { store.similaritySearch(any<SearchRequest>()) } returns listOf(doc)
            every { pipeline.indexDocuments(any()) } throws RuntimeException("BM25 error")
            val runner = Bm25WarmUpRunner(pipeline, objectProvider(store))

            // 예외가 전파되지 않아야 한다
            runner.run(args)
        }
    }
}

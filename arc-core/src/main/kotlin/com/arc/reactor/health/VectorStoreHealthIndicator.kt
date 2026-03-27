package com.arc.reactor.health

import mu.KotlinLogging
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator

private val logger = KotlinLogging.logger {}

/**
 * VectorStore 연결 상태를 확인하는 헬스 인디케이터.
 *
 * 빈 쿼리로 유사도 검색을 시도하여 VectorStore 접근 가능 여부를 판단한다.
 * 예외가 발생하면 DOWN, 정상 응답이면 UP을 보고한다.
 *
 * WHY: VectorStore는 RAG 파이프라인의 핵심 인프라이다.
 * DB 연결 장애나 임베딩 서비스 불가를 조기에 감지하여
 * 운영자가 대응할 수 있도록 `/actuator/health`에 노출한다.
 *
 * @param vectorStore Spring AI VectorStore
 * @see com.arc.reactor.autoconfigure.HealthIndicatorConfiguration 에서 빈 등록
 */
class VectorStoreHealthIndicator(
    private val vectorStore: VectorStore
) : HealthIndicator {

    override fun health(): Health {
        return try {
            val start = System.nanoTime()
            vectorStore.similaritySearch(
                SearchRequest.builder().query("health-check").topK(1).build()
            )
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            logger.debug { "VectorStore 헬스: UP (${latencyMs}ms)" }
            Health.up()
                .withDetail("latencyMs", latencyMs)
                .build()
        } catch (e: Exception) {
            logger.warn(e) { "VectorStore 헬스: DOWN" }
            Health.down(e).build()
        }
    }
}

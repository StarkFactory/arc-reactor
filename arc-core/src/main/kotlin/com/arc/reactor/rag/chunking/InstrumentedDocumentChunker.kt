package com.arc.reactor.rag.chunking

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.ai.document.Document

/**
 * [DocumentChunker]의 메트릭 데코레이터.
 *
 * Micrometer를 통해 청킹 작업을 기록한다:
 * - `arc.rag.documents.chunked` — 실제로 분할된 문서 수
 * - `arc.rag.chunks.created` — 생성된 총 청크 수
 * - `arc.rag.chunk.size.chars` — 청크 크기 분포 (p50, p95)
 *
 * 이 메트릭으로 청킹 파라미터 튜닝과 이상 탐지가 가능하다.
 *
 * @param delegate 실제 청킹을 수행하는 위임 대상
 * @param registry Micrometer 메트릭 레지스트리
 */
class InstrumentedDocumentChunker(
    private val delegate: DocumentChunker,
    registry: MeterRegistry
) : DocumentChunker {

    /** 분할된 문서 수 카운터 */
    private val documentsChunked = registry.counter("arc.rag.documents.chunked")
    /** 생성된 청크 수 카운터 */
    private val chunksCreated = registry.counter("arc.rag.chunks.created")
    /** 청크 크기(문자 수) 분포 — p50과 p95 퍼센타일을 게시 */
    private val chunkSize: DistributionSummary = DistributionSummary.builder("arc.rag.chunk.size.chars")
        .publishPercentiles(0.5, 0.95)
        .register(registry)

    override fun chunk(document: Document): List<Document> {
        val result = delegate.chunk(document)
        // 실제로 분할이 발생한 경우에만 (result.size > 1) 메트릭을 기록
        if (result.size > 1) {
            documentsChunked.increment()
            chunksCreated.increment(result.size.toDouble())
            result.forEach { chunkSize.record(it.text.orEmpty().length.toDouble()) }
        }
        return result
    }
}

package com.arc.reactor.rag.chunking

import com.arc.reactor.memory.DefaultTokenEstimator
import com.arc.reactor.memory.TokenEstimator
import mu.KotlinLogging
import org.springframework.ai.document.Document

private val logger = KotlinLogging.logger {}

/**
 * 재귀적 문자 분할(recursive character splitting)을 사용하는 토큰 기반 문서 청커.
 *
 * 문서를 약 [chunkSize] 토큰 크기의 청크로 분할하되,
 * 인접 청크 간 [overlap] 토큰의 겹침을 유지한다.
 * [minChunkThreshold] 토큰 이하의 문서는 분할하지 않는다.
 *
 * [TokenEstimator]를 사용하여 언어 인식 토큰 카운팅을 수행한다:
 * - 라틴/ASCII: 약 4자/토큰 (영어 텍스트)
 * - CJK (한국어/중국어/일본어): 약 1.5자/토큰
 * - 이모지: 약 1토큰씩
 *
 * ## 왜 512 토큰인가?
 * FloTorch 2026.02 벤치마크 결과에 따르면:
 * - 512 토큰의 재귀적 분할이 69% 정확도로 1위
 * - 최적 범위: 400-512 토큰 + 10-20% 겹침
 * - 너무 작으면 맥락 부족, 너무 크면 임베딩 정밀도 저하
 *
 * @param chunkSize 청크당 목표 토큰 수 (기본값 512)
 * @param minChunkSizeChars 청크의 최소 문자 수 — 이보다 짧은 잔여 청크는 이전 청크에 병합 (기본값 350)
 * @param minChunkThreshold 이 토큰 수 이하의 문서는 분할하지 않는다 (기본값 512)
 * @param overlap 인접 청크 간 겹치는 토큰 수 (기본값 50). 맥락 연속성 보장을 위해 사용.
 * @param keepSeparator 분할 시 구분자(줄바꿈 등)를 현재 청크에 유지할지 여부 (기본값 true)
 * @param maxNumChunks 최대 청크 수 제한 — 무한 분할 방지 (기본값 100)
 * @param tokenEstimator 언어 인식 토큰 추정기
 */
class TokenBasedDocumentChunker(
    private val chunkSize: Int = 512,
    private val minChunkSizeChars: Int = 350,
    private val minChunkThreshold: Int = 512,
    private val overlap: Int = 50,
    private val keepSeparator: Boolean = true,
    private val maxNumChunks: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : DocumentChunker {

    override fun chunk(document: Document): List<Document> {
        val content = document.text.orEmpty()
        if (content.isBlank()) return listOf(document)

        val estimatedTokens = tokenEstimator.estimate(content)
        // 임계값 이하의 짧은 문서는 분할하지 않는다
        if (estimatedTokens <= minChunkThreshold) return listOf(document)

        // 토큰 기반 크기를 문자 기반 크기로 변환 (문서의 문자/토큰 비율을 사용)
        val charsPerToken = content.length.toDouble() / estimatedTokens
        val chunkSizeChars = (chunkSize * charsPerToken).toInt()
        val overlapChars = (overlap * charsPerToken).toInt()
        val chunks = splitRecursive(content, chunkSizeChars, overlapChars)

        if (chunks.size <= 1) return listOf(document)

        val totalChunks = chunks.size
        logger.debug {
            "Chunked document ${document.id}: ${content.length} chars -> $totalChunks chunks"
        }

        // 각 청크에 추적용 메타데이터를 추가하여 Document 객체로 변환
        return chunks.mapIndexed { index, chunkContent ->
            val chunkMetadata = document.metadata.toMutableMap().apply {
                put("parent_document_id", document.id)
                put("chunk_index", index)
                put("chunk_total", totalChunks)
                put("chunked", true)
            }
            Document(DocumentChunker.chunkId(document.id, index), chunkContent, chunkMetadata)
        }
    }

    /**
     * 텍스트를 재귀적으로 분할한다.
     *
     * 자연스러운 경계(문단 > 문장 > 단어)에서 분할을 시도하여
     * 의미 단위가 깨지지 않도록 한다.
     * 겹침(overlap)으로 청크 간 맥락 연속성을 유지한다.
     */
    private fun splitRecursive(
        text: String,
        targetSize: Int,
        overlapSize: Int
    ): List<String> {
        if (text.length <= targetSize) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length && chunks.size < maxNumChunks) {
            var end = minOf(start + targetSize, text.length)

            // 텍스트 중간이면 자연스러운 경계를 찾는다 (문단 > 문장 > 단어)
            if (end < text.length) {
                end = findBreakPoint(text, start, end)
            }

            val chunk = text.substring(start, end).trim()
            if (chunk.length >= minChunkSizeChars || chunks.isEmpty()) {
                chunks.add(chunk)
            } else if (chunks.isNotEmpty()) {
                // 짧은 잔여 청크는 이전 청크에 병합하여 파편화를 방지
                val prev = chunks.removeAt(chunks.lastIndex)
                chunks.add(prev + "\n" + chunk)
            }

            // 겹침을 적용하여 다음 시작점을 결정 — 반드시 전진하도록 보장
            val nextStart = end - overlapSize
            start = if (nextStart <= start) end else nextStart
        }

        return chunks
    }

    /**
     * 자연스러운 분할 지점을 찾는다.
     *
     * 우선순위: 문단 구분(\n\n) > 줄 구분(\n) > 문장 종결(. ! ?) > 단어 구분(공백)
     * 검색 범위: 청크의 후반부(앞쪽 절반에서는 자르지 않음)
     *
     * 왜 후반부에서만 찾는가: 앞쪽에서 자르면 청크가 너무 짧아지고
     * 겹침 계산이 비효율적이 된다.
     */
    private fun findBreakPoint(text: String, start: Int, end: Int): Int {
        val searchFrom = start + (end - start) / 2 // 전반부에서는 자르지 않는다

        // 문단 구분 (\n\n) 시도
        val paragraphBreak = text.lastIndexOf("\n\n", end)
        if (paragraphBreak > searchFrom) {
            return if (keepSeparator) paragraphBreak else paragraphBreak + 2
        }

        // 줄 구분 (\n) 시도
        val lineBreak = text.lastIndexOf('\n', end)
        if (lineBreak > searchFrom) {
            return if (keepSeparator) lineBreak else lineBreak + 1
        }

        // 문장 종결 (. ! ? 。 ！ ？) 시도
        for (i in end downTo searchFrom) {
            if (i < text.length && text[i] in SENTENCE_ENDS && i + 1 < text.length &&
                text[i + 1].isWhitespace()
            ) {
                return i + 1
            }
        }

        // 단어 구분 (공백) 시도
        val spaceBreak = text.lastIndexOf(' ', end)
        if (spaceBreak > searchFrom) return spaceBreak + 1

        // 자연스러운 경계를 찾지 못하면 원래 위치에서 자른다
        return end
    }

    companion object {
        /** 문장 종결 문자 목록 (영어 + 한국어/중국어/일본어) */
        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '。', '！', '？')
    }
}

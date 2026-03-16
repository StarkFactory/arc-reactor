package com.arc.reactor.agent.config

/**
 * RAG 수집을 위한 문서 청킹 설정.
 *
 * 활성화하면 긴 문서를 벡터 저장 전에 작은 청크로 분할하여
 * 임베딩 품질과 검색 정확도를 향상시킨다.
 *
 * ## 설정 예시
 * ```yaml
 * arc:
 *   reactor:
 *     rag:
 *       chunking:
 *         enabled: true
 *         chunk-size: 512
 *         min-chunk-threshold: 512
 *         overlap: 50
 * ```
 *
 * @see com.arc.reactor.rag.RagPipeline RAG 파이프라인에서 청킹 적용
 */
data class RagChunkingProperties(
    /** 문서 청킹 활성화. 업그레이드 시 예상치 못한 동작 변화를 방지하기 위해 opt-in. */
    val enabled: Boolean = false,

    /** 목표 청크 크기 (토큰 단위, 약 4자 = 1토큰). */
    val chunkSize: Int = 512,

    /** 과도하게 작은 청크를 방지하기 위한 최소 청크 크기 (문자 수). */
    val minChunkSizeChars: Int = 350,

    /** 추정 토큰이 이 임계값 이하인 문서는 분할하지 않는다. */
    val minChunkThreshold: Int = 512,

    /** 컨텍스트 보존을 위한 인접 청크 간 겹침 토큰 수. */
    val overlap: Int = 50,

    /** 분할 시 문단/문장 구분자 보존 여부. */
    val keepSeparator: Boolean = true,

    /** 문서당 최대 청크 수. */
    val maxNumChunks: Int = 100
)

package com.arc.reactor.agent.config

/**
 * RAG(검색 증강 생성) 설정.
 *
 * @see com.arc.reactor.agent.impl.RagContextRetriever RAG 컨텍스트 검색
 * @see com.arc.reactor.rag.RagPipeline RAG 파이프라인 (벡터 검색 + rerank)
 */
data class RagProperties(
    /** RAG 활성화 여부 */
    val enabled: Boolean = false,

    /** 검색 유사도 임계값 */
    val similarityThreshold: Double = 0.65,

    /** 검색 결과 수 */
    val topK: Int = 5,

    /** 재순위 활성화 여부 */
    val rerankEnabled: Boolean = false,

    /** 쿼리 변환 모드: passthrough|hyde|decomposition */
    val queryTransformer: String = "passthrough",

    /** RAG 수집 정책 (Q&A -> 후보 큐 -> 검토된 벡터 수집) */
    val ingestion: RagIngestionProperties = RagIngestionProperties(),

    /** 최대 컨텍스트 토큰 수 */
    val maxContextTokens: Int = 4000,

    /** 하이브리드 검색 설정 (BM25 + 벡터) */
    val hybrid: RagHybridProperties = RagHybridProperties(),

    /** 문서 청킹 설정 */
    val chunking: RagChunkingProperties = RagChunkingProperties(),

    /** 부모 문서 검색 설정 */
    val parentRetrieval: RagParentRetrievalProperties = RagParentRetrievalProperties(),

    /** 검색 타임아웃 (밀리초). 벡터 DB 무응답 시 스레드 풀 고갈을 방지한다. */
    val retrievalTimeoutMs: Long = 3000,

    /** 컨텍스트 압축 설정 */
    val compression: RagCompressionProperties = RagCompressionProperties(),

    /** 적응형 쿼리 라우팅 설정 (Adaptive-RAG) */
    val adaptiveRouting: AdaptiveRoutingProperties = AdaptiveRoutingProperties(),

    /**
     * R327: 필수 RAG 필터 키 목록 (cross-tenant leak 차단).
     *
     * 여기에 나열된 키는 `AgentCommand.metadata`에서 자동으로 추출되어 RAG 검색 필터에 강제 주입된다.
     * 호출자가 제공한 `ragFilters`는 이 키를 **덮어쓸 수 없다** (spoofing 차단).
     *
     * 예: `["tenantId", "userId"]` — 모든 RAG 검색이 tenant/user 스코프로 자동 격리됨.
     *
     * 기본값 빈 리스트: backward compat, 아무 키도 강제하지 않는다. **production 권장**: 최소한
     * `tenantId` 또는 `userId` 하나는 설정하여 cross-tenant 벡터 검색 누출을 차단한다.
     *
     * **fail-closed 동작**: 설정된 키가 metadata에 없으면 RAG 검색은 null을 반환하고 warn 로그를
     * 남긴다. 에이전트는 RAG 없이 계속 실행되므로 가용성은 유지된다.
     */
    val mandatoryFilterKeys: List<String> = emptyList()
)

/**
 * 컨텍스트 압축 설정.
 *
 * RECOMP (Xu et al., 2024, arXiv:2310.04408) 기반.
 */
data class RagCompressionProperties(
    /** 컨텍스트 압축 활성화. 기본 비활성. */
    val enabled: Boolean = false,

    /** 이 길이(문자 수) 미만의 문서는 압축을 건너뛴다. */
    val minContentLength: Int = 200
)

/**
 * 적응형 쿼리 라우팅 설정.
 *
 * @see <a href="https://arxiv.org/abs/2403.14403">Adaptive-RAG (Jeong et al., 2024)</a>
 */
data class AdaptiveRoutingProperties(
    /** 적응형 쿼리 라우팅 활성화. 간단한 쿼리에서 RAG를 건너뛰기 위해 기본 활성. */
    val enabled: Boolean = true,

    /** 분류 타임아웃 (밀리초). */
    val timeoutMs: Long = 3000,

    /** COMPLEX 쿼리에 대한 topK 재정의. */
    val complexTopK: Int = 15
)

/**
 * 하이브리드 검색 (BM25 + 벡터) 설정.
 *
 * 활성화하면 BM25 키워드 점수가 벡터 유사도 점수와
 * Reciprocal Rank Fusion(RRF)을 통해 융합되어 고유 명사 검색이 개선된다.
 */
data class RagHybridProperties(
    /** 하이브리드 BM25 + 벡터 검색 활성화. arc.reactor.rag.enabled=true 필요. */
    val enabled: Boolean = false,

    /** BM25 순위에 대한 RRF 가중치 (0.0-1.0) */
    val bm25Weight: Double = 0.5,

    /** 벡터 검색 순위에 대한 RRF 가중치 (0.0-1.0) */
    val vectorWeight: Double = 0.5,

    /** RRF 스무딩 상수 K — 값이 클수록 순위 위치 민감도가 감소한다 */
    val rrfK: Double = 60.0,

    /** BM25 용어 빈도 포화 파라미터 */
    val bm25K1: Double = 1.5,

    /** BM25 길이 정규화 파라미터 */
    val bm25B: Double = 0.75
)

/**
 * 부모 문서 검색 설정.
 */
data class RagParentRetrievalProperties(
    /** 부모 문서 검색 활성화. arc.reactor.rag.enabled=true 필요. */
    val enabled: Boolean = false,

    /** 각 히트 전후에 포함할 인접 청크 수 (1 = 앞뒤 1개씩). */
    val windowSize: Int = 1
)

/**
 * RAG 수집 설정.
 */
data class RagIngestionProperties(
    /** 수집 후보 캡처 마스터 스위치. */
    val enabled: Boolean = false,

    /** 런타임 DB 기반 정책 관리 스위치. */
    val dynamic: RagIngestionDynamicProperties = RagIngestionDynamicProperties(),

    /** 벡터 수집 전 관리자 검토 필요 여부. */
    val requireReview: Boolean = true,

    /** 자동 캡처가 허용되는 채널. 빈 집합 = 모든 채널에서 캡처. */
    val allowedChannels: Set<String> = emptySet(),

    /** 지식 가치가 있다고 판단할 최소 쿼리 길이. */
    val minQueryChars: Int = 10,

    /** 지식 가치가 있다고 판단할 최소 응답 길이. */
    val minResponseChars: Int = 20,

    /** 쿼리 또는 응답에 매칭 시 캡처를 차단하는 정규식 패턴. */
    val blockedPatterns: Set<String> = emptySet()
)

/**
 * RAG 수집 동적 정책 설정.
 */
data class RagIngestionDynamicProperties(
    /** 관리자 API를 통한 DB 정책 재정의 활성화. */
    val enabled: Boolean = false,

    /** 동적 정책의 프로바이더 캐시 갱신 간격 (밀리초). */
    val refreshMs: Long = 10_000
)

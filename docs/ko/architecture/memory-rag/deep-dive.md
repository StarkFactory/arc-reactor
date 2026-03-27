# 메모리 시스템 & RAG 파이프라인

> 이 문서는 Arc Reactor의 대화 기록 관리(Memory)와 외부 지식 검색(RAG)의 내부 동작을 설명합니다.

## 메모리 시스템

### 3단계 계층 구조

```
┌─────────────────────────────────────────────┐
│              ConversationManager             │  대화 생명주기 관리
│  loadHistory() / saveHistory()              │  Executor가 직접 사용
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│                MemoryStore                   │  세션별 메모리 관리
│  getOrCreate(sessionId) / addMessage()      │  멀티테넌트 구조
│  ├── InMemoryMemoryStore (Caffeine LRU)     │
│  └── JdbcMemoryStore (PostgreSQL)           │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│             ConversationMemory               │  메시지 리스트 관리
│  add() / getHistory() / clear()             │
│  getHistoryWithinTokenLimit(maxTokens)      │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│              TokenEstimator                  │  토큰 수 추정
│  estimate(text) → Int                       │  CJK 문자 인식
└─────────────────────────────────────────────┘
```

### TokenEstimator

```kotlin
fun interface TokenEstimator {
    fun estimate(text: String): Int
}
```

`DefaultTokenEstimator`는 문자 타입별로 다른 비율을 적용합니다:

| 문자 타입 | 비율 | 예시 |
|-----------|------|------|
| Latin (영문, 숫자) | ~4자/토큰 | "hello" = 2 토큰 |
| CJK (한글, 한자, 일어) | ~1.5자/토큰 | "안녕하세요" = 4 토큰 |
| 이모지 | ~1자/토큰 | "🎉" = 1 토큰 |
| 기타 | ~3자/토큰 | 특수문자 등 |

**CJK 인식이 중요한 이유:** BPE 토크나이저에서 한글/중국어/일본어는 문자당 더 많은 토큰을 소비합니다. Latin 기준(4자/토큰)으로만 계산하면 컨텍스트 윈도우를 초과할 수 있습니다.

**Unicode 범위:**

```
CJK 통합 이데오그래프: 0x4E00..0x9FFF (한자)
한글 음절:            0xAC00..0xD7AF
히라가나:             0x3040..0x309F
카타카나:             0x30A0..0x30FF
이모지:               0x1F300..0x1FAFF, 0x2600..0x27BF
```

### ConversationMemory

```kotlin
interface ConversationMemory {
    fun add(message: Message)
    fun getHistory(): List<Message>
    fun clear()
    fun getHistoryWithinTokenLimit(maxTokens: Int): List<Message>
}
```

#### InMemoryConversationMemory

```kotlin
class InMemoryConversationMemory(
    private val maxMessages: Int = 50,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : ConversationMemory
```

- **Thread-safe:** `ReentrantReadWriteLock` 사용 (읽기 동시성 허용)
- **FIFO 제거:** `maxMessages` 초과 시 가장 오래된 메시지부터 삭제
- **토큰 기반 트리밍:** `getHistoryWithinTokenLimit()`는 역순으로 순회하여 최신 메시지부터 포함하고, 토큰 예산 초과 시 중단

### MemoryStore

```kotlin
interface MemoryStore {
    fun get(sessionId: String): ConversationMemory?
    fun getOrCreate(sessionId: String): ConversationMemory
    fun remove(sessionId: String)
    fun clear()
    fun addMessage(sessionId: String, role: String, content: String)
    fun addMessage(sessionId: String, role: String, content: String, userId: String)  // 세션 소유권 추적
    fun listSessions(): List<SessionSummary>
    fun listSessionsByUserId(userId: String): List<SessionSummary>
    fun listSessionsByUserIdPaginated(userId: String, limit: Int, offset: Int): PaginatedSessionResult
    fun getSessionOwner(sessionId: String): String?
}
```

#### InMemoryMemoryStore

```kotlin
class InMemoryMemoryStore(
    private val maxSessions: Int = 1000
) : MemoryStore {
    private val sessionOwners = ConcurrentHashMap<String, String>()  // 세션-사용자 소유권 매핑

    private val sessions = Caffeine.newBuilder()
        .maximumSize(maxSessions.toLong())
        .evictionListener<String, ConversationMemory> { key, _, _ ->
            if (key != null) sessionOwners.remove(key)  // 퇴거 시 소유권 동기 정리
        }
        .build<String, ConversationMemory>()
}
```

- **Caffeine 캐시:** LRU 제거 정책
- **세션 소유권:** `sessionOwners` (`ConcurrentHashMap<String, String>`)로 각 세션의 소유자 사용자를 추적한다. 소유권은 첫 `addMessage(sessionId, role, content, userId)` 호출 시 `putIfAbsent`로 기록된다
- **동기적 퇴거 정리:** `removalListener`가 아닌 `evictionListener`를 사용하여 세션이 캐시에서 퇴거될 때 소유권 항목을 동기적으로 제거한다
- 최대 세션 수 도달 시 가장 오래 사용하지 않은 세션 자동 제거
- 서버 재시작 시 모든 데이터 손실

#### JdbcMemoryStore

```kotlin
class JdbcMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val maxMessagesPerSession: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : MemoryStore
```

**테이블 구조:**

```sql
CREATE TABLE conversation_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**주요 기능:**

1. **메시지 로드:** `SELECT ... WHERE session_id = ? ORDER BY id ASC`
2. **FIFO 제거:** 세션별 메시지 수를 `maxMessagesPerSession` 이하로 유지
3. **TTL 정리:** `cleanupExpiredSessions(ttlMs)` — 마지막 메시지로부터 TTL 경과 시 세션 삭제

**자동 감지:**

```kotlin
// ArcReactorAutoConfiguration
@ConditionalOnClass(JdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
fun jdbcMemoryStore(jdbcTemplate: JdbcTemplate): MemoryStore = JdbcMemoryStore(jdbcTemplate)

// DataSource 없으면 InMemory
@ConditionalOnMissingBean(MemoryStore::class)
fun inMemoryMemoryStore(): MemoryStore = InMemoryMemoryStore()
```

### ConversationManager

```kotlin
interface ConversationManager {
    suspend fun loadHistory(command: AgentCommand): List<Message>
    suspend fun saveHistory(command: AgentCommand, result: AgentResult)
    suspend fun saveStreamingHistory(command: AgentCommand, content: String)
}
```

Executor와 MemoryStore 사이의 중간 계층으로, 대화 히스토리의 로드/저장 로직을 캡슐화합니다.

#### DefaultConversationManager

**세션 소유권 검증:**

`loadHistory`는 메시지를 로드하기 전에 `verifySessionOwnership(sessionId, command.userId)`를 호출한다. 세션에 이미 소유자가 있고 요청자의 `userId`가 일치하지 않으면 `SessionOwnershipException`이 발생하며 빈 리스트를 반환한다. 소유권 DB 조회 자체가 실패하면 `SessionOwnershipVerificationException`이 발생하며 마찬가지로 빈 리스트를 반환한다 (fail-close). 이를 통해 다른 사용자의 대화 이력에 대한 무단 접근을 차단한다.

**히스토리 로드:**

```kotlin
override suspend fun loadHistory(command: AgentCommand): List<Message> {
    // 1. AgentCommand에 직접 전달된 히스토리 우선
    if (command.conversationHistory.isNotEmpty()) {
        return command.conversationHistory.map { toSpringAiMessage(it) }
    }

    // 2. sessionId로 MemoryStore에서 조회
    val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
    val memory = memoryStore.getOrCreate(sessionId)

    // 3. 최근 N턴만 반환 (maxConversationTurns * 2: User + Assistant = 1턴)
    return memory.getHistory()
        .takeLast(properties.llm.maxConversationTurns * 2)
        .map { toSpringAiMessage(it) }
}
```

**히스토리 저장:**

```kotlin
override suspend fun saveHistory(command: AgentCommand, result: AgentResult) {
    if (!result.success) return  // 실패한 실행은 저장하지 않음

    val sessionId = command.metadata["sessionId"]?.toString() ?: return
    try {
        memoryStore.addMessage(sessionId, "USER", command.userPrompt)
        memoryStore.addMessage(sessionId, "ASSISTANT", result.content ?: "")
    } catch (e: Exception) {
        logger.error(e) { "Failed to save conversation history" }
        // 저장 실패는 전체 실행을 중단시키지 않음 (fail-safe)
    }
}
```

**스트리밍 히스토리 저장:**

```kotlin
override suspend fun saveStreamingHistory(command: AgentCommand, content: String) {
    // lastIterationContent만 저장 (전체 누적이 아닌 마지막 반복)
    val sessionId = command.metadata["sessionId"]?.toString() ?: return
    memoryStore.addMessage(sessionId, "USER", command.userPrompt)
    memoryStore.addMessage(sessionId, "ASSISTANT", content)
}
```

### Executor 통합

```
executeInternal()
    │
    ├─ 3단계: val conversationHistory = conversationManager.loadHistory(command)
    │         → Spring AI Message 리스트로 변환되어 LLM 호출에 포함
    │
    ├─ 7단계: conversationManager.saveHistory(command, result)
    │         → 성공 시에만 저장
    │
    └─ 스트리밍: finally 블록에서 saveStreamingHistory()
                → withTimeout 밖에서 실행 (스트림 중단 후에도 저장 보장)
```

---

## RAG 파이프라인

### 4단계 구조

```
사용자 쿼리
    │
    ▼
┌─────────────────────┐
│ 1. QueryTransformer  │  쿼리 변환/확장 (선택적)
│    "검색 최적화"      │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 2. DocumentRetriever │  벡터 검색
│    "문서 가져오기"    │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 3. DocumentReranker  │  재정렬 (선택적)
│    "관련성 재평가"    │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 4. ContextBuilder    │  토큰 인식 컨텍스트 생성
│    "프롬프트에 주입"  │
└─────────────────────┘
```

### Stage 1: QueryTransformer

```kotlin
interface QueryTransformer {
    suspend fun transform(query: String): List<String>
}
```

단일 쿼리를 다중 쿼리로 확장하여 검색 품질을 높입니다.

**기본 구현:** `PassthroughQueryTransformer` — 변환 없이 원본 쿼리 그대로 전달

#### HyDEQueryTransformer

**HyDE (Hypothetical Document Embeddings)** — LLM으로 가상 답변 문서를 생성하고, 원본 쿼리와 함께 검색에 사용합니다.

```
사용자 쿼리: "환불 정책이 뭔가요?"

→ LLM이 가상 답변 생성:
  "환불 정책은 구매일로부터 30일 이내 미개봉 상품에 한해 전액 환불이 가능합니다."

→ 검색 쿼리 2개:
  1. "환불 정책이 뭔가요?"           ← 원본
  2. "환불 정책은 구매일로부터..."    ← 가상 답변 (문서와 임베딩 공간에서 더 가까움)
```

**왜 효과적인가:** 질문("정책이 뭔가요?")과 답변("정책은...입니다")은 어휘가 다르지만 의미가 유사하다. 가상 답변을 생성하면 실제 문서와의 벡터 거리가 줄어들어 검색 정확도가 올라간다.

```kotlin
val transformer = HyDEQueryTransformer(chatClient)
val queries = transformer.transform("환불 정책이 뭔가요?")
// → ["환불 정책이 뭔가요?", "환불 정책은 구매일로부터 30일 이내..."]
```

에러 발생 시 원본 쿼리만 반환 (graceful fallback).

#### ConversationAwareQueryTransformer

**대화 문맥 인식 쿼리 변환** — 대화 이력을 참조하여 대명사/암묵적 참조를 해소한 독립적 검색 쿼리로 변환합니다.

```
대화 이력:
  User: "환불 정책에 대해 알려줘"
  AI: "30일 이내 반품 가능합니다."
  User: "전자제품은?"              ← 맥락 없이는 의미 불명확

→ LLM이 재작성: "전자제품의 환불 정책은 무엇인가요?"
→ 이 독립적 쿼리로 검색 → 정확한 문서 검색
```

```kotlin
val transformer = ConversationAwareQueryTransformer(chatClient, maxHistoryTurns = 5)
transformer.updateHistory(listOf("User: 환불 정책 알려줘", "AI: 30일 이내..."))
val queries = transformer.transform("전자제품은?")
// → ["전자제품의 환불 정책은 무엇인가요?"]
```

- 대화 이력이 없으면 LLM 호출 없이 원본 쿼리 반환
- `maxHistoryTurns`으로 LLM에 전달할 이력 수 제한 (기본 5)
- 에러 시 원본 쿼리로 폴백

### Stage 2: DocumentRetriever

```kotlin
interface DocumentRetriever {
    suspend fun retrieve(
        queries: List<String>,
        topK: Int = 10,
        filters: Map<String, Any> = emptyMap()
    ): List<RetrievedDocument>
}
```

#### SpringAiVectorStoreRetriever

```kotlin
class SpringAiVectorStoreRetriever(
    private val vectorStore: VectorStore,
    private val defaultSimilarityThreshold: Double = 0.7
) : DocumentRetriever
```

Spring AI의 `VectorStore`를 사용한 벡터 유사도 검색:

```kotlin
override suspend fun retrieve(
    queries: List<String>, topK: Int, filters: Map<String, Any>
): List<RetrievedDocument> {
    val allDocuments = queries.flatMap { query ->
        searchWithQuery(query, topK, filters)
    }
    return allDocuments
        .sortedByDescending { it.score }
        .distinctBy { it.id }   // 다중 쿼리에서 중복 제거
        .take(topK)
}
```

#### 메타데이터 필터링

`RagQuery.filters`를 통해 메타데이터 기반 문서 필터링을 지원합니다. 여러 필터는 AND 논리로 결합됩니다.

```kotlin
// source=docs AND category=language 인 문서만 검색
val result = pipeline.retrieve(RagQuery(
    query = "kotlin guide",
    topK = 10,
    filters = mapOf("source" to "docs", "category" to "language")
))
```

**Spring AI FilterExpression 변환:**

```kotlin
private fun buildFilterExpression(filters: Map<String, Any>): Filter.Expression? {
    val b = FilterExpressionBuilder()
    val expressions = filters.map { (key, value) -> b.eq(key, value) }
    return if (expressions.size == 1) {
        expressions.first().build()
    } else {
        expressions.reduce { acc, expr -> b.and(acc, expr) }.build()
    }
}
```

- `SpringAiVectorStoreRetriever`: Spring AI의 `FilterExpressionBuilder`로 변환하여 벡터 DB 레벨에서 필터링
- `InMemoryDocumentRetriever`: 메타데이터 맵을 직접 비교하여 필터링

#### InMemoryDocumentRetriever

테스트/개발용 메모리 기반 구현. 키워드 매칭(Jaccard 유사도)으로 검색하며, 메타데이터 필터링도 지원합니다.

### Stage 3: DocumentReranker

```kotlin
interface DocumentReranker {
    suspend fun rerank(
        query: String,
        documents: List<RetrievedDocument>,
        topK: Int = 5
    ): List<RetrievedDocument>
}
```

3가지 구현이 제공됩니다:

#### SimpleScoreReranker

벡터 검색 점수로만 재정렬:

```kotlin
documents.sortedByDescending { it.score }.take(topK)
```

#### KeywordWeightedReranker

벡터 점수 + 키워드 매칭 점수를 가중합:

```
결합 점수 = doc.score × (1 - keywordWeight) + keywordScore × keywordWeight
```

기본 `keywordWeight = 0.3` — 벡터 70% + 키워드 30%

#### DiversityReranker (MMR)

**Maximal Marginal Relevance** 알고리즘:

```
MMR(d) = λ × Relevance(d, q) - (1-λ) × max(Similarity(d, d_i))
```

- `lambda = 0.5` (기본) — 관련성과 다양성의 균형
- `lambda → 1.0` — 관련성 중시
- `lambda → 0.0` — 다양성 중시

**동작 방식:**

1. 점수가 가장 높은 문서를 먼저 선택
2. 나머지 문서 중 MMR 점수가 가장 높은 것을 선택
3. topK개가 될 때까지 반복

문서 간 유사도는 Jaccard 유사도로 계산합니다.

### Stage 4: ContextBuilder

```kotlin
interface ContextBuilder {
    fun build(documents: List<RetrievedDocument>, maxTokens: Int = 4000): String
}
```

#### SimpleContextBuilder

```kotlin
class SimpleContextBuilder(
    private val separator: String = "\n\n---\n\n"
) : ContextBuilder

override fun build(documents: List<RetrievedDocument>, maxTokens: Int): String {
    val sb = StringBuilder()
    var currentTokens = 0

    for (doc in documents) {
        val docTokens = doc.estimatedTokens
        if (currentTokens + docTokens > maxTokens) break  // 토큰 예산 초과 시 중단

        if (sb.isNotEmpty()) sb.append(separator)
        doc.source?.let { sb.append("[Source: $it]\n") }
        sb.append(doc.content)

        currentTokens += docTokens
    }

    return sb.toString()
}
```

**토큰 인식:** 각 문서의 `estimatedTokens` (~4자/토큰 근사값)를 확인하여 예산 내에서만 포함합니다.

### RAG 모델

```kotlin
// 검색 요청
data class RagQuery(
    val query: String,
    val filters: Map<String, Any> = emptyMap(),
    val topK: Int = 10,
    val rerank: Boolean = true
)

// 검색된 문서
data class RetrievedDocument(
    val id: String,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val score: Double = 0.0,
    val source: String? = null
) {
    val estimatedTokens: Int get() = content.length / 4
}

// 검색 결과 컨텍스트
data class RagContext(
    val context: String,              // 최종 텍스트 (시스템 프롬프트에 주입)
    val documents: List<RetrievedDocument>,
    val totalTokens: Int = 0
) {
    val hasDocuments: Boolean get() = documents.isNotEmpty()

    companion object {
        val EMPTY = RagContext(context = "", documents = emptyList())
    }
}
```

### DefaultRagPipeline

```kotlin
class DefaultRagPipeline(
    private val queryTransformer: QueryTransformer? = null,  // 선택적
    private val retriever: DocumentRetriever,                // 필수
    private val reranker: DocumentReranker? = null,          // 선택적
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline
```

실행 흐름:

```kotlin
override suspend fun retrieve(query: RagQuery): RagContext {
    // 1. Query Transform (없으면 원본 사용)
    val transformedQueries = queryTransformer?.transform(query.query)
        ?: listOf(query.query)

    // 2. Retrieve
    val documents = retriever.retrieve(transformedQueries, query.topK)
    if (documents.isEmpty()) return RagContext.EMPTY

    // 3. Rerank (없거나 비활성화면 점수순 정렬)
    val rerankedDocs = if (query.rerank && reranker != null) {
        reranker.rerank(query.query, documents, query.topK)
    } else {
        documents.take(query.topK)
    }

    // 4. Build Context
    val context = contextBuilder.build(rerankedDocs, maxContextTokens)

    return RagContext(
        context = context,
        documents = rerankedDocs,
        totalTokens = tokenEstimator.estimate(context)
    )
}
```

### Executor 통합

**RAG 컨텍스트 검색:**

```kotlin
// SpringAiAgentExecutor.kt
private suspend fun retrieveRagContext(userPrompt: String): String? {
    if (!properties.rag.enabled || ragPipeline == null) return null

    return try {
        val ragResult = ragPipeline.retrieve(
            RagQuery(query = userPrompt, topK = properties.rag.topK, rerank = properties.rag.rerankEnabled)
        )
        if (ragResult.hasDocuments) ragResult.context else null
    } catch (e: Exception) {
        logger.warn(e) { "RAG retrieval failed, continuing without context" }
        null  // Graceful degradation: RAG 실패 시 일반 모드로 계속
    }
}
```

**시스템 프롬프트에 주입:**

```kotlin
private fun buildSystemPrompt(basePrompt: String, ragContext: String?, ...): String {
    val parts = mutableListOf(basePrompt)
    if (ragContext != null) {
        parts.add("[Retrieved Context]\n$ragContext")
    }
    return parts.joinToString("\n\n")
}
```

최종 시스템 프롬프트:

```
{사용자 정의 시스템 프롬프트}

[Retrieved Context]
[Source: document1.pdf]
문서 내용 1...

---

[Source: document2.md]
문서 내용 2...
```

### RAG 설정

```yaml
arc:
  reactor:
    rag:
      enabled: false              # RAG 활성화 여부 (기본: 비활성)
      similarity-threshold: 0.65  # 최소 유사도 임계값
      top-k: 5                    # 검색할 문서 수
      rerank-enabled: false       # 재정렬 (옵트인)
      max-context-tokens: 4000    # RAG 컨텍스트 최대 토큰
```

---

## 토큰 예산 통합 관리

메모리와 RAG 모두 토큰 예산의 영향을 받습니다:

```
maxContextWindowTokens (128,000)
├── System Prompt 토큰
│   ├── 사용자 정의 프롬프트
│   ├── [Retrieved Context] (RAG)      ← maxContextTokens=4000
│   └── [Response Format] (JSON 모드)
├── Conversation History 토큰 (Memory)  ← maxConversationTurns * 2
├── 현재 User Message 토큰
├── Tool 호출/응답 토큰 (ReAct 루프 중)
└── maxOutputTokens 예약 (4,096)        ← LLM 출력용
```

**컨텍스트 트리밍 순서:**

1. 메모리 로드 시: `maxConversationTurns`로 턴 수 제한
2. RAG 컨텍스트 빌드 시: `maxContextTokens`로 문서 수 제한
3. ReAct 루프 중: `trimMessagesToFitContext()`로 오래된 메시지 제거

---

## 토큰 관리 전략 — "매번 전체 이력을 보내면 낭비 아닌가?"

### 문제

LLM은 **무상태(stateless)**이다. 매 요청마다 이전 대화를 다시 보내야 맥락을 유지한다.
세션이 길어질수록 토큰 소비가 급증한다:

```
1턴:  시스템 프롬프트 + 질문 1개                    → ~500 토큰
5턴:  시스템 프롬프트 + 질문 5개 + 응답 5개          → ~5,000 토큰
20턴: 시스템 프롬프트 + 질문 20개 + 응답 20개        → ~20,000 토큰
50턴: 시스템 프롬프트 + 질문 50개 + 응답 50개        → ~50,000+ 토큰  ← 낭비
```

### Arc Reactor의 3단계 제한

Arc Reactor는 이 문제를 **3겹의 방어**로 처리한다:

#### 1. 턴 수 제한 (Sliding Window)

```kotlin
// ConversationManager.kt — loadHistory()
memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2)
```

- 기본값: `maxConversationTurns = 10` → 최근 **20개 메시지**만 LLM에 전달
- 50턴 대화를 했어도 최근 10턴(user 10 + assistant 10)만 보냄
- 설정: `arc.reactor.llm.max-conversation-turns`

#### 2. DB 저장 한도 (FIFO Eviction)

```kotlin
// JdbcMemoryStore.kt — evictOldMessages()
if (count > maxMessagesPerSession) {
    // DELETE oldest messages
}
```

- InMemoryMemoryStore: 세션당 최대 **50개** 메시지
- JdbcMemoryStore: 세션당 최대 **100개** 메시지
- 초과 시 가장 오래된 메시지부터 자동 삭제

#### 3. 토큰 기반 잘라내기 (Token Budget)

```kotlin
// InMemoryConversationMemory.kt — getHistoryWithinTokenLimit()
for (message in messages.reversed()) {
    val tokens = tokenEstimator.estimate(message.content)
    if (totalTokens + tokens > maxTokens) break  // 예산 초과 시 중단
    result.add(message)
}
```

- 역순 순회: 최신 메시지부터 포함, 예산 초과 시 오래된 메시지 제외
- CJK 문자 인식: 한글은 ~1.5자/토큰으로 계산 (영문 4자/토큰보다 높음)

### 실제 토큰 사용량 시뮬레이션

기본 설정(`maxConversationTurns=10`)일 때:

| 시나리오 | 실제 전송되는 이력 | 예상 토큰 | 128K 대비 |
|----------|------------------|----------|----------|
| 1턴 대화 | 메시지 2개 (user+assistant) | ~200~800 | 0.2~0.6% |
| 5턴 대화 | 메시지 10개 | ~2,000~4,000 | 1.6~3.1% |
| 10턴 대화 | 메시지 20개 (최대) | ~4,000~8,000 | 3.1~6.3% |
| 50턴 대화 | 메시지 20개 (최근 10턴만) | ~4,000~8,000 | 3.1~6.3% |

**결론:** 기본 설정에서 대화 이력은 컨텍스트 윈도우의 **3~6% 수준**으로 유지된다.

### 업계 비교: ChatGPT 등은 어떻게 하는가?

| 전략 | 설명 | 사용처 | Arc Reactor |
|------|------|--------|-------------|
| **Sliding Window** | 최근 N턴만 전송 | 대부분의 AI 서비스 | **사용 중** (`maxConversationTurns`) |
| **Summarization** | 오래된 대화를 LLM으로 요약, system prompt에 포함 | ChatGPT (추정), Claude | 미구현 |
| **RAG 기반 검색** | 대화 이력을 벡터 DB에 저장, 관련 과거 대화만 검색 | 일부 기업용 서비스 | 미구현 (대화 이력 대상) |
| **혼합** | 요약 + 최근 원문 + RAG | ChatGPT (추정) | 미구현 |

**ChatGPT의 추정 방식:**

```
[System Prompt]
[Memory: 사용자가 Python 개발자이고, 한국어를 선호함]    ← 장기 기억 (요약)
[Summary: 이전에 환불 절차에 대해 논의함]                ← 대화 요약
[Recent 5-10 turns raw messages]                         ← 최근 대화 원문
[Current user message]                                    ← 현재 질문
```

ChatGPT는 Sliding Window + Summarization을 혼합하는 것으로 추정된다.
오래된 대화는 요약본으로, 최근 대화는 원문 그대로 합쳐서 보낸다.

### 향후 개선 가능성

Arc Reactor에 Summarization을 추가하려면:

```kotlin
// 1. 오래된 대화를 LLM에 요약 요청
val summary = llm.call("Summarize this conversation in 3 sentences: $oldHistory")

// 2. 요약을 system prompt에 주입
val systemPrompt = """
$basePrompt

[Conversation Summary]
$summary
"""

// 3. 최근 N턴 원문 + 요약을 합쳐서 전달
```

**트레이드오프:**
- 장점: 오래된 맥락 보존, 토큰 효율 증가
- 단점: 매 턴마다 요약 LLM 호출 비용 추가 (지연 + 비용)
- 현실적 타협: N턴마다 한 번씩 배치 요약 (매 턴이 아닌)

---

## RAG 현재 상태

### 구현 현황

RAG 파이프라인은 **완전히 구현**되어 있지만, **기본 비활성화** 상태이다:

```yaml
# application.yml
arc:
  reactor:
    rag:
      enabled: false  # ← 기본값: 꺼져 있음
```

| 컴포넌트 | 상태 | 구현체 |
|----------|------|--------|
| RagPipeline | 구현 완료 | `DefaultRagPipeline` (4단계) |
| DocumentRetriever | 구현 완료 | `SpringAiVectorStoreRetriever` + `InMemoryDocumentRetriever` |
| DocumentReranker | 구현 완료 | `SimpleScoreReranker` + `KeywordWeightedReranker` + `DiversityReranker` |
| ContextBuilder | 구현 완료 | `SimpleContextBuilder` (토큰 인식) |
| QueryTransformer | 구현 완료 | `PassthroughQueryTransformer` + `HyDEQueryTransformer` + `ConversationAwareQueryTransformer` |
| Metadata Filtering | 구현 완료 | `DocumentRetriever.retrieve(filters)` — AND 논리, `FilterExpressionBuilder` 활용 |
| VectorStore | **의존성만** | `compileOnly` — 활성화하려면 `implementation`으로 변경 |

### 벡터 DB 지원

```kotlin
// build.gradle.kts — compileOnly (사용자가 필요 시 implementation으로 전환)
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pinecone")
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-chroma")
```

pgvector, Pinecone, Chroma 등을 Spring AI의 `VectorStore` 추상화를 통해 지원한다.
코드 수정 없이 의존성 + 설정만 바꾸면 활성화된다.

### RAG vs 대화 메모리 — 혼동 주의

이 두 시스템은 **목적이 완전히 다르다:**

| | 대화 메모리 (Memory) | RAG |
|---|---|---|
| **대상** | 현재 세션의 대화 이력 | 외부 지식 베이스 (문서, PDF, DB 등) |
| **목적** | "아까 뭐라고 했지?" 맥락 유지 | "이 문서에 뭐라고 써있지?" 지식 검색 |
| **데이터 원천** | conversation_messages 테이블 | VectorStore (벡터 DB) |
| **자동 축적** | 대화하면 자동 저장 | 사전에 문서를 인덱싱해야 함 |
| **사용 시점** | 매 요청 | `rag.enabled: true`일 때만 |

### 벡터 DB는 기업 전용인가?

**아니다.** ChatGPT도 벡터 검색을 사용한다:

| 서비스 | 벡터 검색 사용 | 용도 |
|--------|---------------|------|
| **ChatGPT** | 사용 | 파일 업로드 검색 (Code Interpreter), GPTs의 Knowledge |
| **Claude** | 사용 | Projects의 파일 검색 |
| **Perplexity** | 사용 | 웹 검색 결과 재정렬 |
| **기업 챗봇** | 사용 | 사내 문서 검색 (가장 흔한 사용처) |
| **Cursor/GitHub Copilot** | 사용 | 코드베이스 검색 |

벡터 DB의 용도:
- **범용 AI 서비스**: 사용자가 업로드한 파일 검색 (ChatGPT Files, Claude Projects)
- **기업용**: 사내 문서, 매뉴얼, FAQ 검색 (가장 일반적인 RAG 사용처)
- **개발 도구**: 코드베이스 임베딩 + 검색

**ChatGPT의 "대화 기억"은 벡터 검색이 아니다:**
ChatGPT의 Memory 기능("사용자가 Python 개발자임을 기억")은 벡터 DB가 아닌,
key-value 형태의 사실 목록을 system prompt에 주입하는 방식으로 추정된다.

---

## 설계 결정

| 결정 | 근거 |
|------|------|
| CJK 토큰 비율 1.5자/토큰 | BPE 토크나이저의 한국어/중국어 특성 반영 |
| 실패 시 save 스킵 | 불완전한 실행 기록으로 인한 혼란 방지 |
| 스트리밍 저장은 finally에서 | withTimeout 밖에서 실행하여 완전성 보장 |
| RAG 실패 시 계속 진행 | Graceful degradation — 검색 실패가 전체 실패로 전파되지 않음 |
| QueryTransformer/Reranker 선택적 | 불필요한 단계를 건너뛰어 성능 최적화 |
| Caffeine 캐시 (InMemory) | LRU 자동 정리, 단일 프로세스에 적합 |
| JDBC는 compileOnly | 선택적 의존 — PostgreSQL 불필요 시 제외 가능 |
| RAG 기본 비활성화 | VectorStore 없으면 무의미 — 필요한 사용자만 활성화 |
| Sliding Window 우선 | 구현 간단 + 대부분의 시나리오에서 충분 — Summarization은 추후 고려 |

## RAG 적재 리뷰 큐 (Admin)

Arc Reactor는 기업 운영용으로 Q&A 적재를 리뷰 큐 방식으로 지원한다:

1. 성공한 Q&A를 `PENDING` 후보로 수집
2. Admin이 후보 목록을 검토
3. 승인(`INGESTED`) 또는 반려(`REJECTED`)
4. 승인 시 `VectorStore`에 문서 적재

### 핵심 설정

```yaml
arc:
  reactor:
    rag:
      ingestion:
        enabled: true
        dynamic:
          enabled: true
        require-review: true
```

### Admin API

- `GET /api/rag-ingestion/policy`
- `PUT /api/rag-ingestion/policy`
- `DELETE /api/rag-ingestion/policy`
- `GET /api/rag-ingestion/candidates`
- `POST /api/rag-ingestion/candidates/{id}/approve`
- `POST /api/rag-ingestion/candidates/{id}/reject`

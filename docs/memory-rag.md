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
}
```

#### InMemoryMemoryStore

```kotlin
class InMemoryMemoryStore(
    private val maxSessions: Int = 1000
) : MemoryStore {
    private val sessions = Caffeine.newBuilder()
        .maximumSize(maxSessions.toLong())
        .build<String, ConversationMemory>()
}
```

- **Caffeine 캐시:** LRU 제거 정책
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
    fun loadHistory(command: AgentCommand): List<Message>
    fun saveHistory(command: AgentCommand, result: AgentResult)
    fun saveStreamingHistory(command: AgentCommand, content: String)
}
```

Executor와 MemoryStore 사이의 중간 계층으로, 대화 히스토리의 로드/저장 로직을 캡슐화합니다.

#### DefaultConversationManager

**히스토리 로드:**

```kotlin
override fun loadHistory(command: AgentCommand): List<Message> {
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
override fun saveHistory(command: AgentCommand, result: AgentResult) {
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
override fun saveStreamingHistory(command: AgentCommand, content: String) {
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

**확장 가능한 구현들:**
- **HyDE:** LLM으로 가상 문서를 생성하여 검색
- **다중 쿼리:** 의역, 동의어 추가로 여러 쿼리 생성
- **쿼리 정규화:** 불필요한 부분 제거

### Stage 2: DocumentRetriever

```kotlin
interface DocumentRetriever {
    suspend fun retrieve(queries: List<String>, topK: Int = 10): List<RetrievedDocument>
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
override suspend fun retrieve(queries: List<String>, topK: Int): List<RetrievedDocument> {
    val allDocuments = queries.flatMap { query ->
        val searchRequest = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(defaultSimilarityThreshold)
            .build()
        vectorStore.similaritySearch(searchRequest)
            .map { it.toRetrievedDocument() }
    }

    return allDocuments
        .sortedByDescending { it.score }
        .distinctBy { it.id }   // 다중 쿼리에서 중복 제거
        .take(topK)
}
```

#### InMemoryDocumentRetriever

테스트/개발용 메모리 기반 구현. 키워드 매칭(Jaccard 유사도)으로 검색합니다.

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
      similarity-threshold: 0.7   # 최소 유사도 임계값
      top-k: 10                   # 검색할 문서 수
      rerank-enabled: true        # 재정렬 활성화
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

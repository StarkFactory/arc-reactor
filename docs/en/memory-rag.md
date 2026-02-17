# Memory System & RAG Pipeline

> This document explains the internals of Arc Reactor's conversation history management (Memory) and external knowledge retrieval (RAG).

## Memory System

### 3-Layer Hierarchy

```
┌─────────────────────────────────────────────┐
│              ConversationManager             │  Conversation lifecycle management
│  loadHistory() / saveHistory()              │  Used directly by Executor
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│                MemoryStore                   │  Per-session memory management
│  getOrCreate(sessionId) / addMessage()      │  Multi-tenant architecture
│  ├── InMemoryMemoryStore (Caffeine LRU)     │
│  └── JdbcMemoryStore (PostgreSQL)           │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│             ConversationMemory               │  Message list management
│  add() / getHistory() / clear()             │
│  getHistoryWithinTokenLimit(maxTokens)      │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│              TokenEstimator                  │  Token count estimation
│  estimate(text) → Int                       │  CJK character aware
└─────────────────────────────────────────────┘
```

### TokenEstimator

```kotlin
fun interface TokenEstimator {
    fun estimate(text: String): Int
}
```

`DefaultTokenEstimator` applies different ratios by character type:

| Character Type | Ratio | Example |
|----------------|-------|---------|
| Latin (English, digits) | ~4 chars/token | "hello" = 2 tokens |
| CJK (Korean, Chinese, Japanese) | ~1.5 chars/token | "안녕하세요" = 4 tokens |
| Emoji | ~1 char/token | "🎉" = 1 token |
| Other | ~3 chars/token | Special characters, etc. |

**Why CJK awareness matters:** In BPE tokenizers, Korean/Chinese/Japanese characters consume more tokens per character. If you only use the Latin ratio (4 chars/token), you may exceed the context window.

**Unicode ranges:**

```
CJK Unified Ideographs: 0x4E00..0x9FFF (Chinese characters)
Hangul Syllables:       0xAC00..0xD7AF
Hiragana:               0x3040..0x309F
Katakana:               0x30A0..0x30FF
Emoji:                  0x1F300..0x1FAFF, 0x2600..0x27BF
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

- **Thread-safe:** Uses `ReentrantReadWriteLock` (allows concurrent reads)
- **FIFO eviction:** When `maxMessages` is exceeded, the oldest messages are removed first
- **Token-based trimming:** `getHistoryWithinTokenLimit()` iterates in reverse order, including messages from newest to oldest and stopping when the token budget is exceeded

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

- **Caffeine cache:** LRU eviction policy
- When the maximum session count is reached, the least recently used session is automatically evicted
- All data is lost on server restart

#### JdbcMemoryStore

```kotlin
class JdbcMemoryStore(
    private val jdbcTemplate: JdbcTemplate,
    private val maxMessagesPerSession: Int = 100,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : MemoryStore
```

**Table schema:**

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

**Key features:**

1. **Message loading:** `SELECT ... WHERE session_id = ? ORDER BY id ASC`
2. **FIFO eviction:** Maintains per-session message count at or below `maxMessagesPerSession`
3. **TTL cleanup:** `cleanupExpiredSessions(ttlMs)` -- deletes sessions when TTL has elapsed since the last message

**Auto-detection:**

```kotlin
// ArcReactorAutoConfiguration
@ConditionalOnClass(JdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
fun jdbcMemoryStore(jdbcTemplate: JdbcTemplate): MemoryStore = JdbcMemoryStore(jdbcTemplate)

// Falls back to InMemory when no DataSource is present
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

An intermediate layer between the Executor and MemoryStore that encapsulates the load/save logic for conversation history.

#### DefaultConversationManager

**Loading history:**

```kotlin
override fun loadHistory(command: AgentCommand): List<Message> {
    // 1. History passed directly via AgentCommand takes priority
    if (command.conversationHistory.isNotEmpty()) {
        return command.conversationHistory.map { toSpringAiMessage(it) }
    }

    // 2. Look up from MemoryStore using sessionId
    val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
    val memory = memoryStore.getOrCreate(sessionId)

    // 3. Return only the most recent N turns (maxConversationTurns * 2: User + Assistant = 1 turn)
    return memory.getHistory()
        .takeLast(properties.llm.maxConversationTurns * 2)
        .map { toSpringAiMessage(it) }
}
```

**Saving history:**

```kotlin
override fun saveHistory(command: AgentCommand, result: AgentResult) {
    if (!result.success) return  // Do not save failed executions

    val sessionId = command.metadata["sessionId"]?.toString() ?: return
    try {
        memoryStore.addMessage(sessionId, "USER", command.userPrompt)
        memoryStore.addMessage(sessionId, "ASSISTANT", result.content ?: "")
    } catch (e: Exception) {
        logger.error(e) { "Failed to save conversation history" }
        // Save failure does not abort the overall execution (fail-safe)
    }
}
```

**Saving streaming history:**

```kotlin
override fun saveStreamingHistory(command: AgentCommand, content: String) {
    // Only saves lastIterationContent (the last iteration, not the full accumulation)
    val sessionId = command.metadata["sessionId"]?.toString() ?: return
    memoryStore.addMessage(sessionId, "USER", command.userPrompt)
    memoryStore.addMessage(sessionId, "ASSISTANT", content)
}
```

### Executor Integration

```
executeInternal()
    │
    ├─ Step 3: val conversationHistory = conversationManager.loadHistory(command)
    │         → Converted to Spring AI Message list and included in LLM call
    │
    ├─ Step 7: conversationManager.saveHistory(command, result)
    │         → Only saved on success
    │
    └─ Streaming: saveStreamingHistory() in the finally block
                → Executed outside withTimeout (guarantees save even after stream cancellation)
```

---

## RAG Pipeline

### 4-Stage Architecture

```
User Query
    │
    ▼
┌─────────────────────┐
│ 1. QueryTransformer  │  Query transformation/expansion (optional)
│    "Search optimization"
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 2. DocumentRetriever │  Vector search
│    "Fetch documents"
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 3. DocumentReranker  │  Reranking (optional)
│    "Re-evaluate relevance"
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ 4. ContextBuilder    │  Token-aware context generation
│    "Inject into prompt"
└─────────────────────┘
```

### Stage 1: QueryTransformer

```kotlin
interface QueryTransformer {
    suspend fun transform(query: String): List<String>
}
```

Expands a single query into multiple queries to improve retrieval quality.

**Default implementation:** `PassthroughQueryTransformer` -- passes through the original query without transformation

#### HyDEQueryTransformer

**HyDE (Hypothetical Document Embeddings)** -- Uses an LLM to generate a hypothetical answer document, then uses both the original query and the hypothetical document for retrieval.

```
User query: "What is our return policy?"

→ LLM generates hypothetical answer:
  "Our return policy allows customers to return items within 30 days
   of purchase for a full refund. Items must be unused and in original packaging."

→ 2 search queries:
  1. "What is our return policy?"              ← original
  2. "Our return policy allows customers..."   ← hypothetical (closer in embedding space to actual documents)
```

**Why it works:** A question ("What is the policy?") and its answer ("The policy is...") have different vocabulary but similar meaning. By generating a hypothetical answer, we create a query that's closer in embedding space to the actual documents, improving retrieval accuracy.

```kotlin
val transformer = HyDEQueryTransformer(chatClient)
val queries = transformer.transform("What is our return policy?")
// → ["What is our return policy?", "Our return policy allows customers to return items..."]
```

Falls back to original query on error (graceful fallback).

#### ConversationAwareQueryTransformer

**Conversation-aware query rewriting** -- Rewrites the user's query by incorporating conversation context, resolving pronouns and implicit references into a standalone search query.

```
Conversation history:
  User: "Tell me about the return policy"
  AI: "Items can be returned within 30 days."
  User: "What about electronics?"       ← ambiguous without context

→ LLM rewrites to: "What is the return policy for electronics?"
→ This standalone query retrieves better documents
```

```kotlin
val transformer = ConversationAwareQueryTransformer(chatClient, maxHistoryTurns = 5)
transformer.updateHistory(listOf("User: Tell me about the return policy", "AI: 30 days..."))
val queries = transformer.transform("What about electronics?")
// → ["What is the return policy for electronics?"]
```

- Returns original query without LLM call when no conversation history exists
- `maxHistoryTurns` limits the number of history turns sent to LLM (default: 5)
- Falls back to original query on error

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

Vector similarity search using Spring AI's `VectorStore`:

```kotlin
override suspend fun retrieve(
    queries: List<String>, topK: Int, filters: Map<String, Any>
): List<RetrievedDocument> {
    val allDocuments = queries.flatMap { query ->
        searchWithQuery(query, topK, filters)
    }
    return allDocuments
        .sortedByDescending { it.score }
        .distinctBy { it.id }   // Deduplicate across multi-query results
        .take(topK)
}
```

#### Metadata Filtering

Supports metadata-based document filtering via `RagQuery.filters`. Multiple filters are combined with AND logic.

```kotlin
// Retrieve only documents with source=docs AND category=language
val result = pipeline.retrieve(RagQuery(
    query = "kotlin guide",
    topK = 10,
    filters = mapOf("source" to "docs", "category" to "language")
))
```

**Spring AI FilterExpression conversion:**

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

- `SpringAiVectorStoreRetriever`: Converts to Spring AI `FilterExpressionBuilder` for filtering at the vector DB level
- `InMemoryDocumentRetriever`: Filters by direct metadata map comparison

#### InMemoryDocumentRetriever

An in-memory implementation for testing/development. Searches using keyword matching (Jaccard similarity) and supports metadata filtering.

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

Three implementations are provided:

#### SimpleScoreReranker

Reranks solely by vector search score:

```kotlin
documents.sortedByDescending { it.score }.take(topK)
```

#### KeywordWeightedReranker

Weighted combination of vector score and keyword matching score:

```
Combined score = doc.score × (1 - keywordWeight) + keywordScore × keywordWeight
```

Default `keywordWeight = 0.3` -- 70% vector + 30% keyword

#### DiversityReranker (MMR)

**Maximal Marginal Relevance** algorithm:

```
MMR(d) = λ × Relevance(d, q) - (1-λ) × max(Similarity(d, d_i))
```

- `lambda = 0.5` (default) -- balance between relevance and diversity
- `lambda → 1.0` -- favors relevance
- `lambda → 0.0` -- favors diversity

**How it works:**

1. Select the document with the highest score first
2. From the remaining documents, select the one with the highest MMR score
3. Repeat until topK documents are selected

Inter-document similarity is computed using Jaccard similarity.

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
        if (currentTokens + docTokens > maxTokens) break  // Stop when token budget is exceeded

        if (sb.isNotEmpty()) sb.append(separator)
        doc.source?.let { sb.append("[Source: $it]\n") }
        sb.append(doc.content)

        currentTokens += docTokens
    }

    return sb.toString()
}
```

**Token-aware:** Checks each document's `estimatedTokens` (~4 chars/token approximation) and only includes documents within the budget.

### RAG Models

```kotlin
// Search request
data class RagQuery(
    val query: String,
    val filters: Map<String, Any> = emptyMap(),
    val topK: Int = 10,
    val rerank: Boolean = true
)

// Retrieved document
data class RetrievedDocument(
    val id: String,
    val content: String,
    val metadata: Map<String, Any> = emptyMap(),
    val score: Double = 0.0,
    val source: String? = null
) {
    val estimatedTokens: Int get() = content.length / 4
}

// Retrieval result context
data class RagContext(
    val context: String,              // Final text (injected into system prompt)
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
    private val queryTransformer: QueryTransformer? = null,  // Optional
    private val retriever: DocumentRetriever,                // Required
    private val reranker: DocumentReranker? = null,          // Optional
    private val contextBuilder: ContextBuilder = SimpleContextBuilder(),
    private val maxContextTokens: Int = 4000,
    private val tokenEstimator: TokenEstimator = DefaultTokenEstimator()
) : RagPipeline
```

Execution flow:

```kotlin
override suspend fun retrieve(query: RagQuery): RagContext {
    // 1. Query Transform (uses original if no transformer)
    val transformedQueries = queryTransformer?.transform(query.query)
        ?: listOf(query.query)

    // 2. Retrieve
    val documents = retriever.retrieve(transformedQueries, query.topK)
    if (documents.isEmpty()) return RagContext.EMPTY

    // 3. Rerank (sorts by score if no reranker or reranking is disabled)
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

### Executor Integration

**RAG context retrieval:**

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
        null  // Graceful degradation: continues in normal mode if RAG fails
    }
}
```

**Injection into system prompt:**

```kotlin
private fun buildSystemPrompt(basePrompt: String, ragContext: String?, ...): String {
    val parts = mutableListOf(basePrompt)
    if (ragContext != null) {
        parts.add("[Retrieved Context]\n$ragContext")
    }
    return parts.joinToString("\n\n")
}
```

Final system prompt:

```
{User-defined system prompt}

[Retrieved Context]
[Source: document1.pdf]
Document content 1...

---

[Source: document2.md]
Document content 2...
```

### RAG Configuration

```yaml
arc:
  reactor:
    rag:
      enabled: false              # Whether to enable RAG (default: disabled)
      similarity-threshold: 0.7   # Minimum similarity threshold
      top-k: 10                   # Number of documents to retrieve
      rerank-enabled: true        # Enable reranking
      max-context-tokens: 4000    # Maximum tokens for RAG context
```

---

## Unified Token Budget Management

Both Memory and RAG are subject to the token budget:

```
maxContextWindowTokens (128,000)
├── System Prompt tokens
│   ├── User-defined prompt
│   ├── [Retrieved Context] (RAG)      ← maxContextTokens=4000
│   └── [Response Format] (JSON mode)
├── Conversation History tokens (Memory)  ← maxConversationTurns * 2
├── Current User Message tokens
├── Tool call/response tokens (during ReAct loop)
└── maxOutputTokens reserved (4,096)        ← Reserved for LLM output
```

**Context trimming order:**

1. On memory load: Limit turn count via `maxConversationTurns`
2. On RAG context build: Limit document count via `maxContextTokens`
3. During ReAct loop: Remove old messages via `trimMessagesToFitContext()`

---

## Token Management Strategy -- "Isn't sending the full history every time wasteful?"

### The Problem

LLMs are **stateless**. You must resend the previous conversation with every request to maintain context.
As sessions grow longer, token consumption increases rapidly:

```
1 turn:   System prompt + 1 question                    → ~500 tokens
5 turns:  System prompt + 5 questions + 5 responses     → ~5,000 tokens
20 turns: System prompt + 20 questions + 20 responses   → ~20,000 tokens
50 turns: System prompt + 50 questions + 50 responses   → ~50,000+ tokens  ← wasteful
```

### Arc Reactor's 3-Layer Limiting

Arc Reactor addresses this problem with **3 layers of defense**:

#### 1. Turn Count Limit (Sliding Window)

```kotlin
// ConversationManager.kt — loadHistory()
memory.getHistory().takeLast(properties.llm.maxConversationTurns * 2)
```

- Default: `maxConversationTurns = 10` -> only the most recent **20 messages** are sent to the LLM
- Even in a 50-turn conversation, only the last 10 turns (10 user + 10 assistant) are sent
- Configuration: `arc.reactor.llm.max-conversation-turns`

#### 2. DB Storage Limit (FIFO Eviction)

```kotlin
// JdbcMemoryStore.kt — evictOldMessages()
if (count > maxMessagesPerSession) {
    // DELETE oldest messages
}
```

- InMemoryMemoryStore: Maximum **50** messages per session
- JdbcMemoryStore: Maximum **100** messages per session
- When the limit is exceeded, the oldest messages are automatically deleted

#### 3. Token-Based Trimming (Token Budget)

```kotlin
// InMemoryConversationMemory.kt — getHistoryWithinTokenLimit()
for (message in messages.reversed()) {
    val tokens = tokenEstimator.estimate(message.content)
    if (totalTokens + tokens > maxTokens) break  // Stop when budget is exceeded
    result.add(message)
}
```

- Reverse iteration: includes messages from newest first, excludes older messages when budget is exceeded
- CJK-aware: Korean is estimated at ~1.5 chars/token (higher than the 4 chars/token for English)

### Actual Token Usage Simulation

With default settings (`maxConversationTurns=10`):

| Scenario | History Actually Sent | Estimated Tokens | % of 128K |
|----------|----------------------|-----------------|-----------|
| 1-turn conversation | 2 messages (user+assistant) | ~200-800 | 0.2-0.6% |
| 5-turn conversation | 10 messages | ~2,000-4,000 | 1.6-3.1% |
| 10-turn conversation | 20 messages (maximum) | ~4,000-8,000 | 3.1-6.3% |
| 50-turn conversation | 20 messages (last 10 turns only) | ~4,000-8,000 | 3.1-6.3% |

**Conclusion:** With default settings, conversation history stays at around **3-6% of the context window**.

### Industry Comparison: How Do ChatGPT and Others Handle This?

| Strategy | Description | Used By | Arc Reactor |
|----------|-------------|---------|-------------|
| **Sliding Window** | Send only the last N turns | Most AI services | **In use** (`maxConversationTurns`) |
| **Summarization** | Summarize old conversations via LLM, include in system prompt | ChatGPT (estimated), Claude | Not implemented |
| **RAG-Based Retrieval** | Store conversation history in vector DB, retrieve only relevant past conversations | Some enterprise services | Not implemented (for conversation history) |
| **Hybrid** | Summarization + recent raw messages + RAG | ChatGPT (estimated) | Not implemented |

**ChatGPT's estimated approach:**

```
[System Prompt]
[Memory: User is a Python developer who prefers Korean]    ← Long-term memory (summary)
[Summary: Previously discussed refund procedures]           ← Conversation summary
[Recent 5-10 turns raw messages]                            ← Recent conversation verbatim
[Current user message]                                      ← Current question
```

ChatGPT is estimated to use a hybrid of Sliding Window + Summarization.
Old conversations are sent as summaries, while recent conversations are sent verbatim.

### Future Improvement Possibilities

To add Summarization to Arc Reactor:

```kotlin
// 1. Request LLM to summarize old conversations
val summary = llm.call("Summarize this conversation in 3 sentences: $oldHistory")

// 2. Inject summary into system prompt
val systemPrompt = """
$basePrompt

[Conversation Summary]
$summary
"""

// 3. Send recent N turns verbatim + summary combined
```

**Trade-offs:**
- Pros: Preserves older context, increases token efficiency
- Cons: Additional LLM call cost for summarization per turn (latency + cost)
- Practical compromise: Batch summarization every N turns (not every turn)

---

## RAG Current Status

### Implementation Status

The RAG pipeline is **fully implemented** but **disabled by default**:

```yaml
# application.yml
arc:
  reactor:
    rag:
      enabled: false  # ← Default: off
```

| Component | Status | Implementation |
|-----------|--------|----------------|
| RagPipeline | Fully implemented | `DefaultRagPipeline` (4 stages) |
| DocumentRetriever | Fully implemented | `SpringAiVectorStoreRetriever` + `InMemoryDocumentRetriever` |
| DocumentReranker | Fully implemented | `SimpleScoreReranker` + `KeywordWeightedReranker` + `DiversityReranker` |
| ContextBuilder | Fully implemented | `SimpleContextBuilder` (token-aware) |
| QueryTransformer | Fully implemented | `PassthroughQueryTransformer` + `HyDEQueryTransformer` + `ConversationAwareQueryTransformer` |
| Metadata Filtering | Fully implemented | `DocumentRetriever.retrieve(filters)` -- AND logic, uses `FilterExpressionBuilder` |
| VectorStore | **Dependency only** | `compileOnly` -- change to `implementation` to activate |

### Vector DB Support

```kotlin
// build.gradle.kts — compileOnly (users switch to implementation when needed)
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-pinecone")
compileOnly("org.springframework.ai:spring-ai-starter-vector-store-chroma")
```

Supports pgvector, Pinecone, Chroma, and more through Spring AI's `VectorStore` abstraction.
Activation requires only dependency and configuration changes -- no code modifications needed.

### RAG vs Conversation Memory -- Don't Confuse Them

These two systems serve **completely different purposes:**

| | Conversation Memory | RAG |
|---|---|---|
| **Target** | Current session's conversation history | External knowledge base (documents, PDFs, DBs, etc.) |
| **Purpose** | "What did I say earlier?" -- context preservation | "What does this document say?" -- knowledge retrieval |
| **Data source** | conversation_messages table | VectorStore (vector DB) |
| **Auto-accumulation** | Automatically saved during conversation | Documents must be indexed in advance |
| **When used** | Every request | Only when `rag.enabled: true` |

### Is Vector DB Enterprise-Only?

**No.** Even ChatGPT uses vector search:

| Service | Uses Vector Search | Purpose |
|---------|--------------------|---------|
| **ChatGPT** | Yes | File upload search (Code Interpreter), GPTs Knowledge |
| **Claude** | Yes | File search in Projects |
| **Perplexity** | Yes | Reranking web search results |
| **Enterprise chatbots** | Yes | Internal document search (most common use case) |
| **Cursor/GitHub Copilot** | Yes | Codebase search |

Vector DB use cases:
- **General-purpose AI services**: Searching user-uploaded files (ChatGPT Files, Claude Projects)
- **Enterprise**: Searching internal documents, manuals, FAQs (most common RAG use case)
- **Developer tools**: Codebase embedding + search

**ChatGPT's "conversation memory" is not vector search:**
ChatGPT's Memory feature ("remembers that user is a Python developer") is estimated to use a key-value fact list injected into the system prompt, not a vector DB.

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| CJK token ratio of 1.5 chars/token | Reflects Korean/Chinese characteristics in BPE tokenizers |
| Skip save on failure | Prevents confusion from incomplete execution records |
| Streaming save in finally block | Executed outside withTimeout to guarantee completeness |
| Continue on RAG failure | Graceful degradation -- retrieval failure does not propagate to overall failure |
| QueryTransformer/Reranker are optional | Skip unnecessary stages for performance optimization |
| Caffeine cache (InMemory) | Automatic LRU cleanup, suitable for single-process deployments |
| JDBC as compileOnly | Optional dependency -- can be excluded when PostgreSQL is not needed |
| RAG disabled by default | Meaningless without VectorStore -- only users who need it enable it |
| Sliding Window first | Simple implementation + sufficient for most scenarios -- Summarization considered for future |

## RAG Ingestion Review Queue (Admin)

Arc Reactor supports a review-queue ingestion flow for enterprise Slack/Web Q&A:

1. Capture successful Q&A as `PENDING` candidates
2. Admin reviews candidate list
3. Admin approves (`INGESTED`) or rejects (`REJECTED`)
4. Approved candidates are added to `VectorStore`

### Key Config

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

### Admin APIs

- `GET /api/rag-ingestion/policy`
- `PUT /api/rag-ingestion/policy`
- `DELETE /api/rag-ingestion/policy`
- `GET /api/rag-ingestion/candidates`
- `POST /api/rag-ingestion/candidates/{id}/approve`
- `POST /api/rag-ingestion/candidates/{id}/reject`

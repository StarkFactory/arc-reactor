# RAG Knowledge Base Q&A

Build an internal knowledge base Q&A system that ingests company documents and answers questions using semantic retrieval.

## Scenario

An internal team needs a chatbot that answers questions from uploaded PDFs and Markdown documents (HR policies, engineering runbooks, product specs). The system:

1. Accepts document uploads via a REST API
2. Embeds and stores them in a vector database
3. Retrieves relevant excerpts for every user question
4. Generates an answer grounded in the retrieved context

## Prerequisites

RAG requires a vector store dependency. Add one of the following to `build.gradle.kts`:

```kotlin
// PostgreSQL + pgvector (recommended for production)
implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")

// Or Chroma (simple local setup)
implementation("org.springframework.ai:spring-ai-starter-vector-store-chroma")
```

These are declared `compileOnly` in Arc Reactor's core — change to `implementation` in your fork's `build.gradle.kts`.

## Enable RAG

```yaml
# application.yml
arc:
  reactor:
    rag:
      enabled: true
      similarity-threshold: 0.70   # Minimum cosine similarity to include a document
      top-k: 8                     # Number of candidate documents retrieved
      rerank-enabled: true         # Enable score-based reranking
      max-context-tokens: 4000     # Maximum tokens injected into the system prompt

# pgvector configuration (if using pgvector)
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 768
  datasource:
    url: jdbc:postgresql://localhost:5432/knowledge_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}
```

With `rag.enabled: true`, the executor automatically retrieves documents matching the user's query and prepends them to the system prompt as:

```
{Your system prompt}

[Retrieved Context]
[Source: hr-policy.pdf]
Employees are entitled to 20 days of annual leave per year...

---

[Source: engineering-runbook.md]
To deploy to staging, run: ./deploy.sh --env staging...
```

## Document Ingestion

Arc Reactor exposes a document ingestion API when RAG is enabled:

```bash
# Ingest a document with source metadata
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Employees are entitled to 20 days of annual leave per year...",
    "metadata": {
      "source": "hr-policy.pdf",
      "category": "hr",
      "department": "people-ops"
    }
  }'

# Ingest Markdown text directly
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "## On-call Policy\nEach engineer rotates on-call weekly...",
    "metadata": {
      "source": "engineering-runbook.md",
      "category": "engineering",
      "team": "platform"
    }
  }'
```

Documents are split into chunks, embedded, and stored in the configured `VectorStore`. The `metadata.source` field appears in the `[Source: ...]` header injected into the system prompt.

## Ingestion Review Queue (Managed Mode)

For environments where ingestion must be reviewed before going live:

```yaml
arc:
  reactor:
    rag:
      ingestion:
        enabled: true
        require-review: true   # Documents land in PENDING state, not live yet
```

An admin reviews and approves candidates via:

```bash
# List pending documents
GET /api/rag-ingestion/candidates

# Approve (moves to VectorStore)
POST /api/rag-ingestion/candidates/{id}/approve

# Reject
POST /api/rag-ingestion/candidates/{id}/reject
```

## Knowledge Base Service

```kotlin
package com.arc.reactor.service

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import org.springframework.stereotype.Service

private val KB_SYSTEM_PROMPT = """
You are a knowledge base assistant for Acme Corp.
Answer questions using ONLY the information in the [Retrieved Context] section above.
If the context does not contain enough information to answer the question, say:
"I don't have that information. Please contact the relevant team directly."
Do not make up facts or draw on general knowledge outside of the provided context.
""".trimIndent()

@Service
class KnowledgeBaseService(
    private val agentExecutor: AgentExecutor
) {

    suspend fun ask(
        question: String,
        userId: String,
        sessionId: String? = null,
        categoryFilter: String? = null
    ): AgentResult {
        val metadata = buildMap<String, Any> {
            sessionId?.let { put("sessionId", it) }
            // Metadata filters are applied at the vector store level.
            // The RAG pipeline reads metadata["ragFilters"] if your retriever is wired to support it.
            categoryFilter?.let { put("ragFilters", mapOf("category" to it)) }
        }

        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = KB_SYSTEM_PROMPT,
                userPrompt = question,
                userId = userId,
                metadata = metadata,
                maxToolCalls = 0  // RAG-only: no tool calling needed
            )
        )
    }
}
```

Setting `maxToolCalls = 0` prevents the agent from making any tool calls. All context comes from the RAG pipeline, not from dynamically executed tools.

## Metadata Filtering

Restrict retrieval to a specific document category by passing filters to the retriever. This keeps retrieval focused and reduces noise:

```kotlin
// Retrieve only HR documents
val hrOnlyResult = agentExecutor.execute(
    AgentCommand(
        systemPrompt = KB_SYSTEM_PROMPT,
        userPrompt = "What is the parental leave policy?",
        userId = userId,
        metadata = mapOf(
            "ragFilters" to mapOf("category" to "hr")
        )
    )
)
```

Wire the filter in a custom `DocumentRetriever` if your retriever implementation reads `AgentCommand.metadata`:

```kotlin
class FilterAwareRetriever(
    private val vectorStore: VectorStore,
    private val defaultThreshold: Double = 0.7
) : DocumentRetriever {

    override suspend fun retrieve(
        queries: List<String>,
        topK: Int,
        filters: Map<String, Any>
    ): List<RetrievedDocument> {
        // filters are passed in from DefaultRagPipeline.retrieve()
        return queries.flatMap { query ->
            vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(defaultThreshold)
                    .filterExpression(buildFilter(filters))
                    .build()
            ).map { doc ->
                RetrievedDocument(
                    id = doc.id ?: "",
                    content = doc.content,
                    metadata = doc.metadata,
                    source = doc.metadata["source"]?.toString()
                )
            }
        }.distinctBy { it.id }.take(topK)
    }
}
```

## Similarity Threshold Tuning

The `similarity-threshold` controls recall vs. precision:

| Threshold | Effect |
|-----------|--------|
| `0.60` | Higher recall — more documents retrieved, including tangentially related ones |
| `0.70` | Balanced (default) — good for general-purpose Q&A |
| `0.80` | Higher precision — only closely matching documents, fewer but more relevant |
| `0.90` | Very strict — only near-exact matches |

Start at `0.70` and adjust based on observed false positives (irrelevant context) vs. false negatives (missing context).

```yaml
arc:
  reactor:
    rag:
      similarity-threshold: 0.72  # Tuned after testing with your corpus
```

## Query Transformation (HyDE)

For better retrieval on ambiguous questions, enable HyDE (Hypothetical Document Embeddings). The LLM generates a hypothetical answer to the question, and that hypothetical answer is used as a second search query alongside the original:

```kotlin
// Register a HyDEQueryTransformer bean to replace the default passthrough transformer
@Bean
@ConditionalOnMissingBean(QueryTransformer::class)
fun hydeQueryTransformer(chatClient: ChatClient): QueryTransformer {
    return HyDEQueryTransformer(chatClient)
}
```

How it works:

```
User question: "What is the vacation accrual rate?"

→ HyDE generates: "Employees accrue 1.67 days of vacation per month, totaling 20 days per year..."

→ Two search queries sent to VectorStore:
  1. "What is the vacation accrual rate?"        (original)
  2. "Employees accrue 1.67 days per month..."   (hypothetical — closer in embedding space to actual policy docs)
```

HyDE adds one additional LLM call per request. Enable it only when retrieval quality is a bottleneck.

## Combining RAG with Custom Tools

RAG and tools can be combined. A useful pattern: use RAG for background context and tools for real-time data:

```kotlin
@Service
class HrAssistantService(
    private val agentExecutor: AgentExecutor,
    private val lookupEmployeeTool: LookupEmployeeTool,  // real-time HR system
    private val submitRequestTool: SubmitRequestTool      // action: submit PTO request
) {

    suspend fun ask(question: String, userId: String): AgentResult {
        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = """
                    You are an HR assistant. Use the [Retrieved Context] for policy questions.
                    Use lookup_employee to get real-time employee details.
                    Use submit_request to submit leave or expense requests.
                """.trimIndent(),
                userPrompt = question,
                userId = userId,
                maxToolCalls = 5
                // RAG runs automatically because arc.reactor.rag.enabled=true
            )
        )
    }
}
```

The flow for "How many vacation days do I have left?" would be:

1. RAG retrieves the vacation accrual policy document
2. Agent calls `lookup_employee` to get the user's hire date and used days
3. Agent combines the policy rules with the real-time data to compute the answer

## Controller

```kotlin
package com.arc.reactor.controller

import com.arc.reactor.service.KnowledgeBaseService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class KbQuestion(
    val question: String,
    val userId: String,
    val sessionId: String? = null,
    val category: String? = null
)

data class KbAnswer(
    val answer: String?,
    val success: Boolean,
    val errorMessage: String?
)

@RestController
@RequestMapping("/api/kb")
@Tag(name = "Knowledge Base", description = "RAG-powered internal knowledge base")
class KnowledgeBaseController(
    private val kbService: KnowledgeBaseService
) {

    @PostMapping("/ask")
    @Operation(summary = "Ask a question against the internal knowledge base")
    suspend fun ask(@RequestBody request: KbQuestion): KbAnswer {
        val result = kbService.ask(
            question = request.question,
            userId = request.userId,
            sessionId = request.sessionId,
            categoryFilter = request.category
        )
        return KbAnswer(
            answer = result.content,
            success = result.success,
            errorMessage = result.errorMessage
        )
    }
}
```

## Testing

```bash
# Ingest a document
curl -X POST http://localhost:8080/api/documents \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Employees receive 20 days of annual leave per year, accrued monthly.",
    "metadata": {
      "source": "hr-leave-policy.md",
      "category": "hr"
    }
  }'

# Ask a question (will retrieve the above document)
curl -X POST http://localhost:8080/api/kb/ask \
  -H "Content-Type: application/json" \
  -d '{
    "question": "How many vacation days do employees get per year?",
    "userId": "employee-007",
    "category": "hr"
  }'
```

Expected response:

```json
{
  "answer": "Employees receive 20 days of annual leave per year, accrued monthly.",
  "success": true,
  "errorMessage": null
}
```

## Related

- [Memory & RAG Deep Dive](../architecture/memory-rag/deep-dive.md)
- [RAG Configuration reference](../architecture/memory-rag/architecture.md)
- [Ingestion and Retrieval](../architecture/memory-rag/ingestion-and-retrieval.md)

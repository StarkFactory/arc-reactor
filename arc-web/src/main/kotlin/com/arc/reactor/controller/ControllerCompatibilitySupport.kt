package com.arc.reactor.controller

import com.arc.reactor.persona.Persona
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import mu.KotlinLogging
import org.springframework.ai.document.Document
import java.util.UUID

private val compatibilityLogger = KotlinLogging.logger {}

internal fun Persona.resolveEffectivePrompt(promptTemplateStore: PromptTemplateStore?): String {
    val base = resolveBasePrompt(promptTemplateStore)
    val guideline = responseGuideline?.trim()
    return if (!guideline.isNullOrBlank()) "$base\n\n$guideline" else base
}

private fun Persona.resolveBasePrompt(promptTemplateStore: PromptTemplateStore?): String {
    val templateId = promptTemplateId
    if (templateId.isNullOrBlank() || promptTemplateStore == null) return systemPrompt
    return try {
        promptTemplateStore.getActiveVersion(templateId)?.content?.takeIf { it.isNotBlank() } ?: systemPrompt
    } catch (e: Exception) {
        compatibilityLogger.warn(e) {
            "프롬프트 템플릿 조회 실패: personaId='$id' promptTemplateId='$templateId'"
        }
        systemPrompt
    }
}

internal fun RagIngestionCandidate.toDocuments(
    documentId: String = UUID.randomUUID().toString(),
    chunker: DocumentChunker? = null
): List<Document> {
    val metadata = linkedMapOf<String, Any>(
        "source" to "rag_ingestion_candidate",
        "runId" to runId,
        "userId" to userId,
        "capturedAt" to capturedAt.toString()
    )
    sessionId?.takeIf { it.isNotBlank() }?.let { metadata["sessionId"] = it }
    channel?.takeIf { it.isNotBlank() }?.let { metadata["channel"] = it }

    val document = Document(documentId, buildRagQaContent(query, response), metadata)
    return chunker?.chunk(document) ?: listOf(document)
}

private fun buildRagQaContent(query: String, response: String): String {
    return buildString {
        append("Q: ")
        append(query.trim())
        append("\n\nA: ")
        append(response.trim())
    }
}

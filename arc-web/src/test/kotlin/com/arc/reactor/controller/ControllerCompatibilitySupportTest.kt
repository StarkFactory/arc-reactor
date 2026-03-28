package com.arc.reactor.controller

import com.arc.reactor.persona.Persona
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
import com.arc.reactor.rag.chunking.DocumentChunker
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import java.time.Instant

/**
 * [ControllerCompatibilitySupport] 유틸리티 함수에 대한 단위 테스트.
 *
 * 페르소나 시스템 프롬프트 해결 및 RAG 수집 후보의 Document 변환 로직을 검증한다.
 */
class ControllerCompatibilitySupportTest {

    @Nested
    inner class ResolveEffectivePrompt {

        @Test
        fun `promptTemplateId가 없으면 systemPrompt를 반환한다`() {
            val persona = buildPersona(systemPrompt = "기본 시스템 프롬프트", promptTemplateId = null)
            val store = mockk<PromptTemplateStore>()

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("기본 시스템 프롬프트", result) {
                "promptTemplateId가 없으면 systemPrompt를 그대로 반환해야 한다"
            }
        }

        @Test
        fun `promptTemplateStore가 null이면 systemPrompt를 반환한다`() {
            val persona = buildPersona(systemPrompt = "기본 프롬프트", promptTemplateId = "tmpl-1")

            val result = persona.resolveEffectivePrompt(null)

            assertEquals("기본 프롬프트", result) {
                "PromptTemplateStore가 null이면 systemPrompt를 그대로 반환해야 한다"
            }
        }

        @Test
        fun `활성 템플릿 버전이 있으면 해당 콘텐츠를 반환한다`() {
            val persona = buildPersona(systemPrompt = "폴백 프롬프트", promptTemplateId = "tmpl-1")
            val store = mockk<PromptTemplateStore>()
            val version = buildActiveVersion(content = "템플릿에서 가져온 프롬프트")
            every { store.getActiveVersion("tmpl-1") } returns version

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("템플릿에서 가져온 프롬프트", result) {
                "활성 버전이 있으면 템플릿 콘텐츠를 반환해야 한다"
            }
        }

        @Test
        fun `활성 버전이 없으면 systemPrompt로 폴백한다`() {
            val persona = buildPersona(systemPrompt = "폴백 프롬프트", promptTemplateId = "tmpl-1")
            val store = mockk<PromptTemplateStore>()
            every { store.getActiveVersion("tmpl-1") } returns null

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("폴백 프롬프트", result) {
                "활성 버전이 없으면 systemPrompt로 폴백해야 한다"
            }
        }

        @Test
        fun `활성 버전 콘텐츠가 공백이면 systemPrompt로 폴백한다`() {
            val persona = buildPersona(systemPrompt = "폴백 프롬프트", promptTemplateId = "tmpl-1")
            val store = mockk<PromptTemplateStore>()
            val version = buildActiveVersion(content = "   ")
            every { store.getActiveVersion("tmpl-1") } returns version

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("폴백 프롬프트", result) {
                "활성 버전 콘텐츠가 공백이면 systemPrompt로 폴백해야 한다"
            }
        }

        @Test
        fun `템플릿 조회 중 예외 발생 시 systemPrompt로 폴백한다`() {
            val persona = buildPersona(systemPrompt = "예외 폴백 프롬프트", promptTemplateId = "tmpl-error")
            val store = mockk<PromptTemplateStore>()
            every { store.getActiveVersion("tmpl-error") } throws RuntimeException("DB 연결 실패")

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("예외 폴백 프롬프트", result) {
                "템플릿 조회 실패 시 systemPrompt로 안전하게 폴백해야 한다"
            }
        }

        @Test
        fun `responseGuideline이 있으면 systemPrompt에 추가된다`() {
            val persona = buildPersona(
                systemPrompt = "기본 프롬프트",
                promptTemplateId = null,
                responseGuideline = "항상 한국어로 답변할 것"
            )
            val store = mockk<PromptTemplateStore>()

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("기본 프롬프트\n\n항상 한국어로 답변할 것", result) {
                "responseGuideline이 있으면 systemPrompt 뒤에 두 줄 개행으로 합쳐져야 한다"
            }
        }

        @Test
        fun `responseGuideline이 공백이면 systemPrompt만 반환한다`() {
            val persona = buildPersona(
                systemPrompt = "기본 프롬프트",
                promptTemplateId = null,
                responseGuideline = "   "
            )
            val store = mockk<PromptTemplateStore>()

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("기본 프롬프트", result) {
                "공백 responseGuideline은 무시되어야 한다"
            }
        }

        @Test
        fun `템플릿 콘텐츠와 responseGuideline이 모두 있으면 합쳐서 반환한다`() {
            val persona = buildPersona(
                systemPrompt = "폴백",
                promptTemplateId = "tmpl-1",
                responseGuideline = "간결하게 답변할 것"
            )
            val store = mockk<PromptTemplateStore>()
            val version = buildActiveVersion(content = "템플릿 프롬프트")
            every { store.getActiveVersion("tmpl-1") } returns version

            val result = persona.resolveEffectivePrompt(store)

            assertEquals("템플릿 프롬프트\n\n간결하게 답변할 것", result) {
                "템플릿 콘텐츠와 responseGuideline이 결합되어야 한다"
            }
        }
    }

    @Nested
    inner class ToDocuments {

        private val capturedAt = Instant.parse("2024-06-01T12:00:00Z")

        @Test
        fun `기본 필드로 단일 Document를 생성한다`() {
            val candidate = buildCandidate(query = "스프링이란?", response = "스프링은 Java 프레임워크입니다.")
            val docId = "doc-123"

            val docs = candidate.toDocuments(documentId = docId)

            assertEquals(1, docs.size) { "청커 없이는 단일 Document가 반환되어야 한다" }
            val doc = docs.first()
            assertEquals(docId, doc.id) { "Document ID가 지정된 값이어야 한다" }
        }

        @Test
        fun `Document 콘텐츠가 Q-A 형식으로 구성된다`() {
            val candidate = buildCandidate(query = "Kotlin이란?", response = "JVM 언어입니다.")

            val docs = candidate.toDocuments()

            val content = docs.first().text
            assertNotNull(content) { "Document 콘텐츠가 null이 아니어야 한다" }
            assertTrue(content!!.startsWith("Q: Kotlin이란?")) {
                "콘텐츠가 'Q: 쿼리' 형식으로 시작해야 한다"
            }
            assertTrue(content.contains("A: JVM 언어입니다.")) {
                "콘텐츠에 'A: 응답' 형식이 포함되어야 한다"
            }
        }

        @Test
        fun `쿼리와 응답 주변 공백은 trim된다`() {
            val candidate = buildCandidate(query = "  질문  ", response = "  답변  ")

            val docs = candidate.toDocuments()
            val content = docs.first().text

            assertTrue(content!!.contains("Q: 질문")) { "쿼리의 앞뒤 공백이 제거되어야 한다" }
            assertTrue(content.contains("A: 답변")) { "응답의 앞뒤 공백이 제거되어야 한다" }
        }

        @Test
        fun `메타데이터에 필수 필드가 포함된다`() {
            val candidate = buildCandidate(runId = "run-abc", userId = "user-xyz")

            val doc = candidate.toDocuments().first()

            assertEquals("rag_ingestion_candidate", doc.metadata["source"]) {
                "source 메타데이터가 'rag_ingestion_candidate'여야 한다"
            }
            assertEquals("run-abc", doc.metadata["runId"]) {
                "runId 메타데이터가 포함되어야 한다"
            }
            assertEquals("user-xyz", doc.metadata["userId"]) {
                "userId 메타데이터가 포함되어야 한다"
            }
        }

        @Test
        fun `sessionId가 있으면 메타데이터에 포함된다`() {
            val candidate = buildCandidate(sessionId = "sess-111")

            val doc = candidate.toDocuments().first()

            assertEquals("sess-111", doc.metadata["sessionId"]) {
                "sessionId가 있으면 메타데이터에 포함되어야 한다"
            }
        }

        @Test
        fun `sessionId가 null이면 메타데이터에 포함되지 않는다`() {
            val candidate = buildCandidate(sessionId = null)

            val doc = candidate.toDocuments().first()

            assertTrue(!doc.metadata.containsKey("sessionId")) {
                "sessionId가 null이면 메타데이터에 포함되지 않아야 한다"
            }
        }

        @Test
        fun `sessionId가 공백이면 메타데이터에 포함되지 않는다`() {
            val candidate = buildCandidate(sessionId = "   ")

            val doc = candidate.toDocuments().first()

            assertTrue(!doc.metadata.containsKey("sessionId")) {
                "공백 sessionId는 메타데이터에 포함되지 않아야 한다"
            }
        }

        @Test
        fun `channel이 있으면 메타데이터에 포함된다`() {
            val candidate = buildCandidate(channel = "slack")

            val doc = candidate.toDocuments().first()

            assertEquals("slack", doc.metadata["channel"]) {
                "channel이 있으면 메타데이터에 포함되어야 한다"
            }
        }

        @Test
        fun `channel이 null이면 메타데이터에 포함되지 않는다`() {
            val candidate = buildCandidate(channel = null)

            val doc = candidate.toDocuments().first()

            assertTrue(!doc.metadata.containsKey("channel")) {
                "channel이 null이면 메타데이터에 포함되지 않아야 한다"
            }
        }

        @Test
        fun `청커가 있으면 청크된 결과를 반환한다`() {
            val candidate = buildCandidate()
            val chunk1 = Document("chunk-1", "청크 내용 1", emptyMap())
            val chunk2 = Document("chunk-2", "청크 내용 2", emptyMap())
            val chunker = mockk<DocumentChunker>()
            every { chunker.chunk(any<Document>()) } returns listOf(chunk1, chunk2)

            val docs = candidate.toDocuments(chunker = chunker)

            assertEquals(2, docs.size) { "청커가 있으면 청크 결과가 반환되어야 한다" }
            assertEquals("chunk-1", docs[0].id) { "첫 번째 청크 ID가 일치해야 한다" }
            assertEquals("chunk-2", docs[1].id) { "두 번째 청크 ID가 일치해야 한다" }
        }

        @Test
        fun `청커가 없으면 단일 Document를 반환한다`() {
            val candidate = buildCandidate()

            val docs = candidate.toDocuments(chunker = null)

            assertEquals(1, docs.size) { "청커가 null이면 단일 Document가 반환되어야 한다" }
        }
    }

    // ---- 헬퍼 팩토리 ----

    private fun buildPersona(
        systemPrompt: String,
        promptTemplateId: String?,
        responseGuideline: String? = null
    ): Persona = Persona(
        id = "persona-test",
        name = "테스트 페르소나",
        systemPrompt = systemPrompt,
        promptTemplateId = promptTemplateId,
        responseGuideline = responseGuideline
    )

    private fun buildActiveVersion(content: String): PromptVersion = PromptVersion(
        id = "ver-1",
        templateId = "tmpl-1",
        version = 1,
        content = content,
        status = VersionStatus.ACTIVE
    )

    private fun buildCandidate(
        runId: String = "run-1",
        userId: String = "user-1",
        sessionId: String? = null,
        channel: String? = null,
        query: String = "테스트 쿼리",
        response: String = "테스트 응답"
    ): RagIngestionCandidate = RagIngestionCandidate(
        runId = runId,
        userId = userId,
        sessionId = sessionId,
        channel = channel,
        query = query,
        response = response,
        capturedAt = Instant.parse("2024-06-01T12:00:00Z")
    )
}

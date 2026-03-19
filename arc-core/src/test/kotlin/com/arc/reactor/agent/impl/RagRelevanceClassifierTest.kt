package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RagRelevanceClassifier에 대한 테스트.
 *
 * RAG 검색 필요 여부를 키워드 기반으로 빠르게 판단하는 분류기를 검증한다.
 */
class RagRelevanceClassifierTest {

    @Test
    fun `단순 수학 질문일 때 RAG를 생략해야 한다`() {
        val command = command("1+1은?")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Simple math should not trigger RAG retrieval"
        )
    }

    @Test
    fun `일반 지식 질문일 때 RAG를 생략해야 한다`() {
        val command = command("REST API가 뭐야?")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "General knowledge question should not trigger RAG retrieval"
        )
    }

    @Test
    fun `인사말일 때 RAG를 생략해야 한다`() {
        val command = command("안녕하세요")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Greeting should not trigger RAG retrieval"
        )
    }

    @Test
    fun `빈 프롬프트일 때 RAG를 생략해야 한다`() {
        val command = command("")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Empty prompt should not trigger RAG retrieval"
        )
    }

    @Test
    fun `지식 쿼리 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("Guard 파이프라인 문서 알려줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Knowledge query with '문서' keyword should trigger RAG retrieval"
        )
    }

    @Test
    fun `confluence 키워드만 있으면 RAG를 생략해야 한다 — 도구 라우팅 우선`() {
        val command = command("confluence에서 환불 정책 찾아줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Confluence query should skip RAG — tool routing handles it"
        )
    }

    @Test
    fun `wiki 키워드만 있으면 RAG를 생략해야 한다 — 도구 라우팅 우선`() {
        val command = command("위키에서 최근 변경된 페이지 보여줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Wiki query should skip RAG — tool routing handles it"
        )
    }

    @Test
    fun `confluence와 다른 지식 키워드가 함께 있으면 RAG를 실행해야 한다`() {
        val command = command("confluence 아키텍처 문서 찾아줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Confluence + '아키텍처' + '문서' should trigger RAG via knowledge keywords"
        )
    }

    @Test
    fun `knowledge 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("knowledge base에서 API 인증 방법 검색해줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Knowledge base query should trigger RAG retrieval"
        )
    }

    @Test
    fun `가이드 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("배포 가이드 알려줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Guide keyword should trigger RAG retrieval"
        )
    }

    @Test
    fun `Jira 워크스페이스 쿼리일 때 RAG를 생략해야 한다`() {
        val command = command("Jira 이슈 보여줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Jira workspace query should not trigger RAG retrieval"
        )
    }

    @Test
    fun `Bitbucket PR 쿼리일 때 RAG를 생략해야 한다`() {
        val command = command("bitbucket PR 목록 보여줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Bitbucket workspace query should not trigger RAG retrieval"
        )
    }

    @Test
    fun `브리핑 쿼리일 때 RAG를 생략해야 한다`() {
        val command = command("아침 브리핑 해줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Briefing workspace query should not trigger RAG retrieval"
        )
    }

    @Test
    fun `swagger 쿼리일 때 RAG를 생략해야 한다`() {
        val command = command("swagger 스펙 보여줘")
        assertFalse(
            RagRelevanceClassifier.isRagRequired(command),
            "Swagger workspace query should not trigger RAG retrieval"
        )
    }

    @Test
    fun `ragRequired 메타데이터가 true이면 항상 RAG를 실행해야 한다`() {
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "1+1은?",
            metadata = mapOf("ragRequired" to true)
        )
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "ragRequired=true metadata should always trigger RAG retrieval"
        )
    }

    @Test
    fun `ragFilters 메타데이터가 있으면 RAG를 실행해야 한다`() {
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "Show docs",
            metadata = mapOf("ragFilters" to mapOf("source" to "confluence"))
        )
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "ragFilters metadata should trigger RAG retrieval"
        )
    }

    @Test
    fun `rag filter 접두사 메타데이터가 있으면 RAG를 실행해야 한다`() {
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "Show data",
            metadata = mapOf("rag.filter.space" to "ENG")
        )
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "rag.filter.* metadata should trigger RAG retrieval"
        )
    }

    @Test
    fun `사내 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("사내 보안 규정이 어떻게 되나요?")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Internal policy query with '사내' should trigger RAG retrieval"
        )
    }

    @Test
    fun `런북 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("장애 대응 런북 확인해줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Runbook query should trigger RAG retrieval"
        )
    }

    @Test
    fun `policy 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("refund policy에 대해 알려줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Policy keyword should trigger RAG retrieval"
        )
    }

    @Test
    fun `procedure 키워드가 포함되면 RAG를 실행해야 한다`() {
        val command = command("deployment procedure를 알려줘")
        assertTrue(
            RagRelevanceClassifier.isRagRequired(command),
            "Procedure keyword should trigger RAG retrieval"
        )
    }

    @Test
    fun `일반적 기술 질문에 자주 쓰이는 단어는 RAG를 트리거하지 않아야 한다`() {
        val generalQuestions = listOf(
            "다음 단계는 뭐야?",
            "폴더 구조 알려줘",
            "클래스 설계 도와줘",
            "디자인 패턴이 뭐야?",
        )
        generalQuestions.forEach { prompt ->
            assertFalse(
                RagRelevanceClassifier.isRagRequired(command(prompt)),
                "General question '$prompt' should NOT trigger RAG retrieval"
            )
        }
    }

    @Test
    fun `기술 아키텍처 키워드가 포함되면 RAG를 실행해야 한다`() {
        val architectureQueries = listOf(
            "Guard 파이프라인은 몇 단계야?",
            "시스템 아키텍처 설명해줘",
            "ReAct 루프 동작 원리가 뭐야?",
            "도구 실행 메커니즘 알려줘",
            "에이전트 작동 방식 설명해줘",
        )
        architectureQueries.forEach { prompt ->
            assertTrue(
                RagRelevanceClassifier.isRagRequired(command(prompt)),
                "Architecture query '$prompt' should trigger RAG retrieval"
            )
        }
    }

    private fun command(userPrompt: String): AgentCommand =
        AgentCommand(systemPrompt = "sys", userPrompt = userPrompt)
}

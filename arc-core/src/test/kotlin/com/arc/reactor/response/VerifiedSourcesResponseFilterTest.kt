package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.response.impl.VerifiedSourcesResponseFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerifiedSourcesResponseFilterTest {

    private val filter = VerifiedSourcesResponseFilter()

    @Test
    fun `should append normalized sources block`() = runTest {
        val result = filter.filter(
            content = "정책은 승인된 문서에 따릅니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "정책 알려줘"),
                toolsUsed = listOf("confluence_answer_question"),
                verifiedSources = listOf(
                    VerifiedSource(
                        title = "개발팀 Home",
                        url = "https://example.atlassian.net/wiki/spaces/DEV/pages/1"
                    )
                ),
                durationMs = 120
            )
        )

        assertTrue(result.contains("\n\n출처\n- [개발팀 Home](")) {
            "Response should end with a sources block"
        }
    }

    @Test
    fun `should block unverified workspace answer`() = runTest {
        val result = filter.filter(
            content = "배포 정책은 매주 수요일입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "배포 정책 알려줘"),
                toolsUsed = listOf("confluence_answer_question"),
                verifiedSources = emptyList(),
                durationMs = 90
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Unverified workspace answers should be replaced with a verification failure message"
        }
        assertTrue(result.endsWith("출처\n- 검증된 출처를 찾지 못했습니다.")) {
            "Blocked answer should still include the required sources footer"
        }
    }

    @Test
    fun `should block unverified informational answer without workspace tool`() = runTest {
        val result = filter.filter(
            content = "정답은 4입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "2+2가 뭐야?"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 75
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Informational answers without verified sources should fail closed"
        }
    }

    @Test
    fun `should keep casual reply and append empty footer`() = runTest {
        val result = filter.filter(
            content = "안녕하세요.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "안녕"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 40
            )
        )

        assertTrue(result.startsWith("안녕하세요.")) {
            "Casual prompts should not be replaced with a verification failure"
        }
        assertTrue(result.endsWith("출처\n- 검증된 출처를 찾지 못했습니다.")) {
            "Casual replies should still include the mandatory footer"
        }
    }

    @Test
    fun `should skip non text responses`() = runTest {
        val result = filter.filter(
            content = "{\"ok\":true}",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "json please",
                    responseFormat = ResponseFormat.JSON
                ),
                toolsUsed = listOf("confluence_answer_question"),
                verifiedSources = listOf(VerifiedSource(title = "Doc", url = "https://example.com/doc")),
                durationMs = 80
            )
        )

        assertEquals("{\"ok\":true}", result)
    }
}

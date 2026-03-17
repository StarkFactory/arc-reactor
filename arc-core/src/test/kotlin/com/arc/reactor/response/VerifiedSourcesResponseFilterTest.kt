package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.response.impl.VerifiedSourcesResponseFilter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * VerifiedSourcesResponseFilter에 대한 테스트.
 *
 * 검증된 출처 응답 필터의 동작을 검증합니다.
 */
class VerifiedSourcesResponseFilterTest {

    private val filter = VerifiedSourcesResponseFilter()

    @Test
    fun `append normalized sources block해야 한다`() = runTest {
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
    fun `allow workspace answer when tool was called and content exists해야 한다`() = runTest {
        val result = filter.filter(
            content = "배포 정책은 매주 수요일입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "배포 정책 알려줘"),
                toolsUsed = listOf("confluence_answer_question"),
                verifiedSources = emptyList(),
                durationMs = 90
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "When a tool was called and returned content, the response should not be blocked"
        }
        assertTrue(result.contains("배포 정책은 매주 수요일입니다.")) {
            "Original content should be preserved when a tool was called"
        }
    }

    @Test
    fun `block unverified workspace answer when no tool called해야 한다`() = runTest {
        val result = filter.filter(
            content = "배포 정책은 매주 수요일입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "배포 정책 알려줘"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 90
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Unverified workspace answers without tool calls should be replaced with a verification failure message"
        }
        assertTrue(result.endsWith("출처\n- 검증된 출처를 찾지 못했습니다.")) {
            "Blocked answer should still include the required sources footer"
        }
    }

    @Test
    fun `keep read only mutation refusal even without verified sources해야 한다`() = runTest {
        val result = filter.filter(
            content = "죄송합니다. 해당 워크스페이스는 읽기 전용이므로 요청하신 변경 작업을 수행할 수 없습니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "Jira 이슈 DEV-51 상태를 진행 중으로 바꿔줘."),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 70
            )
        )

        assertTrue(result.startsWith("죄송합니다. 해당 워크스페이스는 읽기 전용")) {
            "Read-only mutation refusals should not be replaced by verification-failure text"
        }
        assertEquals(
            "죄송합니다. 해당 워크스페이스는 읽기 전용이므로 요청하신 변경 작업을 수행할 수 없습니다.",
            result
        ) {
            "Read-only mutation refusals should stay concise without an empty sources footer"
        }
    }

    @Test
    fun `keep identity resolution refusal even without verified sources해야 한다`() = runTest {
        val result = filter.filter(
            content = "요청자 계정을 Jira 사용자로 확인할 수 없어 개인화 조회를 확정할 수 없습니다. requesterEmail과 Atlassian 사용자 매핑을 확인해 주세요.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "내가 담당한 Jira 오픈 이슈 목록을 보여줘."),
                toolsUsed = listOf("jira_my_open_issues"),
                verifiedSources = emptyList(),
                durationMs = 70
            )
        )

        assertEquals(
            "요청자 계정을 Jira 사용자로 확인할 수 없어 개인화 조회를 확정할 수 없습니다. requesterEmail과 Atlassian 사용자 매핑을 확인해 주세요.",
            result
        ) {
            "Identity-resolution refusals should stay visible without an empty sources footer"
        }
    }

    @Test
    fun `treat swagger spec tools as verified workspace tools해야 한다`() = runTest {
        val result = filter.filter(
            content = "공식 업로드 엔드포인트는 POST /pet/{petId}/uploadImage 입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "pet 이미지 업로드 API 알려줘"),
                toolsUsed = listOf("spec_detail"),
                verifiedSources = listOf(
                    VerifiedSource(
                        title = "Swagger Petstore OpenAPI",
                        url = "https://petstore3.swagger.io/api/v3/openapi.json",
                        toolName = "spec_detail"
                    )
                ),
                durationMs = 95
            )
        )

        assertTrue(result.contains("POST /pet/{petId}/uploadImage")) {
            "Verified Swagger answer should preserve the model response"
        }
        assertTrue(result.contains("[Swagger Petstore OpenAPI](https://petstore3.swagger.io/api/v3/openapi.json)")) {
            "Swagger-backed answers should include the verified source footer"
        }
    }

    @Test
    fun `block unverified workspace question without tool해야 한다`() = runTest {
        val result = filter.filter(
            content = "배포 정책은 매주 수요일입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "우리팀 배포 정책이 뭐야?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 75
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Workspace questions without verified sources should fail closed"
        }
    }

    @Test
    fun `allow general question without workspace context해야 한다`() = runTest {
        val result = filter.filter(
            content = "오늘 서울 날씨는 맑겠습니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "오늘 날씨 어때?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 50
            )
        )

        assertTrue(!result.contains("검증 가능한 출처를 찾지 못해")) {
            "General questions without workspace context should not be blocked"
        }
    }

    @Test
    fun `allow how are you style greetings해야 한다`() = runTest {
        val result = filter.filter(
            content = "I'm doing well, thanks!",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "how are you?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 40
            )
        )

        assertTrue(!result.contains("couldn't verify")) {
            "General English questions should not be blocked"
        }
    }

    @Test
    fun `keep casual reply without empty sources footer해야 한다`() = runTest {
        val result = filter.filter(
            content = "안녕하세요.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "안녕"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 40
            )
        )

        assertEquals("안녕하세요.", result) {
            "Casual prompts should not have an empty sources footer appended"
        }
    }

    @Test
    fun `keep casual Korean greetings unblocked해야 한다`() = runTest {
        val greetings = listOf("안녕하세요", "안녕하세요!", "반갑습니다")
        for (greeting in greetings) {
            val result = filter.filter(
                content = "안녕하세요! 무엇을 도와드릴까요?",
                context = ResponseFilterContext(
                    command = AgentCommand(
                        systemPrompt = "sys",
                        userPrompt = greeting
                    ),
                    toolsUsed = emptyList(),
                    verifiedSources = emptyList(),
                    durationMs = 40
                )
            )

            assertTrue(result.startsWith("안녕하세요!")) {
                "Casual greeting '$greeting' should not be blocked"
            }
        }
    }

    @Test
    fun `keep casual Korean acknowledgements unblocked해야 한다`() = runTest {
        val acks = listOf("네", "알겠습니다", "좋아", "고마워요")
        for (ack in acks) {
            val result = filter.filter(
                content = "다른 도움이 필요하시면 말씀해 주세요.",
                context = ResponseFilterContext(
                    command = AgentCommand(
                        systemPrompt = "sys",
                        userPrompt = ack
                    ),
                    toolsUsed = emptyList(),
                    verifiedSources = emptyList(),
                    durationMs = 40
                )
            )

            assertTrue(!result.contains("검증 가능한 출처를 찾지 못해")) {
                "Acknowledgement '$ack' should not be blocked"
            }
        }
    }

    @Test
    fun `skip non text responses해야 한다`() = runTest {
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

    @Test
    fun `appending normalized footer 전에 strip leaked tool code and generated sources block해야 한다`() = runTest {
        val result = filter.filter(
            content = """
                ```tool_code
                {"tool_name":"jira_daily_briefing","project_key":"BACKEND"}
                ```

                BACKEND 프로젝트 브리핑입니다.

                Sources:
                * Jira project BACKEND: https://example.atlassian.net/issues/?jql=project=BACKEND
            """.trimIndent(),
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "BACKEND 프로젝트 기준으로 오늘의 Jira 브리핑을 만들어줘."),
                toolsUsed = listOf("jira_daily_briefing"),
                verifiedSources = listOf(
                    VerifiedSource(title = "BACKEND-1", url = "https://example.atlassian.net/browse/BACKEND-1")
                ),
                durationMs = 85
            )
        )

        assertTrue(!result.contains("```tool_code")) {
            "Internal tool planning blocks should be removed from final responses"
        }
        assertTrue(!result.contains("\nSources:\n")) {
            "Generated English sources blocks should be removed before the normalized footer is appended"
        }
        assertTrue(result.contains("BACKEND 프로젝트 브리핑입니다.")) {
            "The user-facing summary should be preserved"
        }
        assertTrue(result.endsWith("출처\n- [BACKEND-1](https://example.atlassian.net/browse/BACKEND-1)")) {
            "The normalized footer should still be appended"
        }
    }

    @Test
    fun `sanitized content becomes empty일 때 return verified fallback message해야 한다`() = runTest {
        val result = filter.filter(
            content = """
                ```tool_code
                {"tool_name":"jira_blocker_digest","project_key":"DEV"}
                ```
            """.trimIndent(),
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "DEV blocker 보여줘"),
                toolsUsed = listOf("jira_blocker_digest"),
                verifiedSources = listOf(
                    VerifiedSource(title = "DEV-1", url = "https://example.atlassian.net/browse/DEV-1")
                ),
                durationMs = 70
            )
        )

        assertTrue(result.contains("승인된 도구 결과를 확인했지만")) {
            "Blank sanitized content should fall back to a short verified message"
        }
        assertTrue(result.contains("[DEV-1](https://example.atlassian.net/browse/DEV-1)")) {
            "Fallback verified responses should still include the source footer"
        }
    }

    @Test
    fun `strip inline and emphasized sources sections from tool output해야 한다`() = runTest {
        val result = filter.filter(
            content = """
                문서를 찾지 못했습니다.
                Sources: https://example.atlassian.net/wiki/search?text=weekly

                **출처:**
                * Jira project DEV: https://example.atlassian.net/issues/?jql=project=DEV
            """.trimIndent(),
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "weekly 문서 찾아줘"),
                toolsUsed = listOf("confluence_search_by_text"),
                verifiedSources = listOf(
                    VerifiedSource(title = "Confluence search for 'weekly'", url = "https://example.atlassian.net/wiki/search?text=weekly")
                ),
                durationMs = 50
            )
        )

        assertTrue(!result.contains("Sources: https://")) {
            "Inline tool-generated sources lines should be removed"
        }
        assertTrue(!result.contains("**출처:**")) {
            "Emphasized sources headings should be removed"
        }
        assertTrue(result.endsWith("출처\n- [Confluence search for 'weekly'](https://example.atlassian.net/wiki/search?text=weekly)")) {
            "The normalized footer should still be appended after stripping inline sources"
        }
    }

    @Test
    fun `internal read tools without links에 대해 not append empty sources footer해야 한다`() = runTest {
        val result = filter.filter(
            content = "저장된 briefing profile은 default 하나입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "저장된 briefing profile 목록을 보여줘."),
                toolsUsed = listOf("work_list_briefing_profiles"),
                verifiedSources = emptyList(),
                durationMs = 40
            )
        )

        assertEquals("저장된 briefing profile은 default 하나입니다.", result) {
            "Internal read tools without linkable sources should not get an empty footer"
        }
    }
}

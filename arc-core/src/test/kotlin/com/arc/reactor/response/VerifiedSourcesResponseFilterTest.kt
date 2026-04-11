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

    /**
     * R345 regression: LLM이 본문 중간에 서술적으로 `Sources:` 헤딩을 작성하고 그 뒤에
     * URL 없는 설명 + 결론 본문이 이어지는 경우, 기존 `matches.first()` 기반 절단이
     * **본문 결론 전체를 substring으로 잘라내던** user-visible 응답 품질 저하를 수정했는지
     * 검증. 수정 후에는 후보 match 이후 content에 실제 URL/markdown-link 패턴이 없으면
     * 본문을 보존해야 한다.
     */
    @Test
    fun `R345 본문 중간 서술적 Sources 언급은 본문을 보존해야 한다`() = runTest {
        val result = filter.filter(
            content = """
                The approach I used was:

                Sources:
                - the internal knowledge base (not linked here)
                - previous team discussions

                Given the above, my conclusion is that we should ship feature X next week.
            """.trimIndent(),
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "설명해줘"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 100
            )
        )

        assertTrue(result.contains("Given the above, my conclusion is that we should ship feature X")) {
            "R345: Sources 헤딩 뒤에 URL이 없으면 본문 결론이 보존되어야 한다. " +
                "실제 결과:\n$result"
        }
    }

    @Test
    fun `R345 본문 중간 서술적 출처 한글 언급도 본문 보존`() = runTest {
        val result = filter.filter(
            content = """
                접근 방식은 다음과 같습니다.

                출처:
                - 내부 지식 베이스 (비공개 링크)
                - 팀 회의록

                따라서 다음 주 릴리스는 기능 X를 먼저 배포해야 합니다.
            """.trimIndent(),
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "설명해줘"),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 100
            )
        )

        assertTrue(result.contains("따라서 다음 주 릴리스는 기능 X를 먼저 배포해야 합니다")) {
            "R345: 한글 '출처:' 헤딩 뒤에 URL이 없으면 본문 결론이 보존되어야 한다. " +
                "실제 결과:\n$result"
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

    @Test
    fun `allow general knowledge answer without blocking해야 한다`() = runTest {
        val result = filter.filter(
            content = "프랑스의 수도는 파리입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "프랑스의 수도는 어디야?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 50
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "General knowledge questions should not be blocked by the verified sources filter"
        }
        assertTrue(result.contains("프랑스의 수도는 파리입니다.")) {
            "General knowledge answers should be preserved"
        }
    }

    @Test
    fun `allow simple math answer without blocking해야 한다`() = runTest {
        val result = filter.filter(
            content = "1+1은 2입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "1+1은?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 30
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Simple math questions should not be blocked"
        }
    }

    @Test
    fun `block workspace question without tool even with broad keyword해야 한다`() = runTest {
        val result = filter.filter(
            content = "Jira 이슈 목록입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "Jira 이슈 보여줘"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 60
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Workspace questions with strict workspace keywords should still be blocked when no tool was called"
        }
    }

    @Test
    fun `allow general question containing broad verification keyword해야 한다`() = runTest {
        val result = filter.filter(
            content = "REST API는 Representational State Transfer의 약자입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "REST API가 뭐야?"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 50
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "General questions about broad topics like 'API' should not be blocked when no workspace tool was called"
        }
    }

    @Test
    fun `allow general document writing question해야 한다`() = runTest {
        val result = filter.filter(
            content = "문서 작성 시 목차를 먼저 구성하는 것이 좋습니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "문서 작성법 알려줘"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 50
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "General questions about document writing should not be blocked"
        }
    }

    @Test
    fun `block deadline workspace question without tool해야 한다`() = runTest {
        val result = filter.filter(
            content = "이번 주 마감인 이슈는 3건입니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "이번 주 마감 Jira 이슈 알려줘"
                ),
                toolsUsed = emptyList(),
                verifiedSources = emptyList(),
                durationMs = 60
            )
        )

        assertTrue(result.contains("검증 가능한 출처를 찾지 못해")) {
            "Deadline workspace questions without tool calls should be blocked"
        }
    }

    @Test
    fun `allow onboarding guide search when tool was called해야 한다`() = runTest {
        val result = filter.filter(
            content = "온보딩 가이드를 찾았습니다.",
            context = ResponseFilterContext(
                command = AgentCommand(
                    systemPrompt = "sys",
                    userPrompt = "온보딩 가이드 찾아줘"
                ),
                toolsUsed = listOf("confluence_answer_question"),
                verifiedSources = emptyList(),
                durationMs = 80
            )
        )

        assertFalse(result.contains("검증 가능한 출처를 찾지 못해")) {
            "When a tool was called and returned content, the response should not be blocked"
        }
    }

    // ── R192: thin-body + toolInsights 주입 ──

    @Test
    fun `R192 thin body with toolInsights should append insight block`() = runTest {
        // 본문이 100자 미만 + toolInsights 존재 → 💡 인사이트 블록 자동 주입
        val result = filter.filter(
            content = "내 PR 현황을 정리해 드릴게요.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "내 PR 현황 알려줘"),
                toolsUsed = listOf("bitbucket_my_authored_prs"),
                verifiedSources = listOf(
                    VerifiedSource("ihunet/repo1", "https://bitbucket.org/ihunet/repo1")
                ),
                toolInsights = listOf(
                    "본인이 작성한 OPEN PR 0건 (검토 대기 없음) — 스캔: 20개 레포",
                    "일부 레포(20개) 조회 실패: repo1, repo2"
                ),
                durationMs = 100
            )
        )

        assertTrue(result.contains("💡 인사이트")) {
            "Thin body with toolInsights should inject a 💡 insights block, got: ${result.take(200)}"
        }
        assertTrue(result.contains("본인이 작성한 OPEN PR 0건")) {
            "First toolInsight should be included"
        }
        assertTrue(result.contains("일부 레포(20개) 조회 실패")) {
            "Second toolInsight should be included"
        }
    }

    // ── R193: Confluence 합성 인사이트 fallback (verifiedSources 기반) ──

    @Test
    fun `R193 thin body without toolInsights should synthesize from verifiedSources`() = runTest {
        // 본문 100자 미만 + toolInsights 없음 + verifiedSources 존재 → 합성 인사이트 주입
        val result = filter.filter(
            content = "릴리즈 노트를 찾아드릴게요.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "릴리즈 노트 최신"),
                toolsUsed = listOf("confluence_search_by_text"),
                verifiedSources = listOf(
                    VerifiedSource("릴리즈 노트 — 2026-04-09", "https://example.com/rel-0409"),
                    VerifiedSource("릴리즈 노트 — 2026-04-08", "https://example.com/rel-0408"),
                    VerifiedSource("릴리즈 노트 — 2026-04-07", "https://example.com/rel-0407")
                ),
                toolInsights = emptyList(),
                durationMs = 120
            )
        )

        assertTrue(result.contains("💡 인사이트")) {
            "Thin body with verifiedSources but no toolInsights should inject synthetic insights"
        }
        assertTrue(result.contains("검색 결과: 총 3건")) {
            "Synthetic insight should include verified source count, got: ${result.take(300)}"
        }
        assertTrue(result.contains("릴리즈 노트 — 2026-04-09")) {
            "Synthetic insight should include top source titles"
        }
    }

    @Test
    fun `R192 thin body without any insights or sources should not inject`() = runTest {
        // 본문 100자 미만 + toolInsights 없음 + verifiedSources 없음 → 주입 안 함
        val result = filter.filter(
            content = "요청하신 데이터를 찾지 못했습니다.",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "정책 조회"),
                toolsUsed = listOf("jira_search_issues"),
                verifiedSources = emptyList(),
                toolInsights = emptyList(),
                durationMs = 80
            )
        )

        assertFalse(result.contains("💡 인사이트")) {
            "Thin body with empty insights/sources should not inject synthetic insight"
        }
    }

    // ── R194: insight-poor body (정상 길이지만 마커 부재) ──

    @Test
    fun `R194 insight poor long body should inject minimal insight`() = runTest {
        // 본문 100자 이상 + 💡/권장/분석 등 마커 전무 + toolInsights 존재 → 주입
        val longContentNoMarkers = "배포 가이드를 찾아봤습니다. 검색 결과 몇 가지 관련 문서가 있는데요, " +
            "다국어 구축 제안서와 STG 서버 구성 문서가 배포와 관련된 내용을 포함하고 있습니다. " +
            "혹시 특정 시스템에 대한 배포 가이드가 필요하시면 더 구체적으로 알려주시겠어요?"
        val result = filter.filter(
            content = longContentNoMarkers,
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "배포 가이드 어디 있어?"),
                toolsUsed = listOf("confluence_search_by_text"),
                verifiedSources = listOf(
                    VerifiedSource("다국어(i18n) 구축 제안서", "https://example.com/i18n"),
                    VerifiedSource("STG 서버 구성", "https://example.com/stg")
                ),
                toolInsights = emptyList(),
                durationMs = 200
            )
        )

        assertTrue(longContentNoMarkers.length >= 100) { "Pre-check: content should be above thin-body threshold" }
        assertTrue(result.contains("💡 인사이트")) {
            "Insight-poor long body should receive a 💡 insights block"
        }
        assertTrue(result.contains("검색 결과: 총 2건")) {
            "Insight-poor should use synthetic insight when toolInsights empty"
        }
    }

    @Test
    fun `R194 content with existing insight marker should NOT receive injection`() = runTest {
        // 이미 💡 마커 포함 → 추가 주입 없음 (중복 방지)
        val contentWithMarker = "배포 가이드 검색 결과입니다. " +
            "💡 인사이트: '배포 가이드'라는 정확한 제목의 문서는 없지만 관련 문서가 5건 있습니다."
        val result = filter.filter(
            content = contentWithMarker,
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "배포 가이드 어디 있어?"),
                toolsUsed = listOf("confluence_search_by_text"),
                verifiedSources = listOf(
                    VerifiedSource("배포 문서 1", "https://example.com/deploy1")
                ),
                toolInsights = listOf("서버 계산 인사이트"),
                durationMs = 150
            )
        )

        // 💡 marker는 content에 1번, sources block은 별도 추가
        // 추가 "💡 인사이트" 블록이 2번 이상 나타나지 않아야 함
        val insightBlockCount = result.split("💡 인사이트").size - 1
        assertTrue(insightBlockCount <= 1) {
            "Existing 💡 marker should prevent duplicate injection, found $insightBlockCount blocks"
        }
    }

    @Test
    fun `R194 content with 건수 keyword should NOT trigger insight poor injection`() = runTest {
        // "건" 단어는 INSIGHT_MARKER_PATTERNS에 포함 → 주입 안 함
        val contentWithKeyword = "현재 진행 중인 이슈 3건을 확인했습니다. " +
            "HRFW-5695, LND-77, SETTING-104 이슈가 담당자에게 할당되어 있습니다. " +
            "마감일은 각각 다음 주에 도래할 예정입니다."
        val result = filter.filter(
            content = contentWithKeyword,
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "내 이슈 보여줘"),
                toolsUsed = listOf("jira_my_open_issues"),
                verifiedSources = listOf(
                    VerifiedSource("HRFW-5695", "https://example.com/HRFW-5695")
                ),
                toolInsights = listOf("총 3건 이슈 확인"),
                durationMs = 200
            )
        )

        // content already has "건", "마감" → hasInsightMarker returns true → no extra injection
        // content 본문에는 "인사이트" 섹션이 원래부터 없어야 함
        assertFalse(result.substringBefore("출처").contains("💡 인사이트")) {
            "Content with existing insight keywords should not receive automatic 💡 인사이트 block"
        }
    }

    // ── R195: toolInsights가 ResponseFilterContext를 통해 전달되는지 확인 ──

    @Test
    fun `R195 empty content with toolInsights should use fallback message with insights`() = runTest {
        // 완전히 빈 content + toolInsights 존재 → buildFallbackVerifiedResponse + 인사이트 포함
        val result = filter.filter(
            content = "",
            context = ResponseFilterContext(
                command = AgentCommand(systemPrompt = "sys", userPrompt = "내 PR 상황"),
                toolsUsed = listOf("bitbucket_my_authored_prs"),
                verifiedSources = listOf(
                    VerifiedSource("ihunet/repo", "https://bitbucket.org/ihunet/repo")
                ),
                toolInsights = listOf("24시간+ 미업데이트: 3건", "리뷰어 미지정: 1건"),
                durationMs = 100
            )
        )

        assertTrue(result.contains("💡 인사이트")) {
            "Empty content fallback with toolInsights should include 💡 insights section"
        }
        assertTrue(result.contains("24시간+ 미업데이트: 3건")) {
            "Fallback insight should include tool-calculated insights"
        }
    }
}

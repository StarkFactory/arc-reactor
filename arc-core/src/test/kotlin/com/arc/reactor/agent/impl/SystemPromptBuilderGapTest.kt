package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * SystemPromptBuilder 커버리지 보강 테스트.
 *
 * 기존 SystemPromptBuilderTest에서 미처 다루지 못한 케이스:
 * - userMemoryContext 주입
 * - YAML 응답 형식
 * - SystemPromptPostProcessor 위임
 * - 일반(비워크스페이스) 그라운딩 규칙
 * - 스탠드업 / Jira 일일 브리핑 / Jira 블로커 / Bitbucket 스태일·브랜치 도구 강제
 */
class SystemPromptBuilderGapTest {

    private val builder = SystemPromptBuilder()

    // ── 사용자 메모리 컨텍스트 ──────────────────────────────────────────────

    @Nested
    inner class UserMemoryContext {

        @Test
        fun `userMemoryContext를 basePrompt에 주입해야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userMemoryContext = "사용자 선호: 한국어 답변"
            )

            assertTrue(prompt.contains("[User Context]")) {
                "userMemoryContext가 있으면 [User Context] 섹션이 포함되어야 한다"
            }
            assertTrue(prompt.contains("사용자 선호: 한국어 답변")) {
                "userMemoryContext 내용이 프롬프트에 포함되어야 한다"
            }
            assertTrue(prompt.startsWith("You are helpful.")) {
                "basePrompt는 항상 프롬프트의 시작에 있어야 한다"
            }
        }

        @Test
        fun `userMemoryContext가 null이면 User Context 섹션을 포함하지 않아야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userMemoryContext = null
            )

            assertFalse(prompt.contains("[User Context]")) {
                "userMemoryContext가 null이면 [User Context] 섹션이 없어야 한다"
            }
        }

        @Test
        fun `userMemoryContext와 RAG context가 동시에 있어도 모두 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = "retrieved fact",
                responseFormat = ResponseFormat.TEXT,
                userMemoryContext = "사용자 이름: Alice"
            )

            assertTrue(prompt.contains("[User Context]")) {
                "userMemoryContext가 있으면 [User Context] 섹션이 포함되어야 한다"
            }
            assertTrue(prompt.contains("[Retrieved Context]")) {
                "RAG context가 있으면 [Retrieved Context] 섹션이 포함되어야 한다"
            }
            assertTrue(prompt.contains("사용자 이름: Alice")) {
                "userMemoryContext 내용이 프롬프트에 포함되어야 한다"
            }
            assertTrue(prompt.contains("retrieved fact")) {
                "RAG context 내용이 프롬프트에 포함되어야 한다"
            }
        }
    }

    // ── YAML 응답 형식 ────────────────────────────────────────────────────

    @Nested
    inner class YamlResponseFormat {

        @Test
        fun `YAML 형식일 때 Response Format 섹션과 YAML 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.YAML,
                responseSchema = null
            )

            assertTrue(prompt.contains("[Response Format]")) {
                "YAML 형식은 [Response Format] 섹션을 포함해야 한다"
            }
            assertTrue(prompt.contains("You MUST respond with valid YAML only")) {
                "YAML 형식 지시 문구가 포함되어야 한다"
            }
            assertFalse(prompt.contains("You MUST respond with valid JSON only")) {
                "YAML 형식일 때 JSON 지시 문구가 포함되면 안 된다"
            }
        }

        @Test
        fun `YAML 형식에 스키마가 있으면 스키마가 포함되어야 한다`() {
            val schema = "name: string\nage: integer"
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.YAML,
                responseSchema = schema
            )

            assertTrue(prompt.contains(schema)) {
                "YAML 응답 스키마가 프롬프트에 포함되어야 한다"
            }
            assertTrue(prompt.contains("Expected YAML structure:")) {
                "YAML 스키마 헤더가 포함되어야 한다"
            }
        }

        @Test
        fun `YAML 형식에 스키마가 없으면 스키마 헤더가 없어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.YAML,
                responseSchema = null
            )

            assertFalse(prompt.contains("Expected YAML structure:")) {
                "YAML 스키마가 null이면 스키마 헤더가 없어야 한다"
            }
        }

        @Test
        fun `YAML 형식은 마크다운 코드 블록 금지 지시를 포함해야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.YAML
            )

            assertTrue(prompt.contains("no ```yaml")) {
                "YAML 형식은 마크다운 블록 금지 지시를 포함해야 한다"
            }
        }
    }

    // ── SystemPromptPostProcessor 위임 ────────────────────────────────────

    @Nested
    inner class PostProcessorDelegation {

        @Test
        fun `postProcessor가 있으면 최종 프롬프트에 적용되어야 한다`() {
            val postProcessor = SystemPromptPostProcessor { it + "\n<!-- CANARY:abc123 -->" }
            val builderWithProcessor = SystemPromptBuilder(postProcessor = postProcessor)

            val prompt = builderWithProcessor.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT
            )

            assertTrue(prompt.contains("<!-- CANARY:abc123 -->")) {
                "postProcessor가 반환한 내용이 최종 프롬프트에 포함되어야 한다"
            }
        }

        @Test
        fun `postProcessor가 null이면 원본 프롬프트가 그대로 반환되어야 한다`() {
            val builderNoProcessor = SystemPromptBuilder(postProcessor = null)

            val prompt = builderNoProcessor.build(
                basePrompt = "Base prompt content.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT
            )

            assertTrue(prompt.contains("Base prompt content.")) {
                "postProcessor 없이 basePrompt 내용이 그대로 반환되어야 한다"
            }
            assertFalse(prompt.contains("CANARY")) {
                "postProcessor가 null이면 canary 마커가 없어야 한다"
            }
        }

        @Test
        fun `postProcessor가 전체 프롬프트를 변환할 수 있어야 한다`() {
            val postProcessor = SystemPromptPostProcessor { "[MODIFIED]" }
            val builderWithProcessor = SystemPromptBuilder(postProcessor = postProcessor)

            val prompt = builderWithProcessor.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT
            )

            assertTrue(prompt == "[MODIFIED]") {
                "postProcessor가 전체 프롬프트를 교체할 수 있어야 한다"
            }
        }
    }

    // ── 일반(비워크스페이스) 그라운딩 규칙 ────────────────────────────────

    @Nested
    inner class GeneralGroundingRules {

        @Test
        fun `비워크스페이스 프롬프트에서는 일반 그라운딩 규칙이 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "15 곱하기 23은 얼마야?"
            )

            assertTrue(prompt.contains("[Grounding Rules]")) {
                "비워크스페이스 쿼리도 [Grounding Rules] 섹션을 포함해야 한다"
            }
            assertTrue(prompt.contains("Answer directly from your knowledge")) {
                "비워크스페이스 규칙은 직접 답변 지시를 포함해야 한다"
            }
        }

        @Test
        fun `userPrompt가 null이면 일반 그라운딩 규칙이 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = null
            )

            assertTrue(prompt.contains("[Grounding Rules]")) {
                "userPrompt가 없을 때도 [Grounding Rules] 섹션이 포함되어야 한다"
            }
            assertFalse(prompt.contains("GENERAL questions")) {
                "userPrompt=null이면 워크스페이스 전용 Grounding Rules가 아니어야 한다"
            }
        }

        @Test
        fun `비워크스페이스 쿼리에서는 workspace Grounding Rules가 없어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "Python에서 리스트 정렬하는 방법을 알려줘"
            )

            assertFalse(prompt.contains("WORKSPACE questions")) {
                "비워크스페이스 쿼리에는 WORKSPACE questions 지시가 없어야 한다"
            }
            assertFalse(prompt.contains("[Few-shot Examples")) {
                "비워크스페이스 쿼리에는 Few-shot Examples 섹션이 없어야 한다"
            }
        }
    }

    // ── 스탠드업 도구 강제 ────────────────────────────────────────────────

    @Nested
    inner class StandupToolForcing {

        @Test
        fun `standup 프롬프트에 대해 work_prepare_standup_update 호출 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "오늘 스탠드업 업데이트 초안을 만들어줘."
            )

            assertTrue(prompt.contains("MUST call `work_prepare_standup_update`")) {
                "standup 프롬프트는 work_prepare_standup_update 도구 강제 지시를 포함해야 한다"
            }
        }

        @Test
        fun `standup 영어 프롬프트에서도 강제 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "Prepare my daily standup update draft for today."
            )

            assertTrue(prompt.contains("MUST call `work_prepare_standup_update`")) {
                "영문 standup 프롬프트도 work_prepare_standup_update 강제 지시를 포함해야 한다"
            }
        }

        @Test
        fun `workspaceToolAlreadyCalled이면 standup 강제 지시가 없어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = "standup content",
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "스탠드업 업데이트 초안을 만들어줘.",
                workspaceToolAlreadyCalled = true
            )

            assertFalse(prompt.contains("MUST call `work_prepare_standup_update`")) {
                "workspaceToolAlreadyCalled=true이면 standup 강제 지시가 없어야 한다"
            }
        }
    }

    // ── Jira 일일 브리핑 도구 강제 ─────────────────────────────────────────

    @Nested
    inner class JiraDailyBriefingToolForcing {

        @Test
        fun `Jira daily briefing 프롬프트에 강제 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "DEV 프로젝트 Jira daily briefing을 만들어줘."
            )

            assertTrue(prompt.contains("MUST call") && prompt.contains("jira_daily_briefing")) {
                "Jira daily briefing 프롬프트는 jira_daily_briefing 도구 강제 지시를 포함해야 한다"
            }
        }
    }

    // ── Jira 블로커 도구 강제 ─────────────────────────────────────────────

    @Nested
    inner class JiraBlockerToolForcing {

        @Test
        fun `Jira blocker 프롬프트에 jira_blocker_digest 강제 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "DEV 프로젝트의 Jira blocker 이슈를 정리해줘."
            )

            assertTrue(prompt.contains("MUST call") && prompt.contains("jira_blocker_digest")) {
                "Jira blocker 프롬프트는 jira_blocker_digest 도구 강제 지시를 포함해야 한다"
            }
        }
    }

    // ── Jira 프로젝트 목록 도구 강제 ─────────────────────────────────────

    @Nested
    inner class JiraProjectListToolForcing {

        @Test
        fun `Jira project list 프롬프트에 jira_list_projects 강제 지시가 포함되어야 한다`() {
            // PROJECT_LIST_HINTS: "project list", "projects", "프로젝트 목록", "프로젝트 리스트"
            // JIRA_HINTS: "jira" 포함 → 두 힌트 셋이 모두 매칭되어야 jira_list_projects 강제 발동
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "접근 가능한 Jira projects 목록을 알려줘."
            )

            assertTrue(prompt.contains("MUST call") && prompt.contains("jira_list_projects")) {
                "Jira project list 프롬프트는 jira_list_projects 도구 강제 지시를 포함해야 한다"
            }
        }
    }

    // ── Bitbucket 스태일 PR 도구 강제 ─────────────────────────────────────

    @Nested
    inner class BitbucketStalePrToolForcing {

        @Test
        fun `Bitbucket stale PR 프롬프트에 bitbucket_stale_prs 강제 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "jarvis-project/dev 저장소의 stale PR 목록을 보여줘."
            )

            assertTrue(prompt.contains("MUST call") && prompt.contains("bitbucket_stale_prs")) {
                "Bitbucket stale PR 프롬프트는 bitbucket_stale_prs 도구 강제 지시를 포함해야 한다"
            }
        }
    }

    // ── Bitbucket 브랜치 도구 강제 ────────────────────────────────────────

    @Nested
    inner class BitbucketBranchToolForcing {

        @Test
        fun `Bitbucket branch list 프롬프트에 bitbucket_list_branches 강제 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                userPrompt = "jarvis-project/dev 저장소의 branch 목록을 출처와 함께 보여줘."
            )

            assertTrue(prompt.contains("MUST call") && prompt.contains("bitbucket_list_branches")) {
                "Bitbucket branch list 프롬프트는 bitbucket_list_branches 도구 강제 지시를 포함해야 한다"
            }
        }
    }

    // ── JSON 응답 형식 추가 케이스 ─────────────────────────────────────────

    @Nested
    inner class JsonResponseFormatAdditional {

        @Test
        fun `JSON 형식에 스키마가 없으면 스키마 헤더가 없어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.JSON,
                responseSchema = null
            )

            assertTrue(prompt.contains("[Response Format]")) {
                "JSON 형식은 [Response Format] 섹션을 포함해야 한다"
            }
            assertFalse(prompt.contains("Expected JSON schema:")) {
                "responseSchema=null이면 JSON 스키마 헤더가 없어야 한다"
            }
        }

        @Test
        fun `JSON 형식은 마크다운 코드 블록 금지 지시를 포함해야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.JSON
            )

            assertTrue(prompt.contains("no ```json")) {
                "JSON 형식은 마크다운 블록 금지 지시를 포함해야 한다"
            }
        }
    }

    // ── RAG 지시 추가 케이스 ──────────────────────────────────────────────

    @Nested
    inner class RagContextAdditional {

        @Test
        fun `RAG context가 있으면 출처 인용 지시가 포함되어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = "some fact",
                responseFormat = ResponseFormat.TEXT
            )

            assertTrue(prompt.contains("cite the source")) {
                "RAG context가 있으면 출처 인용 지시가 포함되어야 한다"
            }
            assertTrue(prompt.contains("Do not mention the retrieval process")) {
                "RAG context가 있으면 검색 과정 언급 금지 지시가 포함되어야 한다"
            }
        }

        @Test
        fun `RAG context 없이 null이면 Retrieved Context 섹션이 없어야 한다`() {
            val prompt = builder.build(
                basePrompt = "You are helpful.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT
            )

            assertFalse(prompt.contains("[Retrieved Context]")) {
                "ragContext=null이면 [Retrieved Context] 섹션이 없어야 한다"
            }
        }
    }
}

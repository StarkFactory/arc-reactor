package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.ResponseFormat
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [SystemPromptBuilder]의 출력 byte-identical 검증 테스트.
 *
 * ## 왜 이 테스트가 필요한가
 *
 * `CacheKeyBuilder.buildScopeFingerprint`는 SHA-256 해시에 `command.systemPrompt`를
 * 포함한다. 따라서 [SystemPromptBuilder]의 출력 텍스트가 1바이트라도 변경되면 Redis
 * 의미적 캐시의 scopeFingerprint가 달라져 **기존 캐시 엔트리가 전부 stale**이 된다.
 *
 * 이 테스트는 대표적인 4가지 입력 케이스에 대해 현재 출력의 SHA-256 해시를 golden value로
 * 저장한다. 이후 리팩토링에서 해시가 변경되면 테스트가 실패하여 개발자에게 다음 사실을
 * 알린다:
 *
 * 1. 프롬프트 텍스트가 바뀌었음
 * 2. 이 변경이 전체 의미적 캐시 무효화를 유발함
 * 3. 의도한 변경이라면 golden hash를 업데이트하고 별도의 cache flush 이벤트로 보고서에 기록
 * 4. 의도치 않은 변경이라면 리팩토링을 byte-identical로 조정
 *
 * ## 처음 실행 방법 (신규 케이스 추가 시)
 *
 * 1. 새 입력 케이스를 [buildCaseInput] 함수에 추가한다
 * 2. `expected` 파라미터를 `"TBD"`로 두고 테스트를 실행한다
 * 3. 실패 메시지에서 실제 SHA-256 값을 복사하여 `expected`에 붙여넣는다
 * 4. 다시 실행하면 PASS
 *
 * @see CacheKeyBuilder scopeFingerprint 생성 로직
 * @see RedisSemanticResponseCache Redis 기반 의미적 캐시 구현체
 */
class SystemPromptBuilderGoldenSnapshotTest {

    private val builder = SystemPromptBuilder()

    @Nested
    @DisplayName("byte-identical 불변 검증 (byte-identical invariance)")
    inner class ByteIdenticalInvariance {

        @Test
        @DisplayName("비워크스페이스 간단 인사 케이스의 출력 해시가 불변이어야 한다")
        fun `greeting case hash is stable`() {
            val output = builder.build(
                basePrompt = "You are a helpful assistant.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                responseSchema = null,
                userPrompt = "안녕하세요",
                workspaceToolAlreadyCalled = false,
                userMemoryContext = null,
                minimalPromptRetry = false
            )
            val hash = sha256Hex(output)
            assertEquals(
                GREETING_CASE_HASH,
                hash,
                "비워크스페이스 인사 케이스의 SystemPromptBuilder 출력이 변경되었다. " +
                    "이 변경은 CacheKeyBuilder.buildScopeFingerprint를 통해 Redis 의미적 캐시 " +
                    "무효화를 유발한다. 의도한 변경이면 golden hash를 업데이트하고 보고서에 " +
                    "cache flush 이벤트로 기록하라. 의도치 않은 변경이면 byte-identical로 조정하라. " +
                    "실제 해시: $hash, 출력 길이: ${output.length}자"
            )
        }

        @Test
        @DisplayName("워크스페이스 Jira 조회 케이스의 출력 해시가 불변이어야 한다")
        fun `workspace jira case hash is stable`() {
            val output = builder.build(
                basePrompt = "You are a helpful assistant.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                responseSchema = null,
                userPrompt = "내 지라 티켓 보여줘",
                workspaceToolAlreadyCalled = false,
                userMemoryContext = null,
                minimalPromptRetry = false
            )
            val hash = sha256Hex(output)
            assertEquals(
                WORKSPACE_JIRA_CASE_HASH,
                hash,
                "워크스페이스 Jira 케이스의 SystemPromptBuilder 출력이 변경되었다. " +
                    "이 변경은 Redis 의미적 캐시 무효화를 유발한다. 실제 해시: $hash, " +
                    "출력 길이: ${output.length}자"
            )
        }

        @Test
        @DisplayName("RAG + JSON 형식 케이스의 출력 해시가 불변이어야 한다")
        fun `rag plus json case hash is stable`() {
            val output = builder.build(
                basePrompt = "You are a helpful assistant.",
                ragContext = "- [문서1] 배포 가이드: 스테이징 → 프로덕션 순으로 배포한다.",
                responseFormat = ResponseFormat.JSON,
                responseSchema = """{"type":"object","properties":{"answer":{"type":"string"}}}""",
                userPrompt = "배포 절차 알려줘",
                workspaceToolAlreadyCalled = false,
                userMemoryContext = null,
                minimalPromptRetry = false
            )
            val hash = sha256Hex(output)
            assertEquals(
                RAG_JSON_CASE_HASH,
                hash,
                "RAG + JSON 케이스의 SystemPromptBuilder 출력이 변경되었다. " +
                    "이 변경은 Redis 의미적 캐시 무효화를 유발한다. 실제 해시: $hash, " +
                    "출력 길이: ${output.length}자"
            )
        }

        @Test
        @DisplayName("minimal prompt retry 경로의 출력 해시가 불변이어야 한다")
        fun `minimal retry case hash is stable`() {
            val output = builder.build(
                basePrompt = "You are a helpful assistant.",
                ragContext = null,
                responseFormat = ResponseFormat.TEXT,
                responseSchema = null,
                userPrompt = "릴리즈 노트 최신 거 보여줘",
                workspaceToolAlreadyCalled = false,
                userMemoryContext = null,
                minimalPromptRetry = true
            )
            val hash = sha256Hex(output)
            assertEquals(
                MINIMAL_RETRY_CASE_HASH,
                hash,
                "minimal retry 경로의 SystemPromptBuilder 출력이 변경되었다. " +
                    "R208 이후 이 경로는 retry 시에만 사용되므로 일반 캐시 영향은 작지만 " +
                    "동작 일관성을 위해 추적한다. 실제 해시: $hash, 출력 길이: ${output.length}자"
            )
        }
    }

    @Nested
    @DisplayName("계획 경로 byte-identical 불변 검증")
    inner class PlanningPathInvariance {

        @Test
        @DisplayName("계획 경로 간단 케이스의 출력 해시가 불변이어야 한다")
        fun `planning simple case hash is stable`() {
            val output = builder.buildPlanningPrompt(
                userPrompt = "JAR-36 이슈 상세를 알려줘",
                toolDescriptions = "- jira_get_issue(issueKey): Jira 이슈 상세 조회"
            )
            val hash = sha256Hex(output)
            assertEquals(
                PLANNING_CASE_HASH,
                hash,
                "계획 경로의 buildPlanningPrompt 출력이 변경되었다. 계획 경로는 PLAN_EXECUTE 모드에 " +
                    "사용되며 프롬프트 해시 변경 시 해당 모드의 캐시가 무효화된다. " +
                    "실제 해시: $hash, 출력 길이: ${output.length}자"
            )
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        // R220 initial capture (2026-04-11). SystemPromptBuilder 출력의 현재 상태를 locked down.
        // 이 해시가 바뀌면 출력 텍스트가 변경된 것이고, 이는 Redis 의미적 캐시 scopeFingerprint
        // 변경 → 전체 캐시 stale 을 유발한다. 의도한 변경이면 해시 업데이트 + cache flush 이벤트
        // 보고서 기록. 의도치 않으면 byte-identical 로 조정.
        private const val GREETING_CASE_HASH =
            "e665742b7557b7a6964fd4871e608e74d89a1e200a9c1332b08a3ccfd9bbda93"
        private const val WORKSPACE_JIRA_CASE_HASH =
            "cfbf79631d63d91da41161bae92a762d06e00bc8372e13360807ec4400398396"
        private const val RAG_JSON_CASE_HASH =
            "5a967e8d5daa0ada19d1b1ac89a7531c3ab8051f046ce26700f60f0c3c147eb1"
        private const val MINIMAL_RETRY_CASE_HASH =
            "f28af2f0aad868372e3c4857e7b98c5637347c92ed6ac35e32d00c97a1cda56c"
        private const val PLANNING_CASE_HASH =
            "5c93919d7767efc2f5fcefe820319fbbf094ef7803518c998a07d16af48f3580"
    }
}

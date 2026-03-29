package com.arc.reactor.hook.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ToolCallContext.maskedParams] 보안 마스킹 로직에 대한 단위 테스트.
 *
 * 기존 HookExecutorTest의 기본 케이스 외에 구분자 변형(언더스코어·대시·점),
 * 대소문자 무시, 긴 복합 키, 비민감 키 false-positive 방지를 검증한다.
 */
class ToolCallContextMaskedParamsTest {

    /** 테스트용 HookContext 기본 인스턴스 */
    private val agentCtx = HookContext(
        runId = "run-test",
        userId = "user-test",
        userPrompt = "test prompt"
    )

    private fun context(params: Map<String, Any?>) = ToolCallContext(
        agentContext = agentCtx,
        toolName = "test_tool",
        toolParams = params,
        callIndex = 0
    )

    @Nested
    inner class 언더스코어_구분자 {

        @Test
        fun `access_token 키는 마스킹된다`() {
            val masked = context(mapOf("access_token" to "Bearer abc123")).maskedParams()

            assertEquals("***", masked["access_token"]) {
                "access_token must be masked, got: ${masked["access_token"]}"
            }
        }

        @Test
        fun `api_key 키는 마스킹된다`() {
            val masked = context(mapOf("api_key" to "sk-xxx")).maskedParams()

            assertEquals("***", masked["api_key"]) {
                "api_key must be masked, got: ${masked["api_key"]}"
            }
        }

        @Test
        fun `client_secret 키는 마스킹된다`() {
            val masked = context(mapOf("client_secret" to "s3cr3t!")).maskedParams()

            assertEquals("***", masked["client_secret"]) {
                "client_secret must be masked, got: ${masked["client_secret"]}"
            }
        }

        @Test
        fun `user_credential 키는 마스킹된다`() {
            val masked = context(mapOf("user_credential" to "cred-val")).maskedParams()

            assertEquals("***", masked["user_credential"]) {
                "user_credential must be masked, got: ${masked["user_credential"]}"
            }
        }
    }

    @Nested
    inner class 대시_구분자 {

        @Test
        fun `api-key 키는 마스킹된다`() {
            val masked = context(mapOf("api-key" to "dashed-key")).maskedParams()

            assertEquals("***", masked["api-key"]) {
                "api-key must be masked, got: ${masked["api-key"]}"
            }
        }

        @Test
        fun `auth-token 키는 마스킹된다`() {
            val masked = context(mapOf("auth-token" to "tok-xyz")).maskedParams()

            assertEquals("***", masked["auth-token"]) {
                "auth-token must be masked, got: ${masked["auth-token"]}"
            }
        }

        @Test
        fun `client-secret 키는 마스킹된다`() {
            val masked = context(mapOf("client-secret" to "s3cr3t")).maskedParams()

            assertEquals("***", masked["client-secret"]) {
                "client-secret must be masked, got: ${masked["client-secret"]}"
            }
        }
    }

    @Nested
    inner class 점_구분자 {

        @Test
        fun `auth_dot_token 키는 마스킹된다`() {
            val masked = context(mapOf("auth.token" to "dot-tok")).maskedParams()

            assertEquals("***", masked["auth.token"]) {
                "auth.token must be masked, got: ${masked["auth.token"]}"
            }
        }

        @Test
        fun `config_dot_key 키는 마스킹된다`() {
            val masked = context(mapOf("config.key" to "dotkey")).maskedParams()

            assertEquals("***", masked["config.key"]) {
                "config.key must be masked, got: ${masked["config.key"]}"
            }
        }
    }

    @Nested
    inner class 대소문자_무시 {

        @Test
        fun `PASSWORD 대문자 키는 마스킹된다`() {
            val masked = context(mapOf("PASSWORD" to "PaSsW0rD")).maskedParams()

            assertEquals("***", masked["PASSWORD"]) {
                "Uppercase PASSWORD must be masked, got: ${masked["PASSWORD"]}"
            }
        }

        @Test
        fun `TOKEN 대문자 키는 마스킹된다`() {
            val masked = context(mapOf("TOKEN" to "tok-123")).maskedParams()

            assertEquals("***", masked["TOKEN"]) {
                "Uppercase TOKEN must be masked, got: ${masked["TOKEN"]}"
            }
        }

        @Test
        fun `Api_Key 혼합 대소문자 키는 마스킹된다`() {
            val masked = context(mapOf("Api_Key" to "mixed-case")).maskedParams()

            assertEquals("***", masked["Api_Key"]) {
                "Mixed-case Api_Key must be masked, got: ${masked["Api_Key"]}"
            }
        }
    }

    @Nested
    inner class 비민감_키_false_positive_방지 {

        @Test
        fun `url 키는 마스킹되지 않는다`() {
            val url = "https://api.example.com"
            val masked = context(mapOf("url" to url)).maskedParams()

            assertEquals(url, masked["url"]) {
                "url must NOT be masked, got: ${masked["url"]}"
            }
        }

        @Test
        fun `query 키는 마스킹되지 않는다`() {
            val masked = context(mapOf("query" to "SELECT * FROM users")).maskedParams()

            assertEquals("SELECT * FROM users", masked["query"]) {
                "query must NOT be masked, got: ${masked["query"]}"
            }
        }

        @Test
        fun `keywords 키는 마스킹되지 않는다`() {
            val masked = context(mapOf("keywords" to "spring boot")).maskedParams()

            assertEquals("spring boot", masked["keywords"]) {
                "keywords must NOT be masked — 'key' embedded in longer word should not trigger, " +
                    "got: ${masked["keywords"]}"
            }
        }

        @Test
        fun `monkey 키는 마스킹되지 않는다`() {
            val masked = context(mapOf("monkey" to "banana")).maskedParams()

            assertEquals("banana", masked["monkey"]) {
                "monkey must NOT be masked, got: ${masked["monkey"]}"
            }
        }

        @Test
        fun `timeout 키는 마스킹되지 않는다`() {
            val masked = context(mapOf("timeout" to 5000)).maskedParams()

            assertEquals(5000, masked["timeout"]) {
                "timeout must NOT be masked, got: ${masked["timeout"]}"
            }
        }
    }

    @Nested
    inner class 복합_파라미터_동시_마스킹 {

        @Test
        fun `민감 키와 비민감 키가 혼재할 때 각각 올바르게 처리된다`() {
            val params = mapOf(
                "url" to "https://example.com",
                "access_token" to "tok-secret",
                "user_id" to "user-123",
                "api_key" to "key-value",
                "limit" to 10,
                "client_secret" to "s3cr3t"
            )
            val masked = context(params).maskedParams()

            assertEquals("https://example.com", masked["url"]) {
                "url must not be masked"
            }
            assertEquals("***", masked["access_token"]) {
                "access_token must be masked"
            }
            assertEquals("user-123", masked["user_id"]) {
                "user_id must not be masked"
            }
            assertEquals("***", masked["api_key"]) {
                "api_key must be masked"
            }
            assertEquals(10, masked["limit"]) {
                "limit must not be masked"
            }
            assertEquals("***", masked["client_secret"]) {
                "client_secret must be masked"
            }
        }

        @Test
        fun `빈 파라미터 맵은 빈 결과를 반환한다`() {
            val masked = context(emptyMap()).maskedParams()

            assertEquals(0, masked.size) {
                "Empty params must return empty map, got size: ${masked.size}"
            }
        }

        @Test
        fun `null 값을 가진 민감 키도 마스킹된다`() {
            val masked = context(mapOf("password" to null)).maskedParams()

            assertEquals("***", masked["password"]) {
                "null-valued password must still be masked, got: ${masked["password"]}"
            }
        }
    }
}

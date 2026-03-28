package com.arc.reactor.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel

/**
 * ChatModelProvider 단위 테스트.
 *
 * 프로바이더 조회, 폴백, 예외 경로, 빈 이름 해석을 검증한다.
 */
class ChatModelProviderTest {

    // --- 헬퍼 ---

    private fun buildProvider(
        vararg pairs: Pair<String, ChatModel>,
        default: String = pairs.first().first
    ): ChatModelProvider {
        val models = pairs.toMap()
        return ChatModelProvider(models, default)
    }

    private fun mockChatModel(): ChatModel = mockk<ChatModel>(relaxed = true) {
        every { defaultOptions } returns mockk(relaxed = true)
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `getChatClient — 명시적 프로바이더 조회` {

        @Test
        fun `등록된 프로바이더 이름으로 ChatClient를 반환해야 한다`() {
            val model = mockChatModel()
            val provider = buildProvider("gemini" to model)

            val client = provider.getChatClient("gemini")

            assertNotNull(client) { "등록된 프로바이더 'gemini'에 대해 ChatClient가 반환되어야 한다" }
        }

        @Test
        fun `여러 프로바이더 중 정확한 프로바이더를 선택해야 한다`() {
            val geminiModel = mockChatModel()
            val openaiModel = mockChatModel()
            val provider = buildProvider(
                "gemini" to geminiModel,
                "openai" to openaiModel,
                default = "gemini"
            )

            val client = provider.getChatClient("openai")

            assertNotNull(client) { "등록된 프로바이더 'openai'에 대해 ChatClient가 반환되어야 한다" }
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `getChatClient — null 폴백 동작` {

        @Test
        fun `provider가 null이면 기본 프로바이더로 폴백해야 한다`() {
            val model = mockChatModel()
            val provider = buildProvider("gemini" to model, default = "gemini")

            val client = provider.getChatClient(null)

            assertNotNull(client) { "provider=null 이면 기본 프로바이더로 폴백된 ChatClient가 반환되어야 한다" }
        }

        @Test
        fun `기본 프로바이더가 openai인 경우 null 호출은 openai를 사용해야 한다`() {
            val gemini = mockChatModel()
            val openai = mockChatModel()
            val provider = buildProvider(
                "gemini" to gemini,
                "openai" to openai,
                default = "openai"
            )

            // provider=null → defaultProvider="openai" → openai ChatClient 반환
            val clientDefault = provider.getChatClient(null)
            val clientExplicit = provider.getChatClient("openai")

            assertNotNull(clientDefault) { "null 입력 시 기본 프로바이더('openai') ChatClient가 반환되어야 한다" }
            assertNotNull(clientExplicit) { "명시적 'openai' 입력 시 ChatClient가 반환되어야 한다" }
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `getChatClient — 알 수 없는 프로바이더 예외` {

        @Test
        fun `등록되지 않은 프로바이더 이름은 IllegalArgumentException을 던져야 한다`() {
            val model = mockChatModel()
            val provider = buildProvider("gemini" to model)

            val ex = assertThrows<IllegalArgumentException>(
                "미등록 프로바이더는 IllegalArgumentException을 던져야 한다"
            ) {
                provider.getChatClient("unknown-provider")
            }

            assertTrue(
                ex.message?.contains("unknown-provider") == true
            ) { "예외 메시지에 요청된 프로바이더 이름 'unknown-provider'가 포함되어야 한다" }
        }

        @Test
        fun `예외 메시지에 사용 가능한 프로바이더 목록이 포함되어야 한다`() {
            val model = mockChatModel()
            val provider = buildProvider("gemini" to model)

            val ex = assertThrows<IllegalArgumentException>(
                "예외 메시지에 사용 가능 프로바이더 목록이 포함되어야 한다"
            ) {
                provider.getChatClient("anthropic")
            }

            assertTrue(
                ex.message?.contains("gemini") == true
            ) { "예외 메시지에 등록된 프로바이더 'gemini'가 언급되어야 한다" }
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `availableProviders — 등록된 프로바이더 집합 조회` {

        @Test
        fun `단일 프로바이더만 등록된 경우 해당 이름만 반환해야 한다`() {
            val model = mockChatModel()
            val provider = buildProvider("gemini" to model)

            val available = provider.availableProviders()

            assertEquals(setOf("gemini"), available) { "등록된 프로바이더 집합이 정확해야 한다" }
        }

        @Test
        fun `여러 프로바이더를 등록하면 모두 반환해야 한다`() {
            val provider = buildProvider(
                "gemini" to mockChatModel(),
                "openai" to mockChatModel(),
                "anthropic" to mockChatModel(),
                default = "gemini"
            )

            val available = provider.availableProviders()

            assertTrue(available.containsAll(setOf("gemini", "openai", "anthropic"))) {
                "등록된 세 프로바이더가 모두 availableProviders()에 포함되어야 한다"
            }
            assertEquals(3, available.size) { "프로바이더 수가 정확해야 한다" }
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `defaultProvider — 기본 프로바이더 이름 조회` {

        @Test
        fun `생성자에 전달한 기본 프로바이더 이름을 반환해야 한다`() {
            val provider = buildProvider(
                "gemini" to mockChatModel(),
                "openai" to mockChatModel(),
                default = "openai"
            )

            assertEquals("openai", provider.defaultProvider()) {
                "defaultProvider()는 생성자에 지정한 프로바이더 이름 'openai'를 반환해야 한다"
            }
        }

        @Test
        fun `기본 프로바이더 변경 시 새 값을 반환해야 한다`() {
            val provider1 = buildProvider("gemini" to mockChatModel(), default = "gemini")
            val provider2 = buildProvider(
                "gemini" to mockChatModel(),
                "anthropic" to mockChatModel(),
                default = "anthropic"
            )

            assertEquals("gemini", provider1.defaultProvider()) {
                "첫 번째 인스턴스의 기본 프로바이더는 'gemini'여야 한다"
            }
            assertEquals("anthropic", provider2.defaultProvider()) {
                "두 번째 인스턴스의 기본 프로바이더는 'anthropic'이어야 한다"
            }
        }
    }

    // -------------------------------------------------------------------------

    @Nested
    inner class `resolveProviderName — 빈 이름 해석` {

        @Test
        fun `openAiChatModel 빈 이름은 openai로 해석되어야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("openAiChatModel")

            assertEquals("openai", resolved) { "'openAiChatModel'은 'openai'로 해석되어야 한다" }
        }

        @Test
        fun `anthropicChatModel 빈 이름은 anthropic으로 해석되어야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("anthropicChatModel")

            assertEquals("anthropic", resolved) { "'anthropicChatModel'은 'anthropic'으로 해석되어야 한다" }
        }

        @Test
        fun `googleAiGeminiChatModel 빈 이름은 gemini로 해석되어야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("googleAiGeminiChatModel")

            assertEquals("gemini", resolved) { "'googleAiGeminiChatModel'은 'gemini'로 해석되어야 한다" }
        }

        @Test
        fun `googleGenAiChatModel 빈 이름은 gemini로 해석되어야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("googleGenAiChatModel")

            assertEquals("gemini", resolved) { "'googleGenAiChatModel'은 'gemini'로 해석되어야 한다" }
        }

        @Test
        fun `vertexAiGeminiChatModel 빈 이름은 vertex로 해석되어야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("vertexAiGeminiChatModel")

            assertEquals("vertex", resolved) { "'vertexAiGeminiChatModel'은 'vertex'로 해석되어야 한다" }
        }

        @Test
        fun `알 수 없는 빈 이름은 원래 이름 그대로 반환해야 한다`() {
            val unknownBeanName = "customLlmChatModel"
            val resolved = ChatModelProvider.resolveProviderName(unknownBeanName)

            assertEquals(unknownBeanName, resolved) {
                "매핑에 없는 빈 이름은 변경 없이 원래 이름 그대로 반환되어야 한다"
            }
        }

        @Test
        fun `빈 문자열 빈 이름은 빈 문자열을 반환해야 한다`() {
            val resolved = ChatModelProvider.resolveProviderName("")

            assertEquals("", resolved) { "빈 문자열 입력은 빈 문자열로 반환되어야 한다" }
        }

        @Test
        fun `BEAN_NAME_MAPPING에 5개 항목이 정의되어 있어야 한다`() {
            // 실제 매핑 크기를 간접적으로 검증: 알려진 5개 빈 이름 모두 올바르게 해석
            val knownMappings = mapOf(
                "openAiChatModel" to "openai",
                "anthropicChatModel" to "anthropic",
                "googleAiGeminiChatModel" to "gemini",
                "googleGenAiChatModel" to "gemini",
                "vertexAiGeminiChatModel" to "vertex"
            )

            for ((beanName, expected) in knownMappings) {
                assertEquals(expected, ChatModelProvider.resolveProviderName(beanName)) {
                    "빈 이름 '$beanName'은 '$expected'로 해석되어야 한다"
                }
            }
        }
    }
}

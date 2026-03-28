package com.arc.reactor.intent

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.MessageRole
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.ClassifiedIntent
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 인텐트 모델 데이터 클래스에 대한 단위 테스트.
 *
 * ClassifiedIntent, IntentResult, ClassificationContext, IntentDefinition, IntentProfile을 검증한다.
 */
class IntentModelsTest {

    // -----------------------------------------------------------------------
    // ClassifiedIntent
    // -----------------------------------------------------------------------

    @Nested
    inner class ClassifiedIntentValidation {

        @Test
        fun `유효한 신뢰도 0_0으로 생성된다`() {
            val intent = ClassifiedIntent(intentName = "greeting", confidence = 0.0)
            assertEquals(0.0, intent.confidence) { "최솟값 0.0이 유효해야 한다" }
        }

        @Test
        fun `유효한 신뢰도 1_0으로 생성된다`() {
            val intent = ClassifiedIntent(intentName = "greeting", confidence = 1.0)
            assertEquals(1.0, intent.confidence) { "최댓값 1.0이 유효해야 한다" }
        }

        @Test
        fun `유효한 중간 신뢰도로 생성된다`() {
            val intent = ClassifiedIntent(intentName = "order", confidence = 0.75)
            assertEquals("order", intent.intentName) { "인텐트 이름이 보존되어야 한다" }
            assertEquals(0.75, intent.confidence) { "신뢰도 0.75가 유효해야 한다" }
        }

        @Test
        fun `신뢰도가 1_0 초과이면 예외가 발생한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                ClassifiedIntent(intentName = "greeting", confidence = 1.1)
            }
        }

        @Test
        fun `신뢰도가 0_0 미만이면 예외가 발생한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                ClassifiedIntent(intentName = "greeting", confidence = -0.1)
            }
        }

        @Test
        fun `예외 메시지에 현재 값이 포함된다`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                ClassifiedIntent(intentName = "x", confidence = 2.0)
            }
            assertTrue(ex.message?.contains("2.0") == true) { "예외 메시지에 잘못된 값 2.0이 포함되어야 한다" }
        }

        @Test
        fun `data class 동등성이 올바르게 동작한다`() {
            val a = ClassifiedIntent("greeting", 0.9)
            val b = ClassifiedIntent("greeting", 0.9)
            assertEquals(a, b) { "동일한 값의 ClassifiedIntent는 같아야 한다" }
        }

        @Test
        fun `이름이 다르면 동등하지 않다`() {
            val a = ClassifiedIntent("greeting", 0.9)
            val b = ClassifiedIntent("order", 0.9)
            assertNotEquals(a, b) { "인텐트 이름이 다르면 동등하지 않아야 한다" }
        }
    }

    // -----------------------------------------------------------------------
    // IntentResult
    // -----------------------------------------------------------------------

    @Nested
    inner class IntentResultBehavior {

        @Test
        fun `primary가 null이면 isUnknown이 true다`() {
            val result = IntentResult(primary = null, classifiedBy = "rule")
            assertTrue(result.isUnknown) { "primary=null이면 isUnknown이 true여야 한다" }
        }

        @Test
        fun `primary가 non-null이면 isUnknown이 false다`() {
            val result = IntentResult(
                primary = ClassifiedIntent("greeting", 0.9),
                classifiedBy = "rule"
            )
            assertFalse(result.isUnknown) { "primary가 존재하면 isUnknown이 false여야 한다" }
        }

        @Test
        fun `unknown 팩토리가 primary=null로 생성된다`() {
            val result = IntentResult.unknown(classifiedBy = "rule")
            assertNull(result.primary) { "unknown() 결과의 primary는 null이어야 한다" }
            assertTrue(result.isUnknown) { "unknown() 결과는 isUnknown=true여야 한다" }
        }

        @Test
        fun `unknown 팩토리가 classifiedBy를 보존한다`() {
            val result = IntentResult.unknown(classifiedBy = "llm")
            assertEquals("llm", result.classifiedBy) { "unknown()의 classifiedBy가 보존되어야 한다" }
        }

        @Test
        fun `unknown 팩토리가 tokenCost와 latencyMs를 설정한다`() {
            val result = IntentResult.unknown(classifiedBy = "rule", tokenCost = 42, latencyMs = 15L)
            assertEquals(42, result.tokenCost) { "unknown()의 tokenCost가 설정되어야 한다" }
            assertEquals(15L, result.latencyMs) { "unknown()의 latencyMs가 설정되어야 한다" }
        }

        @Test
        fun `기본값으로 secondary는 빈 리스트다`() {
            val result = IntentResult(
                primary = ClassifiedIntent("greeting", 0.9),
                classifiedBy = "rule"
            )
            assertTrue(result.secondary.isEmpty()) { "secondary 기본값은 빈 리스트여야 한다" }
        }

        @Test
        fun `tokenCost 기본값은 0이다`() {
            val result = IntentResult(
                primary = ClassifiedIntent("order", 0.7),
                classifiedBy = "rule"
            )
            assertEquals(0, result.tokenCost) { "tokenCost 기본값은 0이어야 한다" }
        }

        @Test
        fun `latencyMs 기본값은 0이다`() {
            val result = IntentResult(
                primary = ClassifiedIntent("order", 0.7),
                classifiedBy = "rule"
            )
            assertEquals(0L, result.latencyMs) { "latencyMs 기본값은 0이어야 한다" }
        }

        @Test
        fun `여러 보조 인텐트를 포함할 수 있다`() {
            val result = IntentResult(
                primary = ClassifiedIntent("refund", 0.85),
                secondary = listOf(
                    ClassifiedIntent("shipping", 0.72),
                    ClassifiedIntent("order", 0.6)
                ),
                classifiedBy = "llm",
                tokenCost = 300,
                latencyMs = 250L
            )
            assertEquals(2, result.secondary.size) { "보조 인텐트가 2개여야 한다" }
            assertEquals("shipping", result.secondary[0].intentName) { "첫 번째 보조 인텐트가 'shipping'이어야 한다" }
            assertEquals(300, result.tokenCost) { "tokenCost가 300이어야 한다" }
            assertEquals(250L, result.latencyMs) { "latencyMs가 250이어야 한다" }
        }
    }

    // -----------------------------------------------------------------------
    // ClassificationContext
    // -----------------------------------------------------------------------

    @Nested
    inner class ClassificationContextLazyLoading {

        @Test
        fun `EMPTY 싱글턴은 빈 컨텍스트다`() {
            val empty = ClassificationContext.EMPTY
            assertNull(empty.userId) { "EMPTY의 userId는 null이어야 한다" }
            assertTrue(empty.conversationHistory.isEmpty()) { "EMPTY의 대화이력은 비어 있어야 한다" }
            assertNull(empty.channel) { "EMPTY의 channel은 null이어야 한다" }
            assertTrue(empty.metadata.isEmpty()) { "EMPTY의 metadata는 비어 있어야 한다" }
        }

        @Test
        fun `conversationHistory가 있으면 로더 없이 반환된다`() {
            val messages = listOf(
                Message(role = MessageRole.USER, content = "안녕")
            )
            val context = ClassificationContext(conversationHistory = messages)

            val resolved = context.resolveConversationHistory()
            assertEquals(1, resolved.size) { "제공된 대화이력이 그대로 반환되어야 한다" }
            assertEquals("안녕", resolved[0].content) { "대화이력 메시지 내용이 일치해야 한다" }
        }

        @Test
        fun `conversationHistory가 없으면 로더가 호출된다`() {
            val callCount = AtomicInteger(0)
            val lazyMessages = listOf(
                Message(role = MessageRole.ASSISTANT, content = "무엇을 도와드릴까요?")
            )
            val context = ClassificationContext(
                conversationHistoryLoader = {
                    callCount.incrementAndGet()
                    lazyMessages
                }
            )

            val resolved = context.resolveConversationHistory()
            assertEquals(1, callCount.get()) { "로더가 정확히 1번 호출되어야 한다" }
            assertEquals(1, resolved.size) { "로더가 반환한 메시지가 포함되어야 한다" }
            assertEquals("무엇을 도와드릴까요?", resolved[0].content) { "로더가 반환한 내용이 일치해야 한다" }
        }

        @Test
        fun `로더는 한 번만 호출된다 (lazy 캐싱)`() {
            val callCount = AtomicInteger(0)
            val context = ClassificationContext(
                conversationHistoryLoader = {
                    callCount.incrementAndGet()
                    listOf(Message(role = MessageRole.USER, content = "테스트"))
                }
            )

            // 두 번 호출해도 로더는 한 번만 실행된다
            context.resolveConversationHistory()
            context.resolveConversationHistory()

            assertEquals(1, callCount.get()) { "lazy 프로퍼티이므로 로더가 1번만 호출되어야 한다" }
        }

        @Test
        fun `conversationHistory와 로더 둘 다 없으면 빈 리스트가 반환된다`() {
            val context = ClassificationContext()
            val resolved = context.resolveConversationHistory()
            assertTrue(resolved.isEmpty()) { "대화이력과 로더 모두 없으면 빈 리스트여야 한다" }
        }

        @Test
        fun `conversationHistory가 있으면 로더가 호출되지 않는다`() {
            val callCount = AtomicInteger(0)
            val context = ClassificationContext(
                conversationHistory = listOf(Message(role = MessageRole.USER, content = "직접 제공")),
                conversationHistoryLoader = {
                    callCount.incrementAndGet()
                    emptyList()
                }
            )

            context.resolveConversationHistory()
            assertEquals(0, callCount.get()) { "conversationHistory가 있으면 로더가 호출되지 않아야 한다" }
        }

        @Test
        fun `userId와 channel 필드가 보존된다`() {
            val context = ClassificationContext(
                userId = "user-42",
                channel = "mobile",
                metadata = mapOf("region" to "KR")
            )
            assertEquals("user-42", context.userId) { "userId가 보존되어야 한다" }
            assertEquals("mobile", context.channel) { "channel이 보존되어야 한다" }
            assertEquals("KR", context.metadata["region"]) { "metadata 값이 보존되어야 한다" }
        }
    }

    // -----------------------------------------------------------------------
    // IntentDefinition
    // -----------------------------------------------------------------------

    @Nested
    inner class IntentDefinitionDefaults {

        @Test
        fun `필수 필드만으로 생성할 수 있다`() {
            val intent = IntentDefinition(name = "greeting", description = "인사 처리")
            assertEquals("greeting", intent.name) { "name이 설정되어야 한다" }
            assertEquals("인사 처리", intent.description) { "description이 설정되어야 한다" }
        }

        @Test
        fun `기본값으로 enabled는 true다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.enabled) { "enabled 기본값은 true여야 한다" }
        }

        @Test
        fun `기본값으로 keywords는 빈 리스트다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.keywords.isEmpty()) { "keywords 기본값은 빈 리스트여야 한다" }
        }

        @Test
        fun `기본값으로 examples는 빈 리스트다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.examples.isEmpty()) { "examples 기본값은 빈 리스트여야 한다" }
        }

        @Test
        fun `기본값으로 synonyms는 빈 맵이다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.synonyms.isEmpty()) { "synonyms 기본값은 빈 맵이어야 한다" }
        }

        @Test
        fun `기본값으로 keywordWeights는 빈 맵이다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.keywordWeights.isEmpty()) { "keywordWeights 기본값은 빈 맵이어야 한다" }
        }

        @Test
        fun `기본값으로 negativeKeywords는 빈 리스트다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertTrue(intent.negativeKeywords.isEmpty()) { "negativeKeywords 기본값은 빈 리스트여야 한다" }
        }

        @Test
        fun `기본값으로 profile은 기본 IntentProfile이다`() {
            val intent = IntentDefinition(name = "order", description = "주문 처리")
            assertEquals(IntentProfile(), intent.profile) { "기본 profile은 모든 null인 IntentProfile이어야 한다" }
        }

        @Test
        fun `createdAt과 updatedAt이 자동 설정된다`() {
            val before = Instant.now()
            val intent = IntentDefinition(name = "greeting", description = "인사")
            val after = Instant.now()

            assertFalse(intent.createdAt.isBefore(before)) { "createdAt이 생성 전 시각보다 이르면 안 된다" }
            assertFalse(intent.createdAt.isAfter(after)) { "createdAt이 생성 후 시각보다 늦으면 안 된다" }
        }

        @Test
        fun `copy로 특정 필드만 변경할 수 있다`() {
            val original = IntentDefinition(
                name = "greeting",
                description = "인사",
                keywords = listOf("안녕", "hello"),
                enabled = true
            )
            val disabled = original.copy(enabled = false)

            assertFalse(disabled.enabled) { "copy된 인텐트는 enabled=false여야 한다" }
            assertEquals(original.name, disabled.name) { "name은 변경되지 않아야 한다" }
            assertEquals(original.keywords, disabled.keywords) { "keywords는 변경되지 않아야 한다" }
        }

        @Test
        fun `전체 필드를 지정하여 생성할 수 있다`() {
            val profile = IntentProfile(model = "gemini", maxToolCalls = 5)
            val intent = IntentDefinition(
                name = "refund",
                description = "환불 처리",
                examples = listOf("환불하고 싶어요", "돈 돌려줘"),
                keywords = listOf("환불", "반품"),
                synonyms = mapOf("환불" to listOf("리펀드")),
                keywordWeights = mapOf("환불" to 2.0, "반품" to 1.0),
                negativeKeywords = listOf("환불 정책"),
                profile = profile,
                enabled = true
            )

            assertEquals(2, intent.examples.size) { "examples가 2개여야 한다" }
            assertEquals(2, intent.keywords.size) { "keywords가 2개여야 한다" }
            assertEquals(listOf("리펀드"), intent.synonyms["환불"]) { "synonyms가 설정되어야 한다" }
            assertEquals(2.0, intent.keywordWeights["환불"]) { "keywordWeights가 설정되어야 한다" }
            assertEquals(listOf("환불 정책"), intent.negativeKeywords) { "negativeKeywords가 설정되어야 한다" }
            assertEquals("gemini", intent.profile.model) { "profile.model이 설정되어야 한다" }
        }
    }

    // -----------------------------------------------------------------------
    // IntentProfile
    // -----------------------------------------------------------------------

    @Nested
    inner class IntentProfileNullSemantics {

        @Test
        fun `기본 생성자에서 모든 필드가 null이다`() {
            val profile = IntentProfile()
            assertNull(profile.model) { "기본 profile.model은 null이어야 한다" }
            assertNull(profile.temperature) { "기본 profile.temperature는 null이어야 한다" }
            assertNull(profile.maxToolCalls) { "기본 profile.maxToolCalls는 null이어야 한다" }
            assertNull(profile.allowedTools) { "기본 profile.allowedTools는 null이어야 한다" }
            assertNull(profile.systemPrompt) { "기본 profile.systemPrompt는 null이어야 한다" }
            assertNull(profile.responseFormat) { "기본 profile.responseFormat은 null이어야 한다" }
        }

        @Test
        fun `부분 필드를 설정하면 나머지는 null이다`() {
            val profile = IntentProfile(model = "anthropic", temperature = 0.2)
            assertEquals("anthropic", profile.model) { "model이 설정되어야 한다" }
            assertEquals(0.2, profile.temperature) { "temperature가 설정되어야 한다" }
            assertNull(profile.maxToolCalls) { "설정하지 않은 maxToolCalls는 null이어야 한다" }
            assertNull(profile.allowedTools) { "설정하지 않은 allowedTools는 null이어야 한다" }
        }

        @Test
        fun `allowedTools에 빈 집합을 설정할 수 있다`() {
            val profile = IntentProfile(allowedTools = emptySet())
            assertNotNull(profile.allowedTools) { "빈 집합도 non-null로 설정할 수 있어야 한다" }
            assertTrue(profile.allowedTools!!.isEmpty()) { "allowedTools가 빈 집합이어야 한다" }
        }

        @Test
        fun `allowedTools에 도구 목록을 설정할 수 있다`() {
            val profile = IntentProfile(allowedTools = setOf("processRefund", "trackShipping"))
            val tools = profile.allowedTools
            assertNotNull(tools) { "allowedTools가 null이 아니어야 한다" }
            assertEquals(2, tools!!.size) { "allowedTools에 2개 도구가 있어야 한다" }
            assertTrue(tools.contains("processRefund")) {
                "processRefund가 allowedTools에 포함되어야 한다"
            }
        }

        @Test
        fun `동일한 값의 두 IntentProfile은 같다`() {
            val a = IntentProfile(model = "gemini", temperature = 0.5, maxToolCalls = 3)
            val b = IntentProfile(model = "gemini", temperature = 0.5, maxToolCalls = 3)
            assertEquals(a, b) { "동일한 값의 IntentProfile은 같아야 한다" }
        }

        @Test
        fun `model만 다르면 두 IntentProfile은 다르다`() {
            val a = IntentProfile(model = "gemini")
            val b = IntentProfile(model = "anthropic")
            assertNotEquals(a, b) { "model이 다르면 IntentProfile이 달라야 한다" }
        }

        @Test
        fun `copy로 특정 필드만 오버라이드할 수 있다`() {
            val original = IntentProfile(model = "gemini", temperature = 0.7, maxToolCalls = 10)
            val overridden = original.copy(temperature = 0.1)

            assertEquals("gemini", overridden.model) { "copy 후 model은 유지되어야 한다" }
            assertEquals(0.1, overridden.temperature) { "copy 후 temperature가 변경되어야 한다" }
            assertEquals(10, overridden.maxToolCalls) { "copy 후 maxToolCalls는 유지되어야 한다" }
        }
    }
}

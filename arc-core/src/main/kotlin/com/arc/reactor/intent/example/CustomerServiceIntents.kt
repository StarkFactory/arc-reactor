package com.arc.reactor.intent.example

import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile

/**
 * Customer Service Intent Definitions — Example
 *
 * Demonstrates how to register intents for a customer service chatbot.
 * This class is an example and should NOT be auto-registered as a component.
 *
 * ## Usage
 * ```kotlin
 * val registry = InMemoryIntentRegistry()
 * CustomerServiceIntents.register(registry)
 * ```
 */
// @Component  // Commented out — example only, not auto-registered
object CustomerServiceIntents {

    fun register(registry: IntentRegistry) {
        registry.save(
            IntentDefinition(
                name = "greeting",
                description = "Simple greetings and small talk",
                examples = listOf("안녕하세요", "Hi there", "Hello"),
                keywords = listOf("안녕", "hello", "hi", "/start"),
                synonyms = mapOf(
                    "안녕" to listOf("하이", "헬로"),
                    "hello" to listOf("hey", "howdy")
                ),
                profile = IntentProfile(
                    model = "gemini",
                    maxToolCalls = 0
                )
            )
        )

        registry.save(
            IntentDefinition(
                name = "order_inquiry",
                description = "Order lookup, status check, order modification",
                examples = listOf(
                    "주문 상태 확인해주세요",
                    "주문 #1234 어디까지 왔어요?",
                    "What is the status of my order?"
                ),
                keywords = listOf("주문 조회", "주문 상태", "order status"),
                synonyms = mapOf(
                    "주문 조회" to listOf("주문 확인", "배송 조회"),
                    "주문 상태" to listOf("배송 상태", "배송 현황")
                ),
                negativeKeywords = listOf("주문 취소", "주문 환불"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "getOrderStatus"),
                    maxToolCalls = 3
                )
            )
        )

        registry.save(
            IntentDefinition(
                name = "refund",
                description = "Refund requests, return processing, refund status",
                examples = listOf(
                    "환불 신청하고 싶어요",
                    "주문 취소하고 환불해주세요",
                    "I want to return this product"
                ),
                keywords = listOf("환불", "반품", "취소"),
                synonyms = mapOf(
                    "환불" to listOf("리펀드", "돌려줘", "refund"),
                    "반품" to listOf("반송", "return")
                ),
                keywordWeights = mapOf("환불" to 3.0, "반품" to 2.0),
                negativeKeywords = listOf("환불 정책", "반품 규정"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "processRefund", "getRefundStatus"),
                    systemPrompt = "You are a refund specialist. Follow the refund policy strictly.",
                    maxToolCalls = 5
                )
            )
        )

        registry.save(
            IntentDefinition(
                name = "data_analysis",
                description = "Complex data analysis, report generation, trend analysis",
                examples = listOf(
                    "지난 3개월 매출 분석해줘",
                    "Analyze the sales trend for Q4"
                ),
                profile = IntentProfile(
                    model = "openai",
                    maxToolCalls = 10
                )
            )
        )
    }
}

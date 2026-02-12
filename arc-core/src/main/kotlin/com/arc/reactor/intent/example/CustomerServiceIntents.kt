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

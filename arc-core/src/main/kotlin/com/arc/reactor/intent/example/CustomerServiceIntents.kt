package com.arc.reactor.intent.example

import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile

/**
 * 고객 서비스 인텐트 정의 — 예시
 *
 * 고객 서비스 챗봇을 위한 인텐트 등록 방법을 보여준다.
 * 이 클래스는 예시이며 컴포넌트로 자동 등록되어서는 안 된다.
 *
 * ## 사용법
 * ```kotlin
 * val registry = InMemoryIntentRegistry()
 * CustomerServiceIntents.register(registry)
 * ```
 *
 * WHY: 인텐트 정의의 모범 사례를 보여주기 위한 참조 구현이다.
 * 키워드, 동의어, 가중치, 부정 키워드, 프로필 설정 등 모든 기능의 활용법을 시연한다.
 *
 * @see IntentRegistry 인텐트 레지스트리
 * @see IntentDefinition 인텐트 정의 모델
 */
// @Component  // 주석 처리 — 예시 전용, 자동 등록하지 않음
object CustomerServiceIntents {

    /**
     * 고객 서비스용 인텐트들을 레지스트리에 등록한다.
     *
     * 등록되는 인텐트:
     * - greeting: 간단한 인사말 (도구 호출 없음, 경량 모델)
     * - order_inquiry: 주문 조회/상태 확인 (주문 관련 도구만 허용)
     * - refund: 환불/반품 처리 (전용 시스템 프롬프트 + 환불 도구)
     * - data_analysis: 복잡한 데이터 분석 (고성능 모델, 도구 호출 10회)
     */
    fun register(registry: IntentRegistry) {
        // 인사말 인텐트 — 도구 호출 불필요, 경량 모델로 빠르게 응답
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

        // 주문 조회 인텐트 — 주문 관련 도구만 허용하여 범위 제한
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
                // "주문 취소", "주문 환불"은 refund 인텐트가 처리해야 하므로 부정 키워드로 제외
                negativeKeywords = listOf("주문 취소", "주문 환불"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "getOrderStatus"),
                    maxToolCalls = 3
                )
            )
        )

        // 환불 인텐트 — 전용 시스템 프롬프트로 환불 정책 준수 강제
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
                // "환불"에 높은 가중치를 부여하여 신뢰도를 높인다
                keywordWeights = mapOf("환불" to 3.0, "반품" to 2.0),
                // 정책 문의는 별도 인텐트로 처리해야 하므로 부정 키워드로 제외
                negativeKeywords = listOf("환불 정책", "반품 규정"),
                profile = IntentProfile(
                    allowedTools = setOf("checkOrder", "processRefund", "getRefundStatus"),
                    systemPrompt = "You are a refund specialist. Follow the refund policy strictly.",
                    maxToolCalls = 5
                )
            )
        )

        // 데이터 분석 인텐트 — 복잡한 분석이므로 고성능 모델과 많은 도구 호출 허용
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

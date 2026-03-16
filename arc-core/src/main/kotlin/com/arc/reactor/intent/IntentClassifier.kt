package com.arc.reactor.intent

import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentResult

/**
 * 인텐트 분류기 인터페이스
 *
 * 사용자 입력을 하나 이상의 인텐트로 분류한다.
 * 구현체는 규칙 기반 매칭, LLM 호출, 또는 이들의 조합을 사용할 수 있다.
 *
 * ## 구현 가이드라인
 * - 충분한 신뢰도로 매칭되는 인텐트가 없을 때 [IntentResult.unknown]을 반환한다
 * - [IntentResult.classifiedBy]에 항상 분류기 이름을 채워넣는다
 * - LLM 기반 분류기의 경우 토큰 비용을 추적한다
 *
 * ## 사용 예시
 * ```kotlin
 * val result = classifier.classify("주문 반품하고 싶어요", context)
 * val intentName = result.primary?.intentName
 * if (intentName != null) {
 *     // 예: "refund"
 * }
 * ```
 *
 * WHY: 인텐트 분류를 인터페이스로 추상화하여 규칙/LLM/복합 전략을
 * 런타임에 교체할 수 있게 한다. CompositeIntentClassifier가 이 인터페이스를 통해
 * 규칙 기반 → LLM 폴백 캐스케이딩을 구현한다.
 *
 * @see com.arc.reactor.intent.impl.RuleBasedIntentClassifier 키워드 매칭 구현
 * @see com.arc.reactor.intent.impl.LlmIntentClassifier LLM 기반 분류 구현
 * @see com.arc.reactor.intent.impl.CompositeIntentClassifier 캐스케이딩 전략 구현
 */
interface IntentClassifier {

    /**
     * 사용자 입력을 인텐트로 분류한다.
     *
     * @param text 사용자 입력 텍스트
     * @param context 추가 분류 컨텍스트 (대화 이력, 사용자 정보)
     * @return 주요 인텐트와 선택적 보조 인텐트가 포함된 분류 결과
     */
    suspend fun classify(text: String, context: ClassificationContext = ClassificationContext.EMPTY): IntentResult
}

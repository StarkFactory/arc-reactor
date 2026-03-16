package com.arc.reactor.intent.model

import com.arc.reactor.agent.model.Message
import com.arc.reactor.agent.model.ResponseFormat
import java.time.Instant

/**
 * 인텐트 정의 — 시스템이 처리할 수 있는 사용자 요청 유형을 기술한다.
 *
 * 각 인텐트에는 해당 인텐트로 분류된 요청에 적용될 기본 에이전트 설정을
 * 오버라이드하는 [profile]이 있다.
 *
 * WHY: 인텐트별로 다른 파이프라인 설정(모델, 도구, 온도 등)을 적용하여
 * 각 유형의 요청에 최적화된 처리를 보장한다.
 * 예: 인사말에는 도구 호출 없이 경량 모델, 데이터 분석에는 도구 허용과 고성능 모델.
 *
 * @param name 고유 식별자 (예: "greeting", "order_inquiry", "data_analysis")
 * @param description LLM 분류기가 인텐트 선택에 사용하는 사람이 읽을 수 있는 설명
 * @param examples LLM 분류 정확도를 위한 퓨샷 예시
 * @param keywords 규칙 기반 분류를 위한 키워드 (높은 신뢰도 패턴만)
 * @param synonyms 동의어 그룹 — 키는 정규 키워드, 값은 대체 표현
 * @param keywordWeights 키워드 중요도 가중치 — 높은 값이 신뢰도 기여를 증가시킴 (기본값 1.0)
 * @param negativeKeywords 매칭 시 해당 인텐트를 제외하는 키워드
 * @param profile 이 인텐트에 대한 파이프라인 설정 오버라이드
 * @param enabled 분류에 활성화되어 있는지 여부
 * @param createdAt 생성 시각
 * @param updatedAt 마지막 수정 시각
 * @see IntentProfile 파이프라인 설정 오버라이드
 * @see com.arc.reactor.intent.IntentRegistry 인텐트 레지스트리
 */
data class IntentDefinition(
    val name: String,
    val description: String,
    val examples: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    val synonyms: Map<String, List<String>> = emptyMap(),
    val keywordWeights: Map<String, Double> = emptyMap(),
    val negativeKeywords: List<String> = emptyList(),
    val profile: IntentProfile = IntentProfile(),
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * 인텐트 프로필 — 인텐트 매칭 시 적용되는 파이프라인 설정 오버라이드.
 *
 * 모든 필드가 nullable이다. `null`은 "전역 기본값 사용"을 의미한다.
 * non-null 값만 기본 에이전트 설정을 오버라이드한다.
 *
 * WHY: 병합(merge) 방식을 사용하여 인텐트별로 필요한 설정만 오버라이드하고
 * 나머지는 전역 설정을 그대로 사용한다. 이를 통해 인텐트 정의를 간결하게 유지한다.
 *
 * @param model LLM 프로바이더 오버라이드 (예: "gemini", "openai", "anthropic")
 * @param temperature 온도 오버라이드
 * @param maxToolCalls 최대 도구 호출 수 오버라이드
 * @param allowedTools 도구 화이트리스트 (null = 모든 도구 허용)
 * @param systemPrompt 시스템 프롬프트 오버라이드
 * @param responseFormat 응답 형식 오버라이드
 * @see com.arc.reactor.intent.IntentResolver 프로필 적용 로직
 */
data class IntentProfile(
    val model: String? = null,
    val temperature: Double? = null,
    val maxToolCalls: Int? = null,
    val allowedTools: Set<String>? = null,
    val systemPrompt: String? = null,
    val responseFormat: ResponseFormat? = null
)

/**
 * 분류 컨텍스트 — 인텐트 분류를 위한 추가 정보.
 *
 * 대화 이력은 지연 로딩(lazy loading)을 지원한다. [conversationHistory]가 비어있으면
 * [conversationHistoryLoader]를 통해 필요할 때만 로딩한다.
 *
 * WHY: 대화 이력 로딩은 DB 호출을 수반할 수 있으므로, 규칙 기반 분류에서는
 * 이력이 불필요한 경우 DB 호출을 피하기 위해 지연 로딩을 적용한다.
 *
 * @param userId 개인화된 분류를 위한 사용자 식별자
 * @param conversationHistory 컨텍스트 의존 분류를 위한 최근 대화 이력
 * @param channel 요청 채널 (예: "web", "mobile", "api")
 * @param metadata 커스텀 분류기를 위한 추가 메타데이터
 * @param conversationHistoryLoader 대화 이력을 지연 로딩하는 함수
 */
data class ClassificationContext(
    val userId: String? = null,
    val conversationHistory: List<Message> = emptyList(),
    val channel: String? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val conversationHistoryLoader: (() -> List<Message>)? = null
) {
    /**
     * 지연 해석된 대화 이력.
     * WHY: LazyThreadSafetyMode.NONE을 사용하는 이유는 ClassificationContext가
     * 단일 요청 내에서만 사용되므로 스레드 안전이 불필요하기 때문이다.
     */
    private val resolvedConversationHistory by lazy(LazyThreadSafetyMode.NONE) {
        if (conversationHistory.isNotEmpty()) {
            conversationHistory
        } else {
            conversationHistoryLoader?.invoke().orEmpty()
        }
    }

    /** 해석된 대화 이력을 반환한다. 필요시 로더를 통해 지연 로딩한다. */
    fun resolveConversationHistory(): List<Message> = resolvedConversationHistory

    companion object {
        /** 빈 컨텍스트 싱글턴 */
        val EMPTY = ClassificationContext()
    }
}

/**
 * 인텐트 분류 결과.
 *
 * @param primary 주요 분류된 인텐트 (null = unknown/매칭 없음)
 * @param secondary 감지된 추가 인텐트 (다중 인텐트 입력 시)
 * @param classifiedBy 이 결과를 생성한 분류기 ("rule" 또는 "llm")
 * @param tokenCost 분류에 소모된 토큰 수 (규칙 기반은 0)
 * @param latencyMs 분류 지연 시간 (밀리초)
 */
data class IntentResult(
    val primary: ClassifiedIntent?,
    val secondary: List<ClassifiedIntent> = emptyList(),
    val classifiedBy: String,
    val tokenCost: Int = 0,
    val latencyMs: Long = 0
) {
    /** 매칭된 인텐트가 없는지 여부 */
    val isUnknown: Boolean get() = primary == null

    companion object {
        /** unknown 결과를 생성한다 (매칭된 인텐트 없음) */
        fun unknown(classifiedBy: String, tokenCost: Int = 0, latencyMs: Long = 0) =
            IntentResult(
                primary = null,
                classifiedBy = classifiedBy,
                tokenCost = tokenCost,
                latencyMs = latencyMs
            )
    }
}

/**
 * 신뢰도 점수를 가진 단일 분류된 인텐트.
 *
 * @param intentName 매칭된 인텐트 이름
 * @param confidence 신뢰도 점수 (0.0 ~ 1.0)
 */
data class ClassifiedIntent(
    val intentName: String,
    val confidence: Double
) {
    init {
        require(confidence in 0.0..1.0) { "신뢰도는 0.0과 1.0 사이여야 합니다. 현재: $confidence" }
    }
}

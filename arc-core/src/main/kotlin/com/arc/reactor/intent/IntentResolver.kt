package com.arc.reactor.intent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.intent.model.ClassificationContext
import com.arc.reactor.intent.model.IntentProfile
import com.arc.reactor.intent.model.IntentResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 인텐트 리졸버
 *
 * 인텐트 분류를 조율하고 매칭된 프로필을 [AgentCommand]에 적용한다.
 * 인텐트 시스템과 에이전트 실행기 사이의 주요 통합 지점이다.
 *
 * ## 설계 원칙
 * - 분류 실패 = 기본 파이프라인 (인텐트 시스템이 요청을 절대 차단하지 않음)
 * - 낮은 신뢰도 = 기본 파이프라인 (높은 신뢰도의 매칭만 프로필을 적용)
 * - 프로필 필드는 병합됨: null = 원래 값 유지, non-null = 오버라이드
 *
 * WHY: 인텐트 시스템은 요청 흐름을 차단해서는 안 된다. 분류 오류나 낮은 신뢰도는
 * 기본 파이프라인으로 폴백하여 사용자 경험을 보호한다. 이는 가드(Guard)의
 * fail-close 정책과 의도적으로 반대되는 fail-open 설계이다.
 *
 * @param classifier 인텐트 분류기 (규칙 기반, LLM, 또는 복합)
 * @param registry 인텐트 정의 및 프로필 조회를 위한 레지스트리
 * @param confidenceThreshold 프로필 적용을 위한 최소 신뢰도 (기본값 0.6)
 * @see IntentClassifier 분류기 인터페이스
 * @see IntentRegistry 인텐트 레지스트리
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor 에이전트 실행기에서의 활용
 */
class IntentResolver(
    private val classifier: IntentClassifier,
    private val registry: IntentRegistry,
    private val confidenceThreshold: Double = 0.6
) {

    /**
     * 사용자 입력을 분류하고 적절한 프로필을 결정한다.
     *
     * 분류 흐름:
     * 1. 분류기로 인텐트를 분류한다
     * 2. unknown이면 null 반환 (기본 파이프라인 사용)
     * 3. 신뢰도가 임계값 미만이면 null 반환
     * 4. 레지스트리에서 인텐트 정의를 조회한다
     * 5. 보조 인텐트의 도구를 병합하여 최종 프로필을 구성한다
     *
     * @param text 사용자 입력 텍스트
     * @param context 분류 컨텍스트 (대화 이력, 사용자 정보)
     * @return 프로필이 포함된 결정된 인텐트, 또는 신뢰도 높은 매칭이 없으면 null
     */
    suspend fun resolve(text: String, context: ClassificationContext = ClassificationContext.EMPTY): ResolvedIntent? {
        try {
            val result = classifier.classify(text, context)

            if (result.isUnknown) {
                logger.debug { "IntentResolver: 매칭된 인텐트 없음, 기본 파이프라인 사용" }
                return null
            }

            val primary = result.primary ?: run {
                logger.warn { "IntentResolver: unknown이 아닌데 주요 인텐트 없음, 기본 파이프라인 사용" }
                return null
            }
            // 신뢰도가 임계값 미만이면 프로필을 적용하지 않는다
            if (primary.confidence < confidenceThreshold) {
                logger.debug {
                    "IntentResolver: 신뢰도 부족 " +
                        "(인텐트=${primary.intentName}, " +
                        "신뢰도=${primary.confidence}, 임계값=$confidenceThreshold)"
                }
                return null
            }

            // 레지스트리에서 인텐트 정의를 조회한다
            val definition = registry.get(primary.intentName)
            if (definition == null) {
                logger.warn { "IntentResolver: 분류된 인텐트 '${primary.intentName}'가 레지스트리에 없음" }
                return null
            }

            // 보조 인텐트의 허용 도구를 주요 프로필에 병합한다
            val mergedProfile = mergeProfiles(definition.profile, result)

            logger.info {
                "IntentResolver: 인텐트 결정됨=${primary.intentName} " +
                    "신뢰도=${primary.confidence} 분류기=${result.classifiedBy} " +
                    "모델=${mergedProfile.model ?: "기본값"} 토큰비용=${result.tokenCost}"
            }

            return ResolvedIntent(
                intentName = primary.intentName,
                profile = mergedProfile,
                result = result
            )
        } catch (e: Exception) {
            // CancellationException은 반드시 재전파 — 구조화된 동시성 보존
            e.throwIfCancellation()
            logger.error(e) { "IntentResolver: 결정 실패, 기본 파이프라인 사용" }
            return null
        }
    }

    /**
     * 결정된 인텐트 프로필을 [AgentCommand]에 적용한다.
     *
     * non-null 프로필 필드만 커맨드를 오버라이드한다. 프로필이 null인 필드는
     * 원래 커맨드 값을 보존한다.
     *
     * 인텐트 메타데이터(이름, 신뢰도, 분류기, 토큰 비용 등)를 커맨드 metadata에 추가하여
     * 후속 처리(훅, 로깅)에서 인텐트 정보에 접근할 수 있게 한다.
     *
     * @param command 원래 에이전트 커맨드
     * @param resolved 결정된 인텐트
     * @return 프로필이 적용된 새 에이전트 커맨드
     */
    fun applyProfile(command: AgentCommand, resolved: ResolvedIntent): AgentCommand {
        val profile = resolved.profile
        val primaryConfidence = requireNotNull(resolved.result.primary) {
            "인텐트 프로필 적용 시 ResolvedIntent.result.primary는 null이 아니어야 함"
        }.confidence
        // 인텐트 관련 메타데이터를 구성한다
        val intentMetadata = mutableMapOf<String, Any>(
            METADATA_INTENT_NAME to resolved.intentName,
            METADATA_INTENT_CONFIDENCE to primaryConfidence,
            METADATA_INTENT_CLASSIFIED_BY to resolved.result.classifiedBy,
            METADATA_INTENT_TOKEN_COST to resolved.result.tokenCost,
            METADATA_INTENT_RESOLUTION_ATTEMPTED to true
        )
        profile.allowedTools?.let { tools ->
            // 로깅/직렬화를 위해 안정적인 정렬 리스트로 저장한다.
            intentMetadata[METADATA_INTENT_ALLOWED_TOOLS] = tools.toList().sorted()
        }
        return command.copy(
            systemPrompt = profile.systemPrompt ?: command.systemPrompt,
            model = profile.model ?: command.model,
            temperature = profile.temperature ?: command.temperature,
            maxToolCalls = profile.maxToolCalls ?: command.maxToolCalls,
            responseFormat = profile.responseFormat ?: command.responseFormat,
            metadata = command.metadata + intentMetadata
        )
    }

    /**
     * 주요 프로필과 보조 인텐트의 허용 도구를 병합한다.
     *
     * 다중 인텐트 입력(예: "환불하고 배송 확인해줘")의 경우,
     * 보조 인텐트의 도구가 주요 인텐트의 allowedTools에 추가된다.
     *
     * WHY: 사용자가 한 번에 여러 의도를 표현할 수 있으므로,
     * 보조 인텐트의 도구도 사용 가능해야 에이전트가 완전한 응답을 할 수 있다.
     * 단, 보조 인텐트도 신뢰도 임계값을 충족해야 도구가 포함된다.
     */
    private fun mergeProfiles(primaryProfile: IntentProfile, result: IntentResult): IntentProfile {
        if (result.secondary.isEmpty()) return primaryProfile

        // 신뢰도가 임계값 이상인 보조 인텐트의 허용 도구만 수집한다
        val secondaryTools = result.secondary
            .filter { it.confidence >= confidenceThreshold }
            .mapNotNull { registry.get(it.intentName)?.profile?.allowedTools }
            .flatten()
            .toSet()

        if (secondaryTools.isEmpty()) return primaryProfile

        val mergedTools = (primaryProfile.allowedTools ?: emptySet()) + secondaryTools
        return primaryProfile.copy(allowedTools = mergedTools.ifEmpty { null })
    }

    companion object {
        /** 인텐트 이름 메타데이터 키 */
        const val METADATA_INTENT_NAME = "intentName"
        /** 인텐트 신뢰도 메타데이터 키 */
        const val METADATA_INTENT_CONFIDENCE = "intentConfidence"
        /** 분류기 이름 메타데이터 키 */
        const val METADATA_INTENT_CLASSIFIED_BY = "intentClassifiedBy"
        /** 토큰 비용 메타데이터 키 */
        const val METADATA_INTENT_TOKEN_COST = "intentTokenCost"
        /** 허용 도구 목록 메타데이터 키 */
        const val METADATA_INTENT_ALLOWED_TOOLS = "intentAllowedTools"
        /** 인텐트 결정 시도 여부 메타데이터 키 */
        const val METADATA_INTENT_RESOLUTION_ATTEMPTED = "intentResolutionAttempted"
        /** 인텐트 결정 소요 시간(ms) 메타데이터 키 */
        const val METADATA_INTENT_RESOLUTION_DURATION_MS = "intentResolutionDurationMs"
    }
}

/**
 * 결정된 인텐트 — 분류 결과 + 적용 준비된 매칭 프로필.
 *
 * @param intentName 인텐트 이름
 * @param profile 적용할 파이프라인 설정 프로필
 * @param result 원본 분류 결과 (신뢰도, 토큰 비용 등 포함)
 */
data class ResolvedIntent(
    val intentName: String,
    val profile: IntentProfile,
    val result: IntentResult
)

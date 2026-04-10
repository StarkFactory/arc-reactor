package com.arc.reactor.agent.impl

/**
 * 시스템 프롬프트의 논리적 계층을 표현하는 enum.
 *
 * `docs/agent-work-directive.md` §3.4 Prompt Layer 분리 원칙에 따라
 * [SystemPromptBuilder]가 생성하는 시스템 프롬프트의 각 섹션을 6개 계층으로 분류한다.
 *
 * ## 계층의 의미
 *
 * - [IDENTITY]: 에이전트의 정체성 (who am I). 언어 규칙, 역할 선언.
 * - [SAFETY]: 안전 정책 (what I must not do). 읽기 전용 정책, 예약 문구 금지, 뮤테이션 거부.
 * - [TOOL_POLICY]: 도구 사용 정책 (how I call tools). 도구 호출 강제, 중복 호출 금지, few-shot 예시.
 * - [WORKSPACE_POLICY]: 워크스페이스 Grounding (how I treat workspace queries). 워크스페이스 vs 일반 질문 분류.
 * - [RESPONSE_STYLE]: 응답 스타일 (how I respond). 응답 품질, 출처 섹션, 응답 형식.
 * - [MEMORY_HINT]: 메모리/컨텍스트 힌트 (what I remember). 대화 이력 규칙, 사용자 메모리.
 *
 * ## 현재 동작
 *
 * 이 enum은 **runtime 동작에 영향을 주지 않는다**. 기존 [SystemPromptBuilder]의
 * 출력 텍스트는 byte-identical로 유지된다. 이 분류는 다음 세 가지 목적으로 존재한다:
 *
 * 1. **문서화**: 각 append 메서드가 어느 계층에 속하는지 [PromptLayerRegistry]로 조회 가능
 * 2. **테스트**: 계층 분류의 일관성과 완전성을 테스트로 강제
 * 3. **미래 확장**: 계층별 override, 선택적 비활성화, 워크스페이스 프로파일 등
 *
 * ## 캐시 영향
 *
 * [SystemPromptBuilder]의 출력 텍스트는 `CacheKeyBuilder.buildScopeFingerprint`의 SHA-256
 * 해시에 포함된다. 따라서 append 메서드의 출력 텍스트를 1바이트라도 변경하면 Redis 의미적
 * 캐시의 scopeFingerprint가 달라져 기존 캐시 엔트리가 전부 stale이 된다. 이 enum은 순수
 * 분류만 수행하여 이 위험을 회피한다.
 *
 * @see PromptLayerRegistry append 메서드 이름 → 계층 매핑
 * @see SystemPromptBuilder 실제 프롬프트 조합 빌더
 */
internal enum class PromptLayer {
    /** 에이전트의 정체성: 언어 규칙, 역할 선언, 자기 식별. */
    IDENTITY,

    /** 안전 정책: 읽기 전용 모드, 예약 문구 금지, 뮤테이션 거부, 최종 재확인. */
    SAFETY,

    /** 도구 사용 정책: 호출 강제, 중복 금지, few-shot 예시, 체이닝 에스컬레이션. */
    TOOL_POLICY,

    /** 워크스페이스 grounding: 워크스페이스 vs 일반 질문 분류, 도구 선호도. */
    WORKSPACE_POLICY,

    /** 응답 스타일: 구조화 요건, 출처 섹션, 응답 형식 (JSON/YAML). */
    RESPONSE_STYLE,

    /** 메모리/컨텍스트 힌트: 대화 이력 규칙, 사용자 메모리 주입, RAG 컨텍스트. */
    MEMORY_HINT;
}

/**
 * [SystemPromptBuilder]의 `append*` 메서드 이름을 [PromptLayer]로 매핑하는 레지스트리.
 *
 * ## 사용 예
 *
 * ```kotlin
 * val layer = PromptLayerRegistry.layerOf("appendLanguageRule")  // PromptLayer.IDENTITY
 * val safetyMethods = PromptLayerRegistry.methodsInLayer(PromptLayer.SAFETY)
 * val allClassified = PromptLayerRegistry.allClassifiedMethods()
 * ```
 *
 * ## 분류 원칙
 *
 * 각 메서드의 **주된(primary) 기능**으로 분류한다. 한 메서드가 여러 계층의 특성을 가질 때는
 * 가장 지배적인 의도를 선택한다. 예:
 * - `appendFewShotReadOnlyExamples`: 예시는 응답 스타일과 도구 사용을 모두 가르치지만,
 *   주된 목적은 **도구 호출 패턴 학습**이므로 [PromptLayer.TOOL_POLICY]로 분류한다.
 * - `appendPreventReservedPhrasesFinalReminder`: 응답 문구 제약이지만 안전 영역(예약 문구 출력
 *   방지)이 주된 목적이므로 [PromptLayer.SAFETY]로 분류한다.
 *
 * @see PromptLayer 계층 enum 정의
 */
internal object PromptLayerRegistry {

    /**
     * [SystemPromptBuilder]의 `build()` 경로에서 호출되는 append 메서드 이름 → 계층 매핑.
     *
     * 이 맵은 메인 grounding 경로(`buildGroundingInstruction`) 기준이다.
     * 계획 경로(`buildPlanningPrompt`)의 메서드는 [PLANNING_PATH]에 별도로 정의한다.
     */
    private val MAIN_PATH: Map<String, PromptLayer> = mapOf(
        // 정체성 계층
        "appendLanguageRule" to PromptLayer.IDENTITY,

        // 메모리/컨텍스트 힌트 계층
        "appendConversationHistoryRule" to PromptLayer.MEMORY_HINT,

        // 워크스페이스 grounding 분기
        "appendGeneralGroundingRule" to PromptLayer.WORKSPACE_POLICY,
        "appendWorkspaceGroundingRules" to PromptLayer.WORKSPACE_POLICY,

        // 안전 정책 계층
        "appendReadOnlyPolicy" to PromptLayer.SAFETY,
        "appendMutationRefusal" to PromptLayer.SAFETY,
        "appendPreventReservedPhrasesFinalReminder" to PromptLayer.SAFETY,

        // 도구 사용 정책 계층 (에러/재시도/중복 방지/힌트)
        "appendToolErrorRetryHint" to PromptLayer.TOOL_POLICY,
        "appendEmptyResultRetryHint" to PromptLayer.TOOL_POLICY,
        "appendToolChainingEscalationHint" to PromptLayer.TOOL_POLICY,
        "appendDuplicateToolCallPreventionHint" to PromptLayer.TOOL_POLICY,
        "appendConfluencePreferenceHint" to PromptLayer.TOOL_POLICY,
        "appendCompoundQuestionHint" to PromptLayer.TOOL_POLICY,

        // 도구 사용 정책 계층 (few-shot 학습)
        "appendFewShotExamples" to PromptLayer.TOOL_POLICY,
        "appendFewShotReadOnlyExamples" to PromptLayer.TOOL_POLICY,

        // 도구 사용 정책 계층 (도구 호출 강제)
        "appendConfluenceToolForcing" to PromptLayer.TOOL_POLICY,
        "appendInternalDocSearchForcing" to PromptLayer.TOOL_POLICY,
        "appendTeamStatusForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkToolForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkBriefingForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkStandupForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkReleaseRiskForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkHybridPriorityForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkReleaseReadinessForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkPersonalToolForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkProfileAndOwnerForcing" to PromptLayer.TOOL_POLICY,
        "appendWorkContextToolForcing" to PromptLayer.TOOL_POLICY,
        "appendJiraToolForcing" to PromptLayer.TOOL_POLICY,
        "appendBitbucketToolForcing" to PromptLayer.TOOL_POLICY,
        "appendSwaggerToolForcing" to PromptLayer.TOOL_POLICY,
        "appendSwaggerFallbackForcing" to PromptLayer.TOOL_POLICY,
        "appendToolForcing" to PromptLayer.TOOL_POLICY,
        "appendToolChainForcing" to PromptLayer.TOOL_POLICY,
        "appendLoadedSpecForcing" to PromptLayer.TOOL_POLICY,

        // 응답 스타일 계층
        "appendResponseQualityInstruction" to PromptLayer.RESPONSE_STYLE,
        "appendSourcesInstruction" to PromptLayer.RESPONSE_STYLE
    )

    /**
     * 계획 단계 [SystemPromptBuilder.buildPlanningPrompt] 경로의 append 메서드 분류.
     * 계획 경로는 JSON 계획을 생성하는 별도 경로이므로 메인 경로와 분리한다.
     */
    private val PLANNING_PATH: Map<String, PromptLayer> = mapOf(
        "appendPlanningRole" to PromptLayer.IDENTITY,
        "appendPlanningToolContext" to PromptLayer.TOOL_POLICY,
        "appendPlanningOutputSchema" to PromptLayer.RESPONSE_STYLE,
        "appendPlanningConstraints" to PromptLayer.SAFETY,
        "appendPlanningUserRequest" to PromptLayer.MEMORY_HINT
    )

    /** 두 경로를 합친 전체 분류. 중복 키가 없어야 한다. */
    private val ALL: Map<String, PromptLayer> = MAIN_PATH + PLANNING_PATH

    /**
     * 주어진 메서드 이름의 [PromptLayer]를 반환한다.
     * 등록되지 않은 이름이면 `null`을 반환한다.
     */
    fun layerOf(methodName: String): PromptLayer? = ALL[methodName]

    /** 특정 계층에 속한 모든 메서드 이름을 반환한다. */
    fun methodsInLayer(layer: PromptLayer): Set<String> =
        ALL.entries.asSequence().filter { it.value == layer }.map { it.key }.toSet()

    /** 레지스트리에 등록된 모든 메서드 이름을 반환한다. */
    fun allClassifiedMethods(): Set<String> = ALL.keys

    /** 메인 grounding 경로에 등록된 메서드 이름을 반환한다. */
    fun mainPathMethods(): Set<String> = MAIN_PATH.keys

    /** 계획 경로에 등록된 메서드 이름을 반환한다. */
    fun planningPathMethods(): Set<String> = PLANNING_PATH.keys
}

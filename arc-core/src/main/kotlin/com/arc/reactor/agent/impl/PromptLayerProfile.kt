package com.arc.reactor.agent.impl

/**
 * 시스템 프롬프트의 **계층 활성화 선언** — 어떤 [PromptLayer]들이 켜져 있는지 표현한다.
 *
 * R220에서 도입한 [PromptLayer]/[PromptLayerRegistry]는 각 `append*` 메서드를 6개 계층으로
 * 분류했지만, 실제 runtime 동작에는 전혀 관여하지 않았다 (byte-identical 원칙). R235는
 * 이 기반 위에 **선언적 프로파일** 레이어를 얹어, 사용자가 "이 워크스페이스는 TOOL_POLICY
 * 계층을 사용하지 않는다" 같은 의도를 표현할 수 있게 한다.
 *
 * ## 현재 동작 (R235)
 *
 * 이 클래스는 여전히 **passive** 이다. `SystemPromptBuilder`의 출력은 수정되지 않으며,
 * R220 Golden snapshot 5개 해시는 그대로 유지된다. 프로파일은 다음 용도로만 쓰인다:
 *
 * 1. **선언**: 사용자가 의도한 계층 구성을 명시적으로 표현
 * 2. **Introspection**: [PromptLayerRegistry.filterMethodsByProfile]로 특정 프로파일에
 *    속한 메서드 목록 조회
 * 3. **미래 확장 기반**: 향후 opt-in 프로퍼티로 profile-based filtering을 SystemPromptBuilder
 *    에 실제 적용할 수 있는 기반
 *
 * ## 미래 가능성 (R236+ 또는 사용자 확장)
 *
 * 예: `SystemPromptBuilder.build(profile = PromptLayerProfile.WORKSPACE_FOCUSED)` 같은
 * 오버로드가 도입된다면, 해당 프로파일에 속하지 않은 `append*` 메서드는 호출되지 않는다.
 * 그러나 이 경우 출력이 달라지므로 **cache flush 이벤트**로 명시적 사용자 승인이 필요하다.
 * R235는 아직 그 단계에 도달하지 않았다.
 *
 * ## 사용 예
 *
 * ```kotlin
 * // 전체 프로파일 (R220 기본 동작과 동일)
 * val all = PromptLayerProfile.ALL_LAYERS
 *
 * // 최소 프로파일: 정체성 + 안전 + 메모리 힌트만
 * val minimal = PromptLayerProfile.MINIMAL
 *
 * // 워크스페이스 중심: 도구/응답/워크스페이스 정책 강조
 * val workspace = PromptLayerProfile.WORKSPACE_FOCUSED
 *
 * // 커스텀: 특정 계층만 선택
 * val custom = PromptLayerProfile(
 *     enabledLayers = setOf(
 *         PromptLayer.IDENTITY,
 *         PromptLayer.SAFETY,
 *         PromptLayer.TOOL_POLICY
 *     )
 * )
 *
 * // 빌더 스타일
 * val built = PromptLayerProfile.ALL_LAYERS
 *     .withoutLayer(PromptLayer.RESPONSE_STYLE)
 *     .withoutLayer(PromptLayer.MEMORY_HINT)
 *
 * // Registry 조회
 * val enabledMethods = PromptLayerRegistry.filterMethodsByProfile(minimal)
 * // → {"appendLanguageRule", "appendConversationHistoryRule",
 * //    "appendReadOnlyPolicy", "appendMutationRefusal",
 * //    "appendPreventReservedPhrasesFinalReminder"}
 * ```
 *
 * ## 3대 최상위 제약 준수
 *
 * - **MCP**: 도구 경로 전혀 미수정 — 프로파일은 순수 데이터 모델
 * - **Redis 캐시**: `SystemPromptBuilder` 출력 byte-identical 유지 — 프로파일은 introspection
 *   전용, 실제 프롬프트 생성에 관여하지 않음. R220 Golden snapshot 5개 해시 불변 보장
 * - **컨텍스트 관리**: `MemoryStore`/`Trimmer` 미수정
 *
 * @property enabledLayers 이 프로파일에서 활성화된 [PromptLayer] 집합.
 *   빈 집합도 허용 (모든 메서드 비활성 프로파일 — 주로 테스트/문서화용)
 *
 * @see PromptLayer 6개 계층 enum
 * @see PromptLayerRegistry append 메서드 → 계층 매핑
 */
internal data class PromptLayerProfile(
    val enabledLayers: Set<PromptLayer>
) {

    /**
     * 주어진 계층이 이 프로파일에서 활성화되어 있는지 확인한다.
     */
    fun isEnabled(layer: PromptLayer): Boolean = layer in enabledLayers

    /**
     * 현재 프로파일에 지정한 계층을 **추가**한 새 프로파일을 반환한다.
     * 이미 포함되어 있으면 동등한 프로파일을 반환한다.
     */
    fun withLayer(layer: PromptLayer): PromptLayerProfile =
        PromptLayerProfile(enabledLayers + layer)

    /**
     * 현재 프로파일에서 지정한 계층을 **제거**한 새 프로파일을 반환한다.
     * 포함되지 않은 계층이면 동등한 프로파일을 반환한다.
     */
    fun withoutLayer(layer: PromptLayer): PromptLayerProfile =
        PromptLayerProfile(enabledLayers - layer)

    /**
     * 여러 계층을 한 번에 추가한 새 프로파일을 반환한다.
     */
    fun withLayers(vararg layers: PromptLayer): PromptLayerProfile =
        PromptLayerProfile(enabledLayers + layers.toSet())

    /**
     * 여러 계층을 한 번에 제거한 새 프로파일을 반환한다.
     */
    fun withoutLayers(vararg layers: PromptLayer): PromptLayerProfile =
        PromptLayerProfile(enabledLayers - layers.toSet())

    /**
     * 비활성화된 계층 집합을 반환한다 (전체 계층 - 활성 계층).
     */
    fun disabledLayers(): Set<PromptLayer> =
        PromptLayer.values().toSet() - enabledLayers

    /**
     * 이 프로파일이 모든 계층을 활성화하는지 확인한다.
     * `ALL_LAYERS`와 의미론적으로 동등하다.
     */
    fun isFullyEnabled(): Boolean = enabledLayers.size == PromptLayer.values().size

    /**
     * 이 프로파일이 하나의 계층도 활성화하지 않는지 확인한다.
     */
    fun isEmpty(): Boolean = enabledLayers.isEmpty()

    companion object {
        /**
         * 모든 6개 계층이 활성화된 프로파일.
         * R220의 기본 동작(모든 `append*` 메서드가 그대로 호출됨)과 의미론적으로 동등하다.
         */
        val ALL_LAYERS: PromptLayerProfile = PromptLayerProfile(
            enabledLayers = PromptLayer.values().toSet()
        )

        /**
         * 최소 프로파일 — 정체성, 안전, 메모리 힌트만 활성화.
         *
         * 용도: 외부 도구를 사용하지 않는 단순 Q&A 봇이나, 최대한 간결한 프롬프트를 원하는
         * 경우. 도구 정책/워크스페이스 정책/응답 스타일 지시가 빠지므로 LLM이 더 자유롭게
         * 응답한다.
         */
        val MINIMAL: PromptLayerProfile = PromptLayerProfile(
            enabledLayers = setOf(
                PromptLayer.IDENTITY,
                PromptLayer.SAFETY,
                PromptLayer.MEMORY_HINT
            )
        )

        /**
         * 워크스페이스 중심 프로파일 — 도구/응답 스타일/워크스페이스 정책을 강조.
         *
         * 용도: Jira/Confluence/Bitbucket 업무 쿼리에 집중된 에이전트. 정체성과 안전 정책은
         * 유지하면서 워크스페이스 정책/도구 정책/응답 스타일을 강조한다.
         */
        val WORKSPACE_FOCUSED: PromptLayerProfile = PromptLayerProfile(
            enabledLayers = setOf(
                PromptLayer.IDENTITY,
                PromptLayer.SAFETY,
                PromptLayer.TOOL_POLICY,
                PromptLayer.WORKSPACE_POLICY,
                PromptLayer.RESPONSE_STYLE
            )
        )

        /**
         * 안전 최소 프로파일 — 정체성과 안전 정책만 활성화.
         *
         * 용도: 메모리도 사용하지 않는 stateless 단순 요청. 예: 프롬프트 실험, 벤치마크
         * 시나리오. 실사용보다 연구/테스트 환경에 적합.
         */
        val SAFETY_ONLY: PromptLayerProfile = PromptLayerProfile(
            enabledLayers = setOf(
                PromptLayer.IDENTITY,
                PromptLayer.SAFETY
            )
        )

        /**
         * 빈 프로파일 — 어떤 계층도 활성화되지 않은 상태.
         *
         * 용도: 주로 테스트 및 문서화용. 실제 에이전트 경로에서 이 프로파일을 사용하면
         * 아무 프롬프트도 주입되지 않으므로 의도적이지 않다면 사용하지 말 것.
         */
        val EMPTY: PromptLayerProfile = PromptLayerProfile(enabledLayers = emptySet())
    }
}

/**
 * [PromptLayerRegistry]에 프로파일 기반 메서드 필터링을 추가하는 확장 함수.
 *
 * @param profile 활성화된 계층 집합
 * @return 프로파일의 활성 계층에 속한 모든 `append*` 메서드 이름 집합
 *
 * ## 동작
 *
 * 메인 경로와 계획 경로 두 맵을 모두 검사하여, 각 메서드의 분류 계층이
 * [PromptLayerProfile.enabledLayers]에 포함되는지 확인한다.
 *
 * ## 예시
 *
 * ```kotlin
 * val minimalMethods = PromptLayerRegistry.filterMethodsByProfile(PromptLayerProfile.MINIMAL)
 * // → {"appendLanguageRule" (IDENTITY),
 * //    "appendConversationHistoryRule" (MEMORY_HINT),
 * //    "appendReadOnlyPolicy" (SAFETY),
 * //    "appendMutationRefusal" (SAFETY),
 * //    "appendPreventReservedPhrasesFinalReminder" (SAFETY),
 * //    "appendPlanningRole" (IDENTITY),
 * //    "appendPlanningConstraints" (SAFETY),
 * //    "appendPlanningUserRequest" (MEMORY_HINT)}
 * ```
 *
 * ## 불변식
 *
 * - `filterMethodsByProfile(PromptLayerProfile.ALL_LAYERS)` ==
 *   `PromptLayerRegistry.allClassifiedMethods()`
 * - `filterMethodsByProfile(PromptLayerProfile.EMPTY)` == `emptySet()`
 * - 프로파일의 `enabledLayers`에 포함되지 않은 계층의 메서드는 반환되지 않음
 */
internal fun PromptLayerRegistry.filterMethodsByProfile(
    profile: PromptLayerProfile
): Set<String> {
    if (profile.isEmpty()) return emptySet()
    return allClassifiedMethods()
        .filter { name ->
            val layer = layerOf(name) ?: return@filter false
            profile.isEnabled(layer)
        }
        .toSet()
}

/**
 * 특정 메서드가 주어진 프로파일에서 활성화되는지 확인하는 편의 함수.
 *
 * @return 메서드가 레지스트리에 등록되어 있고 해당 계층이 프로파일에서 활성화되어 있으면
 *   `true`, 그 외에는 `false`
 */
internal fun PromptLayerRegistry.isMethodEnabled(
    methodName: String,
    profile: PromptLayerProfile
): Boolean {
    val layer = layerOf(methodName) ?: return false
    return profile.isEnabled(layer)
}

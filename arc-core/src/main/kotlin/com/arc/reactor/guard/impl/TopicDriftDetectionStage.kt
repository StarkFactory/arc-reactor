package com.arc.reactor.guard.impl

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.memory.MemoryStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 토픽 드리프트 탐지 단계 (order=10)
 *
 * Crescendo 공격을 방어한다 — 다중 턴에 걸쳐 점진적으로 민감도를 올리는
 * 프로그레시브 탈옥(progressive jailbreak) 기법이다.
 *
 * ## Crescendo 공격 패턴 (왜 이 단계가 필요한가)
 * 공격자가 한 번에 직접적인 악의적 요청을 하지 않고, 여러 턴에 걸쳐
 * 점진적으로 민감한 주제로 유도한다:
 * ```
 * 턴 1: "가상의 시나리오를 생각해볼까요?" (hypothetical)
 * 턴 2: "만약 이런 상황이라면?" (what if)
 * 턴 3: "연구 목적으로 알고 싶어요" (for research)
 * 턴 4: "구체적인 단계를 알려주세요" (step by step)
 * 턴 5: "안전장치를 우회해서..." (bypass)
 * ```
 *
 * ## 탐지 방식
 * 1. MemoryStore에서 대화 이력을 로드한다 (sessionId 기반)
 * 2. 마지막 N턴(슬라이딩 윈도우) + 현재 메시지를 분석한다
 * 3. 에스컬레이션 패턴 가중치를 계산한다 (후반 턴일수록 높은 가중치)
 * 4. 정규화된 점수가 임계값을 초과하면 거부한다
 *
 * ## Order가 10인 이유
 * 기본 5단계(1~5) 이후에 실행되도록 10으로 설정한다.
 * 이미 보안 검사를 통과한 "개별적으로는 정상"인 요청들의
 * 대화 흐름 패턴을 분석하기 위함이다.
 *
 * @param memoryStore 대화 이력 저장소 (선택사항, null이면 항상 허용)
 * @param maxDriftScore 거부 임계값 (기본값: 0.7, 0.0~1.0)
 * @param windowSize 분석할 슬라이딩 윈도우 크기 (기본값: 5턴)
 *
 * @see com.arc.reactor.guard.GuardStage Guard 단계 인터페이스
 */
class TopicDriftDetectionStage(
    private val memoryStore: MemoryStore? = null,
    private val maxDriftScore: Double = 0.7,
    private val windowSize: Int = 5
) : GuardStage {

    override val stageName = "TopicDriftDetection"
    override val order = 10

    override suspend fun check(command: GuardCommand): GuardResult {
        // ── 단계 1: 대화 이력 로드 ──
        val history = loadConversationHistory(command)
        if (history.isEmpty()) return GuardResult.Allowed.DEFAULT

        // ── 단계 2: 슬라이딩 윈도우 구성 ──
        // 마지막 (windowSize - 1)개 이력 + 현재 메시지
        val window = history.takeLast(windowSize - 1) + command.text

        // ── 단계 3: 드리프트 점수 계산 ──
        val score = calculateDriftScore(window)

        // ── 단계 4: 임계값 비교하여 거부/허용 결정 ──
        return if (score >= maxDriftScore) {
            logger.warn { "Topic drift detected: score=$score threshold=$maxDriftScore" }
            GuardResult.Rejected(
                reason = "Conversation pattern indicates potential jailbreak attempt",
                category = RejectionCategory.PROMPT_INJECTION
            )
        } else {
            GuardResult.Allowed.DEFAULT
        }
    }

    /**
     * 대화 이력을 로드한다.
     *
     * 우선순위:
     * 1. metadata에 명시적으로 전달된 conversationHistory (테스트나 커스텀 와이어링용)
     * 2. MemoryStore에서 sessionId로 로드한 이력 (실제 운영 환경)
     */
    private fun loadConversationHistory(command: GuardCommand): List<String> {
        // 우선순위 1: metadata에 명시적으로 전달된 이력
        @Suppress("UNCHECKED_CAST")
        val explicit = command.metadata["conversationHistory"] as? List<String>
        if (!explicit.isNullOrEmpty()) return explicit

        // 우선순위 2: MemoryStore에서 sessionId로 로드
        val sessionId = command.metadata["sessionId"]?.toString() ?: return emptyList()
        val memory = memoryStore?.get(sessionId) ?: return emptyList()
        return memory.getHistory()
            .filter { it.role.name == "USER" }
            .map { it.content }
    }

    /**
     * 슬라이딩 윈도우 내 에스컬레이션 패턴의 가중 점수를 계산한다.
     *
     * 왜 후반 턴에 높은 가중치를 주는가:
     * Crescendo 공격의 핵심은 "점진적 에스컬레이션"이므로,
     * 대화 후반부의 에스컬레이션 패턴이 더 위험하다.
     * index에 비례하여 가중치를 증가시킨다 (1.0 + index * 0.2).
     *
     * @param window 분석할 메시지 리스트
     * @return 정규화된 드리프트 점수 (0.0~1.0)
     */
    private fun calculateDriftScore(window: List<String>): Double {
        if (window.size < 2) return 0.0

        var totalScore = 0.0
        for ((index, turn) in window.withIndex()) {
            val turnScore = ESCALATION_PATTERNS.entries.sumOf { (pattern, weight) ->
                if (pattern.containsMatchIn(turn)) weight else 0.0
            }
            // 후반 턴일수록 높은 가중치 (에스컬레이션 감지)
            totalScore += turnScore * (1.0 + index * 0.2)
        }

        // 윈도우 크기로 정규화하여 0.0~1.0 범위로 제한
        return (totalScore / window.size).coerceAtMost(1.0)
    }

    companion object {
        /**
         * 에스컬레이션 패턴 맵: Regex → 가중치
         *
         * 5단계 에스컬레이션 레벨로 구성된다.
         * 각 레벨의 가중치는 해당 패턴이 나타날 때 부여되는 위험도를 나타낸다.
         */
        private val ESCALATION_PATTERNS: Map<Regex, Double> = mapOf(
            // 레벨 1: 가상/이론적 프레이밍 (낮은 가중치)
            // "가상의 시나리오", "이론적으로" 등 — 자체로는 무해하지만 에스컬레이션 시작점
            Regex("(?i)(hypothetical|theoretical|imagine|thought experiment)") to 0.1,

            // 레벨 2: 조건/추측 (중하 가중치)
            // "만약에", "가정해보면" 등 — 시나리오를 구체화하는 단계
            Regex("(?i)(what if|suppose|let's say|assume)") to 0.15,

            // 레벨 3: 학술/연구 프레이밍 (중 가중치)
            // "연구 목적으로", "교육적 목적" 등 — 정당성을 부여하려는 시도
            Regex("(?i)(for research|academic purposes|educational|study)") to 0.2,

            // 레벨 4: 절차적 요청 (중상 가중치)
            // "단계별로", "구체적인 방법" 등 — 실행 가능한 정보를 요구하는 단계
            Regex("(?i)(step by step|detailed instructions|how exactly|specific steps)") to 0.25,

            // 레벨 5: 직접적 우회/재정의 (높은 가중치)
            // "우회", "규칙 무시" 등 — 명시적 탈옥 시도
            Regex("(?i)(bypass|override|ignore.*rules|disable.*safety|remove.*restrictions)") to 0.4
        )
    }
}

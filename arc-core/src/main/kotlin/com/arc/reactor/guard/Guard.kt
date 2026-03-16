package com.arc.reactor.guard

import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult

/**
 * 요청 Guard 인터페이스
 *
 * 들어오는 요청을 검증하여 허용(Allowed) 또는 거부(Rejected) 결과를 반환한다.
 * Guard 파이프라인은 AI 에이전트를 악용, Prompt Injection 공격,
 * 비인가 접근으로부터 보호하는 보안 가드레일을 제공한다.
 *
 * ## 5+2단계 Guard 파이프라인
 *
 * 기본 5단계에 사전(Unicode 정규화)·사후(Topic Drift 감지) 단계를 더한 구조:
 * ```
 * 요청 → [0.UnicodeNormalization] → [1.RateLimit] → [2.InputValidation]
 *      → [3.InjectionDetection] → [4.Classification] → [5.Permission]
 *      → [10.TopicDriftDetection] → 허용/거부
 * ```
 *
 * - **order 0**: Unicode 정규화 — 후속 단계가 깨끗한 ASCII 텍스트로 패턴 매칭할 수 있도록 전처리
 * - **order 1~5**: 핵심 보안 단계 (내장)
 * - **order 10+**: 사용자 정의 확장 단계
 *
 * ## 사용 예시
 * ```kotlin
 * val guard = GuardPipeline(listOf(
 *     DefaultRateLimitStage(requestsPerMinute = 10),
 *     DefaultInputValidationStage(maxLength = 10000),
 *     DefaultInjectionDetectionStage()
 * ))
 *
 * val result = guard.guard(GuardCommand(userId = "user-123", text = "Hello"))
 * when (result) {
 *     is GuardResult.Allowed -> processRequest()
 *     is GuardResult.Rejected -> handleRejection(result.reason)
 * }
 * ```
 *
 * @see GuardStage 커스텀 단계 구현 인터페이스
 * @see com.arc.reactor.guard.impl.GuardPipeline 기본 구현체
 */
interface RequestGuard {
    /**
     * 모든 Guard 단계를 거쳐 요청을 검증한다.
     *
     * @param command 검증할 요청 커맨드
     * @return 검증 통과 시 [GuardResult.Allowed], 거부 시 [GuardResult.Rejected]
     */
    suspend fun guard(command: GuardCommand): GuardResult
}

/**
 * Guard 단계 인터페이스
 *
 * Guard 파이프라인의 개별 단계를 나타낸다.
 * 각 단계는 요청의 특정 측면(속도 제한, 입력 크기 등)을 검사하여
 * 통과시키거나 거부한다.
 *
 * ## 동작 원칙: Fail-Close
 * Guard는 보안 컴포넌트이므로 **항상 fail-close**로 동작한다.
 * 단계에서 예외가 발생하면 요청을 거부한다.
 * 이는 fail-open으로 동작하는 [com.arc.reactor.hook.HookExecutor]와 대조적이다.
 *
 * ## 커스텀 단계 구현 방법
 * ```kotlin
 * @Component
 * class CustomBusinessRuleStage : GuardStage {
 *     override val stageName = "BusinessRule"
 *     override val order = 35  // InjectionDetection(3) 이후, Classification(4) 이전
 *
 *     override suspend fun check(command: GuardCommand): GuardResult {
 *         if (!isAllowedByBusinessRules(command.text)) {
 *             return GuardResult.Rejected(
 *                 reason = "Request violates business rules",
 *                 category = RejectionCategory.UNAUTHORIZED,
 *                 stage = stageName
 *             )
 *         }
 *         return GuardResult.Allowed.DEFAULT
 *     }
 * }
 * ```
 *
 * ## Order 값 가이드
 * - 0: UnicodeNormalization (내장, 전처리)
 * - 1~5: 핵심 보안 단계 (내장)
 * - 10+: 사용자 정의 확장 단계 (TopicDriftDetection 등)
 * - 커스텀 단계는 10 이상 값을 권장
 *
 * @property stageName 이 단계의 고유 식별자 (거부 메시지에 사용)
 * @property order 실행 순서 (낮을수록 먼저 실행)
 * @property enabled 이 단계의 활성화 여부 (기본값: true)
 *
 * @see com.arc.reactor.guard.impl.GuardPipeline 단계들을 실행하는 파이프라인
 */
interface GuardStage {
    /** 로깅 및 거부 메시지에 사용되는 단계 식별자 */
    val stageName: String

    /** 실행 우선순위 — 낮은 값이 먼저 실행됨 */
    val order: Int

    /** 이 단계의 활성화 여부 */
    val enabled: Boolean get() = true

    /**
     * 요청을 검증한다.
     *
     * @param command 검증할 요청 커맨드
     * @return 통과 시 [GuardResult.Allowed], 파이프라인 중단 시 [GuardResult.Rejected]
     */
    suspend fun check(command: GuardCommand): GuardResult
}

/**
 * 1단계: 속도 제한 (Rate Limiting)
 *
 * 사용자별 요청 빈도를 제한하여 남용을 방지한다.
 * 가장 먼저 실행되어야 하므로 order=1로 설정한다.
 * 왜 1번인가: 비용이 가장 낮은 검사이며, 악의적 요청의 대량 전송을 조기 차단한다.
 *
 * @see com.arc.reactor.guard.impl.DefaultRateLimitStage 기본 구현체
 */
interface RateLimitStage : GuardStage {
    override val stageName: String get() = "RateLimit"
    override val order: Int get() = 1
}

/**
 * 2단계: 입력 검증 (Input Validation)
 *
 * 입력의 형식, 길이, 구조를 검증한다.
 * 왜 2번인가: 너무 길거나 빈 입력을 조기에 차단하여
 * 후속 단계(Injection 탐지 등)의 불필요한 처리를 방지한다.
 *
 * @see com.arc.reactor.guard.impl.DefaultInputValidationStage 기본 구현체
 */
interface InputValidationStage : GuardStage {
    override val stageName: String get() = "InputValidation"
    override val order: Int get() = 2
}

/**
 * 3단계: Prompt Injection 탐지
 *
 * Prompt Injection 공격을 탐지하고 차단한다.
 * 패턴 매칭과 휴리스틱을 사용하여 악의적 입력을 식별한다.
 * 왜 3번인가: 입력 검증을 통과한 정상 길이의 텍스트에 대해서만
 * 비용이 높은 패턴 매칭을 수행하기 위함이다.
 *
 * @see com.arc.reactor.guard.impl.DefaultInjectionDetectionStage 기본 구현체
 * @see com.arc.reactor.guard.InjectionPatterns 공유 Injection 패턴 목록
 */
interface InjectionDetectionStage : GuardStage {
    override val stageName: String get() = "InjectionDetection"
    override val order: Int get() = 3
}

/**
 * 4단계: 콘텐츠 분류 (Classification)
 *
 * 토픽 필터링이나 라우팅을 위해 콘텐츠를 분류한다.
 * 주제에서 벗어난 요청을 감지하거나 전문 핸들러로 라우팅하는 데 사용할 수 있다.
 * 왜 4번인가: 보안 검사(1~3)를 모두 통과한 안전한 입력에 대해서만
 * 분류 로직(잠재적으로 LLM 호출 포함)을 실행하기 위함이다.
 *
 * @see com.arc.reactor.guard.impl.CompositeClassificationStage 규칙+LLM 복합 구현체
 * @see com.arc.reactor.guard.impl.RuleBasedClassificationStage 규칙 기반 구현체
 * @see com.arc.reactor.guard.impl.LlmClassificationStage LLM 기반 구현체
 */
interface ClassificationStage : GuardStage {
    override val stageName: String get() = "Classification"
    override val order: Int get() = 4
}

/**
 * 5단계: 권한 확인 (Permission Check)
 *
 * 요청된 작업에 대한 사용자 인가를 확인한다.
 * RBAC 또는 커스텀 권한 시스템과 통합할 수 있다.
 * 왜 5번(마지막)인가: 모든 보안/분류 검사를 통과한 후에
 * 비즈니스 로직 수준의 권한 확인을 수행하기 위함이다.
 *
 * 기본 order: 5
 */
interface PermissionStage : GuardStage {
    override val stageName: String get() = "Permission"
    override val order: Int get() = 5
}

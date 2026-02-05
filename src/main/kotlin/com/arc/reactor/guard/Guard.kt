package com.arc.reactor.guard

import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult

/**
 * Request Guard 인터페이스
 *
 * 요청을 검사하여 허용 또는 거부 결과를 반환.
 *
 * ## 5단계 가드레일 파이프라인
 *
 * ```
 * Request → [1.RateLimit] → [2.InputValidation] → [3.InjectionDetection]
 *        → [4.Classification] → [5.Permission] → Allowed/Rejected
 * ```
 */
interface RequestGuard {
    /**
     * 요청 검사
     *
     * @param command 검사할 요청
     * @return 검사 결과 (Allowed 또는 Rejected)
     */
    suspend fun guard(command: GuardCommand): GuardResult
}

/**
 * 가드레일 단계 인터페이스
 *
 * 각 단계는 요청을 검사하고 다음 단계로 전달하거나 거부.
 */
interface GuardStage {
    /** 단계 이름 */
    val stageName: String

    /** 단계 순서 (낮을수록 먼저) */
    val order: Int

    /** 단계 활성화 여부 */
    val enabled: Boolean get() = true

    /**
     * 검사 실행
     *
     * @return Continue면 다음 단계, Reject면 중단
     */
    suspend fun check(command: GuardCommand): GuardResult
}

/**
 * 1단계: Rate Limiting
 */
interface RateLimitStage : GuardStage {
    override val stageName: String get() = "RateLimit"
    override val order: Int get() = 1
}

/**
 * 2단계: Input Validation
 */
interface InputValidationStage : GuardStage {
    override val stageName: String get() = "InputValidation"
    override val order: Int get() = 2
}

/**
 * 3단계: Prompt Injection Detection
 */
interface InjectionDetectionStage : GuardStage {
    override val stageName: String get() = "InjectionDetection"
    override val order: Int get() = 3
}

/**
 * 4단계: Content Classification
 */
interface ClassificationStage : GuardStage {
    override val stageName: String get() = "Classification"
    override val order: Int get() = 4
}

/**
 * 5단계: Permission Check
 */
interface PermissionStage : GuardStage {
    override val stageName: String get() = "Permission"
    override val order: Int get() = 5
}

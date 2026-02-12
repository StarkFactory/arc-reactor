package com.arc.reactor.guard

import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult

/**
 * Request Guard Interface
 *
 * Validates incoming requests and returns allowed/rejected results.
 * The guard pipeline provides security guardrails to protect the AI agent
 * from abuse, injection attacks, and unauthorized access.
 *
 * ## 5-Stage Guardrail Pipeline
 *
 * ```
 * Request → [1.RateLimit] → [2.InputValidation] → [3.InjectionDetection]
 *        → [4.Classification] → [5.Permission] → Allowed/Rejected
 * ```
 *
 * ## Example Usage
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
 * @see GuardStage for implementing custom stages
 * @see com.arc.reactor.guard.impl.GuardPipeline for default implementation
 */
interface RequestGuard {
    /**
     * Validates the request against all guard stages.
     *
     * @param command The request to validate
     * @return GuardResult.Allowed if validation passes, GuardResult.Rejected otherwise
     */
    suspend fun guard(command: GuardCommand): GuardResult
}

/**
 * Guard Stage Interface
 *
 * Represents a single stage in the guard pipeline.
 * Each stage checks a specific aspect of the request (rate limit, input size, etc.)
 * and either allows it to proceed or rejects it.
 *
 * ## Implementing Custom Stages
 * ```kotlin
 * @Component
 * class CustomBusinessRuleStage : GuardStage {
 *     override val stageName = "BusinessRule"
 *     override val order = 35  // After InjectionDetection (30)
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
 * @property stageName Unique identifier for this stage
 * @property order Execution order (lower values execute first)
 * @property enabled Whether this stage is active (default: true)
 */
interface GuardStage {
    /** Stage identifier used in rejection messages */
    val stageName: String

    /** Execution priority - lower values execute first */
    val order: Int

    /** Whether this stage is enabled */
    val enabled: Boolean get() = true

    /**
     * Validates the request.
     *
     * @param command The request to validate
     * @return GuardResult.Allowed to continue, GuardResult.Rejected to stop pipeline
     */
    suspend fun check(command: GuardCommand): GuardResult
}

/**
 * Stage 1: Rate Limiting
 *
 * Prevents abuse by limiting request frequency per user.
 * Default order: 1 (executes first)
 */
interface RateLimitStage : GuardStage {
    override val stageName: String get() = "RateLimit"
    override val order: Int get() = 1
}

/**
 * Stage 2: Input Validation
 *
 * Validates input format, length, and structure.
 * Default order: 2
 */
interface InputValidationStage : GuardStage {
    override val stageName: String get() = "InputValidation"
    override val order: Int get() = 2
}

/**
 * Stage 3: Prompt Injection Detection
 *
 * Detects and blocks prompt injection attacks.
 * Uses pattern matching and heuristics to identify malicious inputs.
 * Default order: 3
 */
interface InjectionDetectionStage : GuardStage {
    override val stageName: String get() = "InjectionDetection"
    override val order: Int get() = 3
}

/**
 * Stage 4: Content Classification
 *
 * Classifies content for topic filtering or routing.
 * Can be used to detect off-topic requests or route to specialized handlers.
 * Default order: 4
 */
interface ClassificationStage : GuardStage {
    override val stageName: String get() = "Classification"
    override val order: Int get() = 4
}

/**
 * Stage 5: Permission Check
 *
 * Verifies user authorization for the requested operation.
 * Integrates with RBAC or custom permission systems.
 * Default order: 5 (executes last)
 */
interface PermissionStage : GuardStage {
    override val stageName: String get() = "Permission"
    override val order: Int get() = 5
}

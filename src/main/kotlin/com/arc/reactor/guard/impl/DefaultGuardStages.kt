package com.arc.reactor.guard.impl

import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.InjectionDetectionStage
import com.arc.reactor.guard.InputValidationStage
import com.arc.reactor.guard.PermissionStage
import com.arc.reactor.guard.RateLimitStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * 기본 Rate Limit 구현
 *
 * Caffeine 캐시 기반 분당/시간당 제한.
 */
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100
) : RateLimitStage {

    private val minuteCache: Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build()

    private val hourCache: Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build()

    override suspend fun check(command: GuardCommand): GuardResult {
        val userId = command.userId

        // 분당 체크
        val minuteCount = minuteCache.get(userId) { AtomicInteger(0) }
        if (minuteCount.incrementAndGet() > requestsPerMinute) {
            return GuardResult.Rejected(
                reason = "Rate limit exceeded: $requestsPerMinute requests per minute",
                category = RejectionCategory.RATE_LIMITED
            )
        }

        // 시간당 체크
        val hourCount = hourCache.get(userId) { AtomicInteger(0) }
        if (hourCount.incrementAndGet() > requestsPerHour) {
            return GuardResult.Rejected(
                reason = "Rate limit exceeded: $requestsPerHour requests per hour",
                category = RejectionCategory.RATE_LIMITED
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 Input Validation 구현
 */
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1
) : InputValidationStage {

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text.trim()

        if (text.length < minLength) {
            return GuardResult.Rejected(
                reason = "Input too short",
                category = RejectionCategory.INVALID_INPUT
            )
        }

        if (text.length > maxLength) {
            return GuardResult.Rejected(
                reason = "Input too long (max: $maxLength)",
                category = RejectionCategory.INVALID_INPUT
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 Prompt Injection Detection 구현
 *
 * 규칙 기반 탐지 (기본). LLM 기반 탐지는 확장 가능.
 */
class DefaultInjectionDetectionStage : InjectionDetectionStage {

    private val suspiciousPatterns = listOf(
        // 역할 변경 시도
        Regex("(?i)(ignore|forget|disregard).*(previous|above|prior|all).*instructions?"),
        Regex("(?i)you are now"),
        Regex("(?i)act as"),
        Regex("(?i)pretend (to be|you're|you are)"),
        Regex("(?i)new (role|persona|character|identity)"),

        // 시스템 프롬프트 추출 시도
        Regex("(?i)(show|reveal|print|display|output).*(system|initial|original).*(prompt|instruction)"),
        Regex("(?i)what (are|were) your (instructions|rules)"),

        // 출력 조작
        Regex("(?i)(always|only|must).*(respond|reply|say|output)"),
        Regex("(?i)from now on"),

        // 인코딩/난독화 시도
        Regex("(?i)base64"),
        Regex("(?i)\\\\x[0-9a-f]{2}"),

        // 구분자 주입
        Regex("```system"),
        Regex("\\[SYSTEM\\]"),
        Regex("<\\|im_start\\|>"),
        Regex("<\\|endoftext\\|>")
    )

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text

        for (pattern in suspiciousPatterns) {
            if (pattern.containsMatchIn(text)) {
                logger.warn { "Injection pattern detected: ${pattern.pattern}" }
                return GuardResult.Rejected(
                    reason = "Suspicious pattern detected",
                    category = RejectionCategory.PROMPT_INJECTION
                )
            }
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 Classification 구현 (패스스루)
 *
 * 실제 구현에서는 LLM 기반 분류 또는 규칙 기반 분류 사용.
 */
class DefaultClassificationStage : ClassificationStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // 기본 구현은 모든 요청 허용
        // 실제 사용 시 LLM 분류 또는 규칙 기반 분류 구현
        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * 기본 Permission 구현 (패스스루)
 *
 * 실제 구현에서는 사용자 권한 확인.
 */
class DefaultPermissionStage : PermissionStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // 기본 구현은 모든 사용자 허용
        // 실제 사용 시 권한 시스템과 연동
        return GuardResult.Allowed.DEFAULT
    }
}

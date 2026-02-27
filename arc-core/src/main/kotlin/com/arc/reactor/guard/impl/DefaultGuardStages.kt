package com.arc.reactor.guard.impl

import com.arc.reactor.agent.config.TenantRateLimit
import com.arc.reactor.guard.ClassificationStage
import com.arc.reactor.guard.InjectionDetectionStage
import com.arc.reactor.guard.InputValidationStage
import com.arc.reactor.guard.PermissionStage
import com.arc.reactor.guard.RateLimitStage
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.support.formatBoundaryRuleViolation
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Default Rate Limit Implementation
 *
 * Caffeine cache-based per-minute and per-hour limits.
 * Supports tenant-aware rate limiting via tenantRateLimits map.
 */
class DefaultRateLimitStage(
    private val requestsPerMinute: Int = 10,
    private val requestsPerHour: Int = 100,
    private val tenantRateLimits: Map<String, TenantRateLimit> = emptyMap()
) : RateLimitStage {

    private val minuteCache: Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build()

    private val hourCache: Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build()

    override suspend fun check(command: GuardCommand): GuardResult {
        val tenantId = command.metadata["tenantId"]?.toString()
        val userId = command.userId
        val cacheKey = if (tenantId != null) "$tenantId:$userId" else userId

        // Resolve limits: tenant-specific override → global default fallback
        val tenantLimit = if (tenantId != null) tenantRateLimits[tenantId] else null
        val perMinute = tenantLimit?.perMinute ?: requestsPerMinute
        val perHour = tenantLimit?.perHour ?: requestsPerHour

        // Per-minute check
        val minuteCount = minuteCache.get(cacheKey) { AtomicInteger(0) }
        if (minuteCount.incrementAndGet() > perMinute) {
            return GuardResult.Rejected(
                reason = "Rate limit exceeded: $perMinute requests per minute",
                category = RejectionCategory.RATE_LIMITED
            )
        }

        // Per-hour check
        val hourCount = hourCache.get(cacheKey) { AtomicInteger(0) }
        if (hourCount.incrementAndGet() > perHour) {
            return GuardResult.Rejected(
                reason = "Rate limit exceeded: $perHour requests per hour",
                category = RejectionCategory.RATE_LIMITED
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * Default Input Validation Implementation
 */
class DefaultInputValidationStage(
    private val maxLength: Int = 10000,
    private val minLength: Int = 1,
    private val systemPromptMaxChars: Int = 0
) : InputValidationStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text.trim()

        if (text.length < minLength) {
            val reason = formatBoundaryRuleViolation("input.min_chars", text.length, minLength)
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        if (text.length > maxLength) {
            val reason = formatBoundaryRuleViolation("input.max_chars", text.length, maxLength)
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        if (systemPromptMaxChars > 0 && command.systemPrompt != null &&
            command.systemPrompt.length > systemPromptMaxChars
        ) {
            val reason = formatBoundaryRuleViolation(
                "system_prompt.max_chars",
                command.systemPrompt.length,
                systemPromptMaxChars
            )
            logger.warn { reason }
            return GuardResult.Rejected(
                reason = reason,
                category = RejectionCategory.INVALID_INPUT
            )
        }

        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * Default Prompt Injection Detection Implementation
 *
 * Rule-based detection (default). Extensible to LLM-based detection.
 */
class DefaultInjectionDetectionStage : InjectionDetectionStage {

    override suspend fun check(command: GuardCommand): GuardResult {
        val text = command.text

        for (pattern in SUSPICIOUS_PATTERNS) {
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

    companion object {
        private val SUSPICIOUS_PATTERNS = listOf(
            // Role change attempts
            Regex("(?i)(ignore|forget|disregard).*(previous|above|prior|all).*instructions?"),
            Regex("(?i)you are now"),
            Regex("(?i)\\bact as (a |an )?(unrestricted|unfiltered|different|new|evil|hacker|jailbroken)"),
            Regex("(?i)pretend (to be|you're|you are)"),
            Regex("(?i)new (role|persona|character|identity)"),

            // System prompt extraction attempts
            Regex("(?i)(show|reveal|print|display|output).*(system|initial|original).*(prompt|instruction)"),
            Regex("(?i)what (are|were) your (instructions|rules)"),

            // Output manipulation
            Regex("(?i)(always|only|must).*(respond|reply|say|output)"),
            Regex("(?i)from now on"),

            // Encoding/obfuscation attempts
            Regex("(?i)(decode|convert|translate).*base64.*(this|the|my|following)"),
            Regex("(?i)\\\\x[0-9a-f]{2}"),

            // Delimiter injection
            Regex("```system"),
            Regex("\\[SYSTEM\\]"),
            Regex("<\\|im_start\\|>"),
            Regex("<\\|endoftext\\|>"),

            // --- 2025 vectors ---

            // ChatML token smuggling
            Regex("<\\|im_end\\|>"),
            Regex("<\\|assistant\\|>"),
            Regex("<\\|user\\|>"),

            // Llama/Gemma format injection
            Regex("\\[INST\\]"),
            Regex("\\[/INST\\]"),
            Regex("<start_of_turn>"),
            Regex("<end_of_turn>"),

            // Instruction hierarchy / authority escalation
            Regex("(?i)(developer|system)\\s*(mode|override|prompt)"),

            // Safety override attempts
            Regex("(?i)override\\s+(safety|content|security)\\s+(filter|policy)"),

            // Context separator injection (20+ consecutive dashes or equals)
            Regex("-{20,}"),
            Regex("={20,}"),

            // Many-shot jailbreak (3+ numbered examples in a single message)
            Regex("(?is)example\\s*\\d+.*example\\s*\\d+.*example\\s*\\d+"),

            // Encoding bypass (rot13, deobfuscate)
            Regex("(?i)(rot13|deobfuscate).*this.*(text|message)")
        )
    }
}

/**
 * Default Classification Implementation (pass-through)
 *
 * In production, use LLM-based or rule-based classification.
 */
class DefaultClassificationStage : ClassificationStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // Default implementation allows all requests
        // Override with LLM-based or rule-based classification in production
        return GuardResult.Allowed.DEFAULT
    }
}

/**
 * Default Permission Implementation (pass-through)
 *
 * In production, verify user permissions.
 */
class DefaultPermissionStage : PermissionStage {
    override suspend fun check(command: GuardCommand): GuardResult {
        // Default implementation allows all users
        // Override with permission system integration in production
        return GuardResult.Allowed.DEFAULT
    }
}

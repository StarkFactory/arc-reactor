package com.arc.reactor.hook.example

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Audit Log Hook (example) — AfterAgentCompleteHook implementation
 *
 * Records an audit log every time agent execution completes (both success and failure).
 * In a real project, this can be extended to save to a DB, send to an external logging system, etc.
 *
 * ## Hook execution timing
 * ```
 * BeforeAgentStart → [Agent Loop] → AfterAgentComplete ← executes here
 * ```
 *
 * ## Use cases
 * - Tracking per-user usage history
 * - Analyzing tool call patterns
 * - Error monitoring and alerting
 * - Billing/metering data collection
 *
 * ## How to activate
 * Adding @Component to this class will auto-register it.
 *
 * ## Spring DI usage example
 * ```kotlin
 * @Component
 * class AuditLogHook(
 *     private val auditRepository: AuditRepository  // DB persistence
 * ) : AfterAgentCompleteHook {
 *     // ...
 * }
 * ```
 */
// @Component  ← Uncomment to auto-register
class AuditLogHook : AfterAgentCompleteHook {

    // 100-199: Standard Hook range
    override val order = 100

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        logger.info {
            "AUDIT | runId=${context.runId} " +
                "userId=${context.userId} " +
                "success=${response.success} " +
                "tools=${response.toolsUsed} " +
                "duration=${context.durationMs()}ms " +
                "prompt=\"${context.userPrompt.take(100)}\""
        }

        // In a real project, add DB persistence, external system forwarding, etc. here:
        // auditRepository.save(AuditLog(
        //     runId = context.runId,
        //     userId = context.userId,
        //     success = response.success,
        //     toolsUsed = response.toolsUsed,
        //     durationMs = context.durationMs(),
        //     prompt = context.userPrompt
        // ))
    }
}

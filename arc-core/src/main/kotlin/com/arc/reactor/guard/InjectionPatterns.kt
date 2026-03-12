package com.arc.reactor.guard

/**
 * Shared prompt injection detection patterns used by both
 * input guard ([DefaultInjectionDetectionStage]) and
 * tool output sanitizer ([ToolOutputSanitizer]).
 */
object InjectionPatterns {

    data class InjectionPattern(val name: String, val regex: Regex)

    val SHARED: List<InjectionPattern> = listOf(
        // Role override
        InjectionPattern(
            "role_override",
            Regex("(?i)(ignore|forget|disregard).*(previous|above|prior|all).*instructions?")
        ),
        InjectionPattern("role_override", Regex("(?i)you are now")),
        InjectionPattern(
            "role_override",
            Regex("(?i)\\bact as (a |an )?(unrestricted|unfiltered|different|new|evil|hacker|jailbroken)")
        ),

        // System delimiter injection
        InjectionPattern("system_delimiter", Regex("\\[SYSTEM\\]")),
        InjectionPattern("system_delimiter", Regex("<\\|im_start\\|>")),
        InjectionPattern("system_delimiter", Regex("<\\|im_end\\|>")),
        InjectionPattern("system_delimiter", Regex("<\\|assistant\\|>")),

        // Prompt override
        InjectionPattern("prompt_override", Regex("(?i)from now on"))
    )
}

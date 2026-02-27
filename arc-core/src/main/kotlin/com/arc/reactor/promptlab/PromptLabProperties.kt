package com.arc.reactor.promptlab

/**
 * Prompt Lab configuration properties.
 *
 * Bound to `arc.reactor.prompt-lab.*` via [AgentProperties].
 */
data class PromptLabProperties(
    /** Enable Prompt Lab feature */
    val enabled: Boolean = false,

    /** Maximum concurrent experiments */
    val maxConcurrentExperiments: Int = 3,

    /** Maximum test queries per experiment */
    val maxQueriesPerExperiment: Int = 100,

    /** Maximum prompt versions per experiment */
    val maxVersionsPerExperiment: Int = 10,

    /** Maximum repetitions per version-query pair */
    val maxRepetitions: Int = 5,

    /** Default LLM judge model (null = same as experiment model) */
    val defaultJudgeModel: String? = null,

    /** Default token budget for LLM judge evaluations */
    val defaultJudgeBudgetTokens: Int = 100_000,

    /** Experiment execution timeout (milliseconds) */
    val experimentTimeoutMs: Long = 600_000,

    /** Number of candidate prompts to auto-generate */
    val candidateCount: Int = 3,

    /** Minimum negative feedback count to trigger auto pipeline */
    val minNegativeFeedback: Int = 5,

    /** Cron scheduling configuration */
    val schedule: ScheduleProperties = ScheduleProperties()
)

/** Cron schedule configuration for automatic prompt optimization */
data class ScheduleProperties(
    /** Enable scheduled auto-optimization */
    val enabled: Boolean = false,

    /** Cron expression (default: daily at 2 AM) */
    val cron: String = "0 0 2 * * *",

    /** Target template IDs (empty = all templates) */
    val templateIds: List<String> = emptyList()
)

package com.arc.reactor.agent.impl

/**
 * 강제 도구 호출 계획에 사용할 인자 맵을 생성하는 빌더.
 *
 * [WorkContextForcedToolPlanner]의 plan 메서드에서 반복되는 인자 조립 로직을
 * 중앙화하여 일관된 기본값과 설정을 보장한다.
 *
 * @see WorkContextForcedToolPlanner
 */
internal object WorkContextArgBuilder {

    // ── 정규식 상수 ──

    private val specNameSanitizeRegex = Regex("[^a-z0-9._-]")

    // ── 인자 맵 빌더 ──

    /** 릴리즈 리스크 다이제스트 도구의 공통 인자 맵을 생성한다. */
    fun buildReleaseRiskArgs(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): Map<String, Any?> = buildMap {
        put("releaseName", inferReleaseName(prompt, projectKey, repository))
        put("stalePrDays", 3)
        put("reviewSlaHours", 24)
        put("jiraMaxResults", 20)
        projectKey?.let { put("jiraProject", it) }
        repository?.let {
            put("bitbucketWorkspace", it.first)
            put("bitbucketRepo", it.second)
        }
    }

    /** 릴리즈 준비 팩 도구의 공통 인자 맵을 생성한다. */
    fun buildReadinessPackArgs(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): Map<String, Any?> = buildMap {
        put("releaseName", inferReleaseName(prompt, projectKey, repository))
        put("stalePrDays", 3)
        put("reviewSlaHours", 24)
        put("daysLookback", 1)
        put("jiraMaxResults", 20)
        put("dryRunActionItems", true)
        put("actionItemsMaxCreate", 10)
        put("blockerWeight", 4)
        put("overdueWeight", 2)
        put("reviewSlaBreachWeight", 3)
        put("stalePrWeight", 1)
        put("missingReleaseDocWeight", 2)
        put("highRiskThreshold", 18)
        put("mediumRiskThreshold", 10)
        put("autoExecuteActionItems", false)
        put("autoExecuteMaxRiskLevel", "MEDIUM")
        put("autoExecuteRequireNoBlockers", true)
        projectKey?.let { put("jiraProject", it) }
        repository?.let {
            put("bitbucketWorkspace", it.first)
            put("bitbucketRepo", it.second)
        }
    }

    /** 아침 브리핑 도구의 공통 인자 맵을 생성한다. */
    fun buildMorningBriefingArgs(
        projectKey: String,
        confluenceKeyword: String = "status"
    ): Map<String, Any?> = mapOf(
        "jiraProject" to projectKey,
        "confluenceKeyword" to confluenceKeyword,
        "reviewSlaHours" to 24,
        "dueSoonDays" to 7,
        "jiraMaxResults" to 20
    )

    /** 스탠드업 준비 도구의 공통 인자 맵을 생성한다. */
    fun buildStandupArgs(projectKey: String?): Map<String, Any?> = buildMap {
        projectKey?.let { put("jiraProject", it) }
        put("daysLookback", 7)
        put("jiraMaxResults", 20)
    }

    /** 학습 다이제스트 도구의 공통 인자 맵을 생성한다. */
    fun buildLearningDigestArgs(): Map<String, Any?> = mapOf(
        "lookbackDays" to 14,
        "topTopics" to 4,
        "docsPerTopic" to 2
    )

    // ── 추론 헬퍼 ──

    /** 릴리즈 준비 팩에 사용할 릴리즈 이름을 추론한다. */
    fun inferReleaseName(
        prompt: String,
        projectKey: String?,
        repository: Pair<String, String>?
    ): String = WorkContextEntityExtractor.extractQuotedKeyword(prompt)
        ?: projectKey
        ?: repository?.second
        ?: "release-readiness"

    /** URL에서 스펙 이름을 추론한다 (파일명 기반, 소문자/특수문자 정리). */
    fun inferSpecName(url: String): String {
        val sanitized = url.substringAfterLast('/')
            .substringBefore('?').substringBefore('#')
        val base = sanitized.substringBeforeLast('.')
            .ifBlank { "openapi-spec" }
        return base.lowercase()
            .replace(specNameSanitizeRegex, "-")
            .trim('-')
            .ifBlank { "openapi-spec" }
    }
}

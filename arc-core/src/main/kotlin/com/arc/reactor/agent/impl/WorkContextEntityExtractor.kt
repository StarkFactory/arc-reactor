package com.arc.reactor.agent.impl

// WorkContextPatterns는 같은 패키지 내 internal object — import 불필요

/**
 * 사용자 프롬프트에서 엔티티(이슈 키, 프로젝트 키, 레포지토리, 서비스명 등)를 추출한다.
 *
 * [WorkContextForcedToolPlanner]가 강제 도구 호출 계획을 수립할 때
 * 프롬프트 분석의 첫 단계로 이 객체를 사용한다.
 *
 * @see WorkContextForcedToolPlanner
 */
internal object WorkContextEntityExtractor {

    // ── 정규식 상수 ──

    /** 이모지 및 기호 유니코드를 제거한다. JQL 파싱 오류 방지. */
    private val emojiRegex = Regex(
        "[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}" +
            "\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}\\p{So}\\p{Cn}]"
    )
    private val multiSpaceRegex = Regex("\\s{2,}")

    private val issueKeyRegex = WorkContextPatterns.ISSUE_KEY_REGEX
    private val projectRegexes = listOf(
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*프로젝트"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*팀"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*릴리즈"),
        Regex("\\b([A-Z][A-Z0-9_]{1,15})\\s*이슈"),
        Regex("\\bproject\\s+([A-Z][A-Z0-9_]{1,15})\\b", RegexOption.IGNORE_CASE)
    )
    private val looseProjectRegex = Regex("\\b([A-Z][A-Z0-9_]{1,15})\\b")
    private val looseProjectStopWords = setOf(
        "API", "JIRA", "CONFLUENCE", "BITBUCKET", "SWAGGER", "OPENAPI",
        "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "HTTP", "HTTPS",
        "MCP", "PDF", "URL", "JSON", "XML", "SQL", "UI", "UX"
    )
    private val repositoryRegex =
        Regex("\\b([A-Za-z0-9._-]{2,64})/([A-Za-z0-9._-]{2,64})\\b")
    private val repositorySlugRegex =
        Regex(
            "([A-Za-z0-9._-]{2,64})\\s*(?:저장소|레포지토리|레포|리포지토리|리포)",
            RegexOption.IGNORE_CASE
        )
    private val urlRegex = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE)
    private val quotedKeywordRegexes = listOf(
        Regex("'([^']{2,80})'"),
        Regex("\"([^\"]{2,80})\"")
    )
    private val keywordRegexes = listOf(
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*키워드"),
        Regex(
            "\\bkeyword\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b",
            RegexOption.IGNORE_CASE
        )
    )
    private val swaggerSpecNameRegex =
        Regex("\\b([A-Za-z][A-Za-z0-9._-]{2,63})\\b")
    private val swaggerSpecStopWords = setOf(
        "swagger", "openapi", "spec", "summary", "summarize", "schema",
        "endpoint", "security", "methods", "method", "detail", "details",
        "loaded", "local", "current", "show", "tell", "the", "and", "for",
        "with", "pet", "store", "user"
    )
    private val apiRegexes = listOf(
        Regex(
            "\\b([A-Za-z][A-Za-z0-9._-]{1,63})\\s*api\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\bapi\\s+([A-Za-z][A-Za-z0-9._-]{1,63})\\b",
            RegexOption.IGNORE_CASE
        )
    )
    private val serviceRegexes = listOf(
        Regex(
            "\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*서비스",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "\\b([A-Za-z0-9][A-Za-z0-9_-]{1,63})\\s*service\\b",
            RegexOption.IGNORE_CASE
        )
    )
    private val personalIdentityRegexes = listOf(
        Regex("(^|\\s)내\\s"),
        Regex(
            "(^|\\s)내(가|를|가요|가야|기준으로|기준|이름으로|이름|휴가|오픈|리뷰|jira|pr)" +
                "(\\b|\\s|$)"
        )
    )

    /** 개인화 프롬프트 판별에 사용하는 키워드 셋. */
    private val personalIdentityPhrases = setOf(
        "내가", "내 기준", "내 기준으로", "내 이름", "내 휴가", "내 오픈",
        "내 open", "내 review", "내 리뷰", "내 jira", "내 pr",
        "내 pull request", "내가 맡은", "내가 오늘", "내가 최근",
        "내 이름으로", "내가 담당", "내 기준으로 오늘", "내 owner"
    )

    // ── 프롬프트 분석에서 추출된 엔티티를 담는 컨테이너 ──

    /** [parsePrompt] 메서드에서 반복 추출을 방지하기 위한 파싱 결과 컨테이너. */
    data class ParsedPrompt(
        val normalized: String,
        val issueKey: String?,
        val serviceName: String?,
        val projectKey: String?,
        val inferredProjectKey: String?,
        val repository: Pair<String, String>?,
        val repositorySlug: String?,
        val specUrl: String?,
        val swaggerSpecName: String?,
        val ownershipKeyword: String?,
        val isPersonal: Boolean
    )

    /** 이모지 및 특수 유니코드 문자를 제거하여 정규식 매칭과 JQL 생성을 안전하게 한다. */
    fun stripEmoji(text: String): String =
        emojiRegex.replace(text, "").replace(multiSpaceRegex, " ").trim()

    /** 원본 프롬프트에서 모든 엔티티를 일괄 추출한다. */
    fun parsePrompt(prompt: String): ParsedPrompt {
        val normalized = prompt.lowercase()
        val projectKey = extractProjectKey(prompt)
        val repository = extractRepository(prompt)
        return ParsedPrompt(
            normalized = normalized,
            issueKey = extractIssueKey(prompt),
            serviceName = extractServiceName(prompt),
            projectKey = projectKey,
            inferredProjectKey = projectKey ?: extractLooseProjectKey(prompt),
            repository = repository,
            repositorySlug = repository?.second
                ?: extractRepositorySlug(prompt),
            specUrl = extractUrl(prompt),
            swaggerSpecName = extractSwaggerSpecName(prompt),
            ownershipKeyword = extractOwnershipKeyword(prompt),
            isPersonal = isPersonalPrompt(normalized)
        )
    }

    // ── 개별 엔티티 추출 메서드 ──

    /** Jira 이슈 키(예: PROJ-123)를 추출한다. */
    fun extractIssueKey(prompt: String): String? =
        issueKeyRegex.find(prompt.uppercase())?.value

    /** 프롬프트가 개인화된 요청("내 이슈", "내가 담당" 등)인지 판별한다. */
    fun isPersonalPrompt(normalizedPrompt: String): Boolean =
        normalizedPrompt.matchesAny(personalIdentityPhrases) ||
            personalIdentityRegexes.any {
                it.containsMatchIn(normalizedPrompt)
            }

    /** 프롬프트에서 서비스명을 추출한다 (예: "payment-service 서비스"). */
    fun extractServiceName(prompt: String): String? =
        extractFirstGroupMatch(prompt, serviceRegexes)

    /** 프롬프트에서 Jira 프로젝트 키를 추출한다 (예: "PROJ 프로젝트"). */
    fun extractProjectKey(prompt: String): String? =
        extractFirstGroupMatch(prompt, projectRegexes)?.uppercase()

    /** 느슨한 규칙으로 프로젝트 키를 추출한다. 일반 단어와 stop word를 제외한다. */
    fun extractLooseProjectKey(prompt: String): String? {
        return looseProjectRegex.find(prompt)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.uppercase()
            ?.let { candidate ->
                if (candidate.isBlank() || candidate.length < 2) return null
                if (candidate.length > 12) return null
                if (candidate in looseProjectStopWords) return null
                if (candidate.all { it.isDigit() }) return null
                return candidate
            }
    }

    /** "workspace/repository" 형식의 레포지토리 참조를 추출한다. */
    fun extractRepository(prompt: String): Pair<String, String>? {
        val match = repositoryRegex.find(prompt) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** "xxx 저장소" 형식에서 레포지토리 slug를 추출한다. */
    fun extractRepositorySlug(prompt: String): String? =
        repositorySlugRegex.find(prompt)?.groupValues?.getOrNull(1)
            ?.trim()?.takeIf { it.isNotBlank() }

    /** 프롬프트에서 HTTP/HTTPS URL을 추출한다. */
    fun extractUrl(prompt: String): String? =
        urlRegex.find(prompt)?.value?.trim()

    /** 따옴표('...' 또는 "...")로 감싼 키워드를 추출한다. */
    fun extractQuotedKeyword(prompt: String): String? =
        extractFirstGroupMatch(prompt, quotedKeywordRegexes)

    /** 검색 키워드를 추출한다. 따옴표 키워드를 우선하고, 없으면 "키워드" 패턴을 사용한다. */
    fun extractSearchKeyword(prompt: String): String? =
        extractQuotedKeyword(prompt)
            ?: extractFirstGroupMatch(prompt, keywordRegexes)

    /** 소유자 조회에 사용할 키워드를 추출한다. */
    fun extractOwnershipKeyword(prompt: String): String? {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null
        extractRepository(prompt)?.second
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extractRepositorySlug(prompt)?.let { return it }
        extractServiceName(prompt)?.let { return it }
        extractQuotedKeyword(prompt)?.let { return it }
        extractFirstGroupMatch(prompt, apiRegexes)?.let { return it }
        return inferOwnershipFallback(prompt.lowercase())
    }

    /** Swagger/OpenAPI 스펙 이름을 추출한다. 따옴표 키워드 -> 패턴 매칭 순으로 시도한다. */
    fun extractSwaggerSpecName(prompt: String): String? {
        extractQuotedKeyword(prompt)
            ?.trim()
            ?.takeIf(::looksLikeSwaggerSpecName)
            ?.let { return it }

        return swaggerSpecNameRegex.findAll(prompt)
            .mapNotNull { match -> match.groupValues.getOrNull(1) }
            .map { it.trim() }
            .firstOrNull(::looksLikeSwaggerSpecName)
    }

    // ── 내부 헬퍼 ──

    /** 정규식 목록에서 첫 번째 그룹 매칭 결과를 반환하는 공통 헬퍼. */
    private fun extractFirstGroupMatch(
        input: String,
        regexes: List<Regex>
    ): String? = regexes.asSequence()
        .mapNotNull { regex ->
            regex.find(input)?.groupValues?.getOrNull(1)
        }
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }

    /** 소유권 키워드의 폴백 추론 (일반 키워드 매칭). */
    private fun inferOwnershipFallback(normalized: String): String? = when {
        normalized.contains("release note") -> "release note"
        normalized.contains("incident") -> "incident"
        normalized.contains("runbook") -> "runbook"
        normalized.contains("vacation") ||
            normalized.contains("휴가") -> "휴가"
        normalized.contains("billing") -> "billing"
        normalized.contains("auth") -> "auth"
        normalized.contains("frontend") -> "frontend"
        normalized.contains("backend") -> "backend"
        normalized.contains("owner") -> "owner"
        else -> null
    }

    /** 후보 문자열이 Swagger 스펙 이름처럼 보이는지 판별한다. */
    private fun looksLikeSwaggerSpecName(candidate: String): Boolean {
        val normalized = candidate.lowercase()
        if (normalized.isBlank()) return false
        if (normalized in swaggerSpecStopWords) return false
        if (normalized.startsWith("http")) return false
        if (candidate.startsWith("/")) return false
        if (candidate.contains('/')) return false
        return candidate.contains('-') || candidate.contains('_')
    }

    /** 정규화된 문자열이 힌트 셋 중 하나라도 포함하면 true를 반환한다. */
    private fun String.matchesAny(hints: Set<String>): Boolean =
        hints.any { this.contains(it) }
}

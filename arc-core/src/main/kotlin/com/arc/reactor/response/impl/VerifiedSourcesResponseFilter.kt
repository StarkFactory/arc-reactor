package com.arc.reactor.response.impl

import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.tool.WorkspaceMutationIntentDetector

/**
 * 검증된 출처(verified sources)를 응답 하단에 자동 첨부하는 [ResponseFilter].
 *
 * 실행 순서: 90 (내장 필터 범위 내).
 *
 * ## 동작 흐름
 * 1. TEXT 형식이 아니면 통과
 * 2. 생성된 콘텐츠에서 도구 코드 블록과 기존 출처 블록을 제거 (정규화)
 * 3. 검증된 출처가 없고 미검증 답변 차단 조건에 해당하면 차단 메시지를 반환
 * 4. 내부 읽기 전용 도구만 사용했거나, 읽기 전용 변경 거부/신원 미확인 거부인 경우 출처 없이 반환
 * 5. 검증된 출처가 필요 없는 캐주얼 프롬프트이면 출처 없이 반환
 * 6. 그 외에는 콘텐츠 끝에 출처 블록을 추가
 *
 * 한글 프롬프트이면 한글 메시지를, 영어 프롬프트이면 영어 메시지를 생성한다.
 */
class VerifiedSourcesResponseFilter : ResponseFilter {
    override val order: Int = 90

    override suspend fun filter(content: String, context: ResponseFilterContext): String {
        // TEXT 형식이 아니면 변환 없이 통과
        if (context.command.responseFormat != ResponseFormat.TEXT) return content

        val normalizedContent = sanitizeGeneratedContent(content)
        val sources = context.verifiedSources.distinctBy { it.url }.take(MAX_SOURCES)
        val internalReadWithoutLinks = usesOnlyInternalReadTools(context)

        // 검증 불가 시 차단 메시지 반환
        if (shouldBlockUnverifiedAnswer(context, sources, normalizedContent)) {
            return buildUnverifiedResponse(context.command.userPrompt, sources)
        }

        val finalContent = normalizedContent.ifBlank {
            buildFallbackVerifiedResponse(context.command.userPrompt, sources)
        }

        // 내부 읽기 도구만 사용했거나 읽기 전용 변경 거부이면 출처 블록 생략
        if (sources.isEmpty() && (internalReadWithoutLinks || allowsReadOnlyMutationRefusal(context, finalContent))) {
            return finalContent.trimEnd()
        }
        // 신원 미확인 거부이면 출처 블록 생략
        if (sources.isEmpty() && allowsIdentityResolutionRefusal(finalContent)) {
            return finalContent.trimEnd()
        }
        // 검증된 출처가 필요 없는 프롬프트이면 출처 블록 생략
        if (sources.isEmpty() && !requiresVerifiedSources(context)) {
            return finalContent.trimEnd()
        }

        return finalContent.trimEnd() + "\n\n" + buildSourcesBlock(context.command.userPrompt, sources)
    }

    /**
     * 미검증 답변을 차단해야 하는지 판단한다.
     *
     * 출처가 있거나, 검증이 불필요하거나, 내부 도구만 사용했거나,
     * 읽기 전용/신원 거부이거나, 이미 스스로 검증 불가를 선언한 경우 차단하지 않는다.
     * 도구가 호출되지 않은 일반 지식 질문도 차단하지 않는다.
     */
    private fun shouldBlockUnverifiedAnswer(
        context: ResponseFilterContext,
        sources: List<VerifiedSource>,
        content: String
    ): Boolean {
        if (sources.isNotEmpty()) return false
        if (!requiresVerifiedSources(context)) return false
        if (usesOnlyInternalReadTools(context)) return false
        // 도구가 호출되었고 콘텐츠가 있으면 차단하지 않는다 (도구가 출처 URL을 반환하지 않는 경우)
        if (context.toolsUsed.isNotEmpty() && content.isNotBlank()) return false
        // 도구가 호출되지 않았고 워크스페이스 전용 질문이 아니면 일반 지식 답변으로 허용한다
        if (context.toolsUsed.isEmpty() && !isStrictWorkspaceQuery(context.command.userPrompt)) return false
        if (allowsReadOnlyMutationRefusal(context, content)) return false
        if (allowsIdentityResolutionRefusal(content)) return false
        return !alreadyDeclinesVerification(content)
    }

    /** 읽기 전용 변경 거부 응답인지 판단한다. */
    private fun allowsReadOnlyMutationRefusal(context: ResponseFilterContext, content: String): Boolean {
        if (!WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(context.command.userPrompt)) return false
        return READ_ONLY_MUTATION_PATTERNS.any { pattern -> content.contains(pattern, ignoreCase = true) }
    }

    /** 신원 미확인 거부 응답인지 판단한다. */
    private fun allowsIdentityResolutionRefusal(content: String): Boolean {
        return IDENTITY_RESOLUTION_PATTERNS.any { pattern -> content.contains(pattern, ignoreCase = true) }
    }

    /**
     * 검증된 출처가 필요한 프롬프트인지 판단한다.
     *
     * 캐주얼 인사/감사 프롬프트이면 불필요, 워크스페이스 도구를 사용했으면 필요,
     * 정보 요청 패턴이면 필요하다.
     */
    private fun requiresVerifiedSources(context: ResponseFilterContext): Boolean {
        if (isCasualPrompt(context.command.userPrompt)) return false
        if (context.toolsUsed.any(::isWorkspaceTool)) return true
        return looksLikeInformationRequest(context.command.userPrompt)
    }

    /** 링크 없는 내부 읽기 전용 도구만 사용했는지 확인한다. */
    private fun usesOnlyInternalReadTools(context: ResponseFilterContext): Boolean {
        return context.toolsUsed.isNotEmpty() && context.toolsUsed.all { it in INTERNAL_READ_TOOLS_WITHOUT_LINKS }
    }

    /** 워크스페이스 도구인지 접두사로 판단한다. */
    private fun isWorkspaceTool(toolName: String): Boolean {
        return WORKSPACE_TOOL_PREFIXES.any { prefix -> toolName.startsWith(prefix) }
    }

    /**
     * 프롬프트가 워크스페이스 전용 질문인지 엄격하게 판단한다.
     *
     * [requiresVerifiedSources]의 [VERIFICATION_KEYWORDS]는 "문서", "서비스" 등
     * 일반 지식 질문에서도 등장하는 넓은 키워드를 포함한다.
     * 이 메서드는 도구가 호출되지 않은 상황에서 차단 여부를 결정할 때만 사용되며,
     * Jira/Confluence/Bitbucket/Swagger 등 워크스페이스 도구와 직접 연관된
     * 키워드가 포함된 경우에만 참을 반환한다.
     */
    private fun isStrictWorkspaceQuery(userPrompt: String): Boolean {
        return STRICT_WORKSPACE_KEYWORDS.any { keyword ->
            userPrompt.contains(keyword, ignoreCase = true)
        }
    }

    /** 응답이 이미 검증 불가를 선언하는 문구를 포함하는지 확인한다. */
    private fun alreadyDeclinesVerification(content: String): Boolean {
        return UNVERIFIED_PATTERNS.any { pattern -> content.contains(pattern, ignoreCase = true) }
    }

    /** 미검증 차단 메시지를 생성한다. 한글/영어 자동 판별. */
    private fun buildUnverifiedResponse(userPrompt: String, sources: List<VerifiedSource>): String {
        val message = if (containsHangul(userPrompt)) {
            "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. 승인된 Jira, Confluence, Bitbucket, Swagger/OpenAPI 자료를 다시 조회해 주세요."
        } else {
            "I couldn't verify this answer from approved sources. Please re-run the query against approved Jira, Confluence, Bitbucket, or Swagger/OpenAPI data."
        }
        return "$message\n\n${buildSourcesBlock(userPrompt, sources)}"
    }

    /** 출처 블록을 마크다운 형식으로 생성한다. */
    private fun buildSourcesBlock(userPrompt: String, sources: List<VerifiedSource>): String {
        val heading = if (containsHangul(userPrompt)) "출처" else "Sources"
        if (sources.isEmpty()) {
            val emptyLine = if (containsHangul(userPrompt)) {
                "- 검증된 출처를 찾지 못했습니다."
            } else {
                "- No verified sources available."
            }
            return "$heading\n$emptyLine"
        }
        val lines = sources.map { source ->
            "- [${escapeTitle(source.title)}](${source.url})"
        }
        return "$heading\n${lines.joinToString("\n")}"
    }

    /** 콘텐츠가 비어 있을 때 대체 메시지를 생성한다. */
    private fun buildFallbackVerifiedResponse(userPrompt: String, sources: List<VerifiedSource>): String {
        if (sources.isEmpty()) return ""
        return if (containsHangul(userPrompt)) {
            "승인된 도구 결과를 확인했지만 요약 문장을 생성하지 못했습니다. 아래 출처를 직접 확인해 주세요."
        } else {
            "I retrieved approved tool results, but couldn't generate a clean summary. Please inspect the sources below."
        }
    }

    /** 생성된 콘텐츠에서 도구 코드 블록과 기존 출처 블록을 제거한다. */
    private fun sanitizeGeneratedContent(content: String): String {
        val withoutToolPlans = TOOL_CODE_BLOCK_REGEX.replace(content, "\n")
        val withoutSourcesBlock = stripSourcesBlock(withoutToolPlans)
        return BLANK_LINE_SPACING_REGEX.replace(withoutSourcesBlock, "\n\n").trim()
    }

    /** 콘텐츠 끝에 있는 기존 출처 블록을 제거한다. */
    private fun stripSourcesBlock(content: String): String {
        val trimmed = content.trimEnd()
        val matches = SOURCE_HEADING_REGEX.findAll(trimmed).toList()
        if (matches.isEmpty()) return trimmed
        val match = matches.first()
        return trimmed.substring(0, match.range.first).trimEnd()
    }

    /** 마크다운 링크 제목에서 대괄호를 이스케이프한다. */
    private fun escapeTitle(title: String): String {
        return title.replace("[", "\\[").replace("]", "\\]")
    }

    /** 문자열에 한글(가~힣)이 포함되어 있는지 확인한다. */
    private fun containsHangul(text: String): Boolean {
        return text.any { ch -> ch in '\uAC00'..'\uD7A3' }
    }

    /**
     * 정보 요청 프롬프트인지 판단한다.
     *
     * 워크스페이스 키워드가 포함되면 즉시 참, 그렇지 않으면
     * 질문/정보 패턴과 워크스페이스 힌트가 동시에 존재해야 참이다.
     */
    private fun looksLikeInformationRequest(userPrompt: String): Boolean {
        val hasWorkspaceKeyword = VERIFICATION_KEYWORDS.any { keyword ->
            userPrompt.contains(keyword, ignoreCase = true)
        }
        if (hasWorkspaceKeyword) return true
        if (!hasQuestionOrInfoPattern(userPrompt)) return false
        return WORKSPACE_CONTEXT_HINTS.any { hint ->
            userPrompt.contains(hint, ignoreCase = true)
        }
    }

    /** 질문 형태이거나 정보 요청 패턴이 포함되어 있는지 확인한다. */
    private fun hasQuestionOrInfoPattern(userPrompt: String): Boolean {
        if (userPrompt.contains('?')) return true
        return INFORMATION_REQUEST_PATTERNS.any { pattern ->
            userPrompt.contains(pattern, ignoreCase = true)
        }
    }

    /** 캐주얼 인사/감사 프롬프트인지 판단한다. */
    private fun isCasualPrompt(userPrompt: String): Boolean {
        val normalized = userPrompt.trim().lowercase()
        if (normalized.isBlank()) return true
        return CASUAL_PROMPTS.any { casual -> normalized == casual } ||
            CASUAL_PREFIXES.any { prefix -> normalized.startsWith(prefix) }
    }

    companion object {
        /** 워크스페이스 도구 접두사 목록. */
        private val WORKSPACE_TOOL_PREFIXES =
            listOf("jira_", "confluence_", "bitbucket_", "work_", "mcp_", "spec_")

        /**
         * 도구가 호출되지 않은 상황에서 차단 여부를 결정하기 위한 엄격한 워크스페이스 키워드.
         *
         * [VERIFICATION_KEYWORDS]보다 좁은 범위로, 워크스페이스 도구(Jira, Confluence 등)와
         * 직접 연관된 키워드만 포함한다. "문서", "서비스", "규칙" 등 일반 지식 질문에서도
         * 빈번히 등장하는 넓은 키워드는 제외한다.
         */
        private val STRICT_WORKSPACE_KEYWORDS = setOf(
            "jira", "confluence", "bitbucket", "swagger", "openapi",
            "지라", "컨플루언스", "비트버킷", "스웨거", "오픈api",
            "이슈", "티켓", "스프린트", "보드", "위키", "스페이스",
            "pull request", "pr ", "브랜치", "리포",
            "배포 현황", "배포 일정", "배포 정책", "배포 규칙", "릴리즈", "릴리스", "인시던트", "장애",
            "온보딩", "런북", "runbook", "팀 정책", "사내 정책", "우리팀 정책", "우리 팀 정책",
            "마감", "마감일", "기한", "due date", "deadline", "마감 임박",
            "완료", "완료된", "완료 이슈", "done", "resolved", "closed"
        )

        /** 링크 없는 내부 읽기 전용 도구 목록. */
        private val INTERNAL_READ_TOOLS_WITHOUT_LINKS = setOf(
            "work_list_briefing_profiles",
            "list_scheduled_jobs",
            "get_scheduled_job",
            "get_scheduler_capabilities"
        )

        /** 검증이 필요한 워크스페이스 관련 키워드. */
        private val VERIFICATION_KEYWORDS = setOf(
            "jira", "confluence", "bitbucket", "slack", "policy", "policies", "runbook", "incident",
            "release", "owner", "status", "guideline", "documentation", "swagger", "openapi",
            "endpoint", "schema", "api", "지라", "컨플루언스", "비트버킷", "스웨거", "오픈api",
            "엔드포인트", "스키마", "정책", "규정", "문서", "사내", "서비스", "규칙"
        )

        /** 정보 요청을 나타내는 키워드/패턴. */
        private val INFORMATION_REQUEST_PATTERNS = setOf(
            "who", "what", "when", "where", "why", "how", "tell me", "explain", "summarize", "summary",
            "list", "show", "find", "search", "lookup", "알려", "설명", "요약", "정리", "보여", "찾아",
            "조회", "무엇", "왜", "어떻게", "누구", "언제", "어디", "몇"
        )

        /** 워크스페이스 컨텍스트 힌트 키워드. */
        private val WORKSPACE_CONTEXT_HINTS = setOf(
            "jira", "confluence", "bitbucket", "slack",
            "이슈", "티켓", "프로젝트", "스프린트", "보드",
            "페이지", "스페이스", "위키",
            "pr", "pull request", "브랜치", "리포",
            "배포", "릴리즈", "릴리스", "장애", "인시던트",
            "팀", "담당자", "담당", "할당"
        )

        /** 캐주얼 프롬프트 정확 일치 목록. */
        private val CASUAL_PROMPTS = setOf(
            "hi", "hello", "hey", "thanks", "thank you", "ok", "okay",
            "안녕", "안녕하세요", "안녕하세요!", "안녕!", "반갑습니다",
            "고마워", "고마워요", "고맙습니다", "감사해", "감사합니다",
            "네", "아니요", "아니오", "응", "좋아", "알겠어", "알겠습니다",
            "오케이", "ㅇㅋ", "ㅎㅇ", "ㅎㅎ", "ㄳ", "ㄱㅅ"
        )

        /** 캐주얼 프롬프트 접두사 목록. */
        private val CASUAL_PREFIXES = setOf(
            "thanks ", "thank you ", "hi ", "hello ",
            "고마워 ", "감사해 ", "감사합니다 ",
            "안녕 ", "안녕하세요 ", "네 ", "좋아 "
        )

        /** 이미 검증 불가를 선언한 응답 패턴. */
        private val UNVERIFIED_PATTERNS = listOf(
            "cannot verify",
            "couldn't verify",
            "insufficient",
            "검증 가능한 출처를 찾지 못",
            "확인 가능한 출처를 찾지 못",
            "근거를 찾지 못"
        )

        /** 신원 미확인 거부 패턴. */
        private val IDENTITY_RESOLUTION_PATTERNS = listOf(
            "requester identity could not be resolved",
            "couldn't resolve the requesting user",
            "요청자 계정을 jira 사용자로 확인할 수 없어",
            "요청자 계정을 jira 사용자로 확인할 수 없습니다",
            "요청자 계정을 확인할 수 없습니다",
            "개인화 조회에 필요한 사용자 매핑을 확인할 수 없습니다"
        )

        /** 읽기 전용 변경 거부 패턴. */
        private val READ_ONLY_MUTATION_PATTERNS = listOf(
            "read-only",
            "readonly",
            "읽기 전용",
            "지원하지 않습니다",
            "수행할 수 없습니다",
            "업데이트할 수 없습니다",
            "재할당은 불가능",
            "변경 작업을 수행할 수 없습니다"
        )

        /** 도구 코드 블록 정규식. */
        private val TOOL_CODE_BLOCK_REGEX = Regex("(?s)```tool_code\\s*.*?```\\s*")

        /** 출처 헤딩 정규식. */
        private val SOURCE_HEADING_REGEX =
            Regex("(?m)^\\s*(?:\\*\\*)?(Sources|출처)(?:\\*\\*)?(?:\\s*:\\s*.*)?$")

        /** 연속 빈 줄 정규화 정규식. */
        private val BLANK_LINE_SPACING_REGEX = Regex("\\n{3,}")

        /** 출처 블록에 포함할 최대 출처 수. */
        private const val MAX_SOURCES = 8
    }
}

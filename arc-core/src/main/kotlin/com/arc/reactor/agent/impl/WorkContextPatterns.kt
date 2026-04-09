package com.arc.reactor.agent.impl

/** [WorkContextEntityExtractor.ParsedPrompt]의 타입 별칭. 플래너 전체에서 공유한다. */
internal typealias PlannerCtx = WorkContextEntityExtractor.ParsedPrompt

/** 정규화된 문자열이 힌트 셋 중 하나라도 포함하면 true를 반환한다. 플래너 전체에서 공유한다. */
internal fun String.matchesAnyHint(hints: Set<String>): Boolean =
    hints.any { this.contains(it) }

/**
 * SystemPromptBuilder와 WorkContextForcedToolPlanner가 공유하는 키워드 힌트 집합.
 *
 * 도구 라우팅 키워드를 한 곳에서 관리하여 split-brain 라우팅을 방지한다.
 * 정규식 패턴과 힌트 셋을 모두 포함하며, 핫 경로 Regex 컴파일 방지를 위해
 * object 레벨에서 한 번만 초기화한다.
 */
internal object WorkContextPatterns {

    // ── 정규식 패턴 ──

    /**
     * Jira 스타일 이슈 키를 매칭한다 (예: PAY-123, DEV-1).
     * 이슈 키가 감지되면 이슈 관련 도구(Jira 조회 등)를 우선 선택한다.
     */
    val ISSUE_KEY_REGEX = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b", RegexOption.IGNORE_CASE)

    /**
     * OpenAPI/Swagger 스펙 URL을 매칭한다.
     * 스펙 URL이 감지되면 API 관련 도구를 우선 선택한다.
     */
    val OPENAPI_URL_REGEX = Regex(
        "https?://\\S+(?:openapi|swagger)\\S*",
        RegexOption.IGNORE_CASE
    )

    // ── 공유 키워드 힌트 셋 (17쌍) ──

    val BLOCKER_HINTS = setOf("blocker", "차단", "막힌")

    val REVIEW_QUEUE_HINTS = setOf(
        "queue", "대기열", "review queue", "리뷰 대기열", "검토 대기열",
        "리뷰가 필요한", "검토가 필요한", "needs review", "review needed",
        "리뷰 대기", "검토 대기"
    )

    val REVIEW_SLA_HINTS = setOf(
        "sla", "응답 지연", "review sla", "리뷰 sla", "sla 경고", "리뷰 sla 경고"
    )

    val REVIEW_RISK_HINTS = setOf("review risk", "리뷰 리스크", "코드 리뷰 리스크")

    val HYBRID_PRIORITY_HINTS = setOf(
        "priority", "priorities", "우선순위", "오늘 우선", "today priority"
    )

    val WORK_OWNER_HINTS = setOf(
        "owner", "담당자", "담당 팀", "누구 팀", "책임자", "누가 담당",
        "담당 서비스"
    )

    val MISSING_ASSIGNEE_HINTS = setOf(
        "담당자가 없는", "담당자 없는", "미할당", "unassigned",
        "assignee is empty", "assignee 없는"
    )

    val WORK_ITEM_CONTEXT_HINTS = setOf(
        "전체 맥락", "맥락", "context", "관련 문서", "관련 pr", "열린 pr",
        "오픈 pr", "다음 액션", "next action",
        "상세", "설명", "본문", "description", "보여줘", "알려줘",
        "댓글", "코멘트", "comment", "하위 이슈", "서브태스크", "subtask",
        "블로킹", "블록", "blocking", "blocked", "연결된 이슈", "issuelink"
    )

    val WORK_SERVICE_CONTEXT_HINTS = setOf(
        "서비스 상황", "서비스 현황", "service context", "service summary",
        "현재 상황", "현재 현황", "최근 jira", "최근 jira 이슈", "열린 pr",
        "오픈 pr", "관련 문서", "한 번에 요약", "요약해줘", "기준으로"
    )

    val WORK_RELEASE_READINESS_HINTS = setOf(
        "release readiness", "readiness pack", "릴리즈 준비", "출시 준비",
        "readiness"
    )

    val WORK_PERSONAL_FOCUS_HINTS = setOf(
        "focus plan", "personal focus plan", "개인 focus plan",
        "개인 집중 계획", "오늘 집중 계획"
    )

    val WORK_PERSONAL_LEARNING_HINTS = setOf(
        "learning digest", "personal learning digest", "학습 digest",
        "학습 다이제스트"
    )

    val WORK_PERSONAL_INTERRUPT_HINTS = setOf(
        "interrupt guard", "personal interrupt guard", "interrupt plan",
        "인터럽트 가드", "집중 방해"
    )

    val WORK_PERSONAL_WRAPUP_HINTS = setOf(
        "end of day wrapup", "end-of-day wrapup", "eod wrapup", "wrapup",
        "wrap-up", "마감 정리", "하루 마감"
    )

    val WRONG_ENDPOINT_HINTS = setOf(
        "wrong endpoint", "invalid endpoint", "잘못된 endpoint",
        "없는 endpoint"
    )

    val VALIDATE_HINTS = setOf("validate", "검증", "유효성")

    val SUMMARY_HINTS = setOf("summary", "summarize", "요약", "정리")

    val JIRA_BRIEFING_HINTS = setOf(
        "daily briefing", "아침 브리핑", "업무 브리핑", "데일리 브리핑",
        "daily digest", "오늘의 jira 브리핑", "오늘 jira 브리핑",
        "jira 브리핑", "jira briefing", "오늘의 jira briefing"
    )

    val EXPLICIT_BRIEFING_FALLBACK_HINTS = setOf(
        "브리핑", "briefing", "아침 요약", "오늘 현황",
        "현황 요약", "현황 정리", "상황 정리", "상황 요약",
        "팀 진행 상황", "팀 현황", "우리 팀 상황", "팀 요약"
    )

    val HYBRID_RELEASE_RISK_HINTS = setOf(
        "위험 신호", "risk signal", "release risk", "릴리즈 리스크",
        "risk digest"
    )

    val PRE_DEPLOY_READINESS_HINTS = setOf(
        "배포 전에", "출시 전에", "release 전에", "pre-release"
    )
}

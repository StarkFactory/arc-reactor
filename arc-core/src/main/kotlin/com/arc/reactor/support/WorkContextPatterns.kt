package com.arc.reactor.support

/**
 * 사용자 프롬프트에서 작업 컨텍스트 신호를 감지하기 위한 공유 정규식 패턴.
 *
 * 도구 선택, 시스템 프롬프트 구성, 강제 도구 계획 등에서
 * 사용자 입력의 작업 맥락을 파악하는 데 사용된다.
 *
 * WHY: 동일한 정규식 패턴이 여러 곳에서 필요하므로 companion object로 중앙화하여
 * 패턴 불일치와 중복 정의를 방지한다.
 * CLAUDE.md 규칙: "핫 경로에서 Regex를 컴파일하지 말 것" — companion object 또는
 * 최상위 val로 추출하여 한 번만 컴파일한다.
 */
object WorkContextPatterns {
    /**
     * Jira 스타일 이슈 키를 매칭한다 (예: PAY-123, DEV-1).
     * 이슈 키가 감지되면 이슈 관련 도구(Jira 조회 등)를 우선 선택한다.
     */
    val ISSUE_KEY_REGEX = Regex("\\b[A-Z][A-Z0-9_]+-[1-9][0-9]*\\b")

    /**
     * OpenAPI/Swagger 스펙 URL을 매칭한다.
     * 스펙 URL이 감지되면 API 관련 도구를 우선 선택한다.
     */
    val OPENAPI_URL_REGEX = Regex(
        "https?://\\S+(?:openapi|swagger)\\S*",
        RegexOption.IGNORE_CASE
    )
}

package com.arc.reactor.tool

/**
 * Tool 카테고리
 *
 * 동적 Tool 로딩에서 요청별 필요한 도구만 선택하기 위한 분류.
 * 프로젝트별로 확장 가능한 인터페이스 제공.
 *
 * @see ToolSelector 카테고리 기반 Tool 선택기
 */
interface ToolCategory {
    /** 카테고리 이름 */
    val name: String

    /** 매칭 키워드들 (소문자) */
    val keywords: Set<String>

    /**
     * 프롬프트에 이 카테고리와 매칭되는 키워드가 있는지 확인
     */
    fun matches(prompt: String): Boolean {
        val lowerPrompt = prompt.lowercase()
        return keywords.any { it in lowerPrompt }
    }
}

/**
 * 기본 제공 카테고리
 */
enum class DefaultToolCategory(
    override val keywords: Set<String>
) : ToolCategory {
    /** 검색 도구 */
    SEARCH(setOf("검색", "search", "찾아", "find", "조회", "query")),

    /** 생성 도구 */
    CREATE(setOf("생성", "create", "만들어", "작성", "write")),

    /** 분석 도구 */
    ANALYZE(setOf("분석", "analyze", "요약", "summary", "리포트", "report")),

    /** 통신 도구 */
    COMMUNICATE(setOf("전송", "send", "메일", "email", "알림", "notify")),

    /** 데이터 도구 */
    DATA(setOf("데이터", "data", "저장", "save", "업데이트", "update"));

    companion object {
        /**
         * 프롬프트에서 매칭되는 카테고리들 추출
         */
        fun matchCategories(prompt: String): Set<DefaultToolCategory> {
            return entries.filter { it.matches(prompt) }.toSet()
        }
    }
}

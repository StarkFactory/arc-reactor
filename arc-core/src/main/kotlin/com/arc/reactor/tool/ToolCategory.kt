package com.arc.reactor.tool

/**
 * 도구 카테고리 인터페이스
 *
 * 동적 도구 로딩을 위한 분류 체계.
 * 사용자 요청에 따라 관련 도구만 선택하여 컨텍스트 윈도우 사용을 줄이고
 * 도구 선택 정확도를 높인다.
 *
 * ## 왜 카테고리가 필요한가?
 * - LLM은 적고 관련성 높은 도구로 더 잘 작동한다
 * - 토큰 사용량 감소 (도구 설명이 컨텍스트를 소비)
 * - 도메인별 도구 그룹화 지원
 *
 * ## 커스텀 카테고리
 * ```kotlin
 * enum class MyProjectCategory(
 *     override val keywords: Set<String>
 * ) : ToolCategory {
 *     HR(setOf("employee", "hiring", "recruitment")),
 *     FINANCE(setOf("budget", "expense", "invoice"))
 * }
 * ```
 *
 * @see ToolSelector 카테고리 기반 도구 필터링
 * @see DefaultToolCategory 기본 제공 카테고리
 */
interface ToolCategory {
    /** 카테고리 식별자 */
    val name: String

    /** 이 카테고리를 활성화하는 키워드 목록 (소문자) */
    val keywords: Set<String>

    /**
     * 프롬프트에 이 카테고리와 매칭되는 키워드가 포함되어 있는지 확인한다.
     *
     * @param prompt 사용자 요청 텍스트
     * @return 키워드가 하나라도 매칭되면 true
     */
    fun matches(prompt: String): Boolean {
        val lowerPrompt = prompt.lowercase()
        return keywords.any { it in lowerPrompt }
    }
}

/**
 * 기본 도구 카테고리
 *
 * 일반적인 도구 유형에 대한 기본 제공 카테고리.
 * 이것을 사용하거나 도메인에 맞는 커스텀 카테고리를 정의할 수 있다.
 */
enum class DefaultToolCategory(
    override val keywords: Set<String>
) : ToolCategory {
    /** 검색 및 조회 도구 */
    SEARCH(setOf("검색", "search", "찾아", "find", "조회", "query")),

    /** 콘텐츠 생성 도구 */
    CREATE(setOf("생성", "create", "만들어", "작성", "write")),

    /** 분석 및 리포팅 도구 */
    ANALYZE(setOf("분석", "analyze", "요약", "summary", "리포트", "report")),

    /** 커뮤니케이션 및 알림 도구 */
    COMMUNICATE(setOf("전송", "send", "메일", "email", "알림", "notify")),

    /** 데이터 관리 도구 */
    DATA(setOf("데이터", "data", "저장", "save", "업데이트", "update"));

    companion object {
        /**
         * 프롬프트에서 키워드가 매칭되는 모든 카테고리를 추출한다.
         *
         * @param prompt 사용자 요청 텍스트
         * @return 매칭된 카테고리 집합
         */
        fun matchCategories(prompt: String): Set<DefaultToolCategory> {
            return entries.filter { it.matches(prompt) }.toSet()
        }
    }
}

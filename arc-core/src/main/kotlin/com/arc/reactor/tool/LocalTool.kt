package com.arc.reactor.tool

/**
 * 로컬 도구 마커 인터페이스
 *
 * 이 인터페이스를 구현하고 @Component로 어노테이트한 클래스는
 * 에이전트에 자동 등록된다.
 *
 * ## 기능
 * - Spring 컴포넌트 스캐닝을 통한 자동 탐색
 * - 카테고리 기반 필터링으로 컨텍스트 윈도우 최적화
 * - Spring AI의 @Tool 어노테이션과 통합
 *
 * ## 사용 예시
 * ```kotlin
 * @Component
 * class CompanySearchTool : LocalTool {
 *     override val category = ToolCategory.SEARCH
 *
 *     @Tool(description = "Search company information by name")
 *     fun searchCompany(@ToolParam("Company name") name: String): CompanyInfo {
 *         return companyService.search(name)
 *     }
 * }
 * ```
 *
 * ## 카테고리 활용
 * ```kotlin
 * // 항상 로딩 (필수 도구)
 * class CoreTool : LocalTool {
 *     override val category = null  // 또는 생략
 * }
 *
 * // 사용자 요청에 따라 조건부 로딩
 * class SearchTool : LocalTool {
 *     override val category = ToolCategory.SEARCH
 * }
 * ```
 *
 * @see ToolCategory 사용 가능한 도구 카테고리
 * @see ToolSelector 카테고리 기반 도구 필터링
 */
interface LocalTool {
    /**
     * 이 도구가 속한 카테고리.
     *
     * [ToolSelector]가 사용자 요청에 따라 도구를 필터링할 때 사용한다.
     * - `null`: 항상 로딩 (필수/핵심 도구)
     * - non-null: 카테고리가 사용자 의도와 매칭될 때만 로딩
     *
     * @return 도구 카테고리, 항상 필요한 도구이면 null
     */
    val category: ToolCategory?
        get() = null
}

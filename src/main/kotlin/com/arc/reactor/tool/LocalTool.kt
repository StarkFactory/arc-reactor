package com.arc.reactor.tool

/**
 * 로컬 Tool 마커 인터페이스
 *
 * 이 인터페이스를 구현하는 @Component는 자동으로 Agent에 등록된다.
 *
 * ## 사용법
 *
 * ```kotlin
 * @Component
 * class MyTool : LocalTool {
 *     override val category = ToolCategory.SEARCH
 *
 *     @Tool(description = "회사 정보를 검색합니다")
 *     fun searchCompany(@ToolParam("회사명") name: String): CompanyInfo {
 *         // 구현
 *     }
 * }
 * ```
 *
 * @see ToolCategory Tool 카테고리
 * @see ToolResult Tool 실행 결과
 */
interface LocalTool {
    /**
     * Tool이 속한 카테고리
     *
     * null이면 항상 로딩됨 (필수 도구)
     */
    val category: ToolCategory?
        get() = null
}

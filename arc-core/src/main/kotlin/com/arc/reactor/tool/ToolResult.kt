package com.arc.reactor.tool

/**
 * 도구 실행 결과 인터페이스
 *
 * 도구 실행 결과의 표준 인터페이스. 모든 도구에서 일관된 성공/실패 처리를 제공한다.
 *
 * ## 사용 예시
 * ```kotlin
 * @Tool(description = "Search companies")
 * fun searchCompany(name: String): ToolResult {
 *     return try {
 *         val results = companyService.search(name)
 *         SimpleToolResult.success("Found ${results.size} companies", results)
 *     } catch (e: Exception) {
 *         SimpleToolResult.failure("Search failed: ${e.javaClass.simpleName}")
 *     }
 * }
 * ```
 *
 * @see SimpleToolResult 기본 구현체
 * @see CountableToolResult 항목 개수 포함 결과
 */
interface ToolResult {
    /** 작업 성공 여부 */
    val success: Boolean

    /** 성공 시 사용자에게 보여줄 메시지 */
    val message: String?

    /** 실패 시 에러 설명 */
    val errorMessage: String?

    /** 작업이 성공했는지 확인한다 (success 플래그 + 에러 없음) */
    fun isSuccess(): Boolean = success && errorMessage == null

    /** 작업이 실패했는지 확인한다 */
    fun isFailure(): Boolean = !isSuccess()

    /** 성공/실패 상태에 따른 적절한 메시지를 반환한다 */
    fun displayMessage(): String = if (isSuccess()) message.orEmpty() else errorMessage.orEmpty()
}

/**
 * 간단한 도구 실행 결과 구현체
 *
 * ToolResult를 구현하는 기본 데이터 클래스. 팩토리 메서드를 사용하면 더 깔끔하게 생성할 수 있다.
 *
 * ## 사용 예시
 * ```kotlin
 * // 데이터 포함 성공
 * SimpleToolResult.success("Found 5 companies", companies)
 *
 * // 실패
 * SimpleToolResult.failure("Company not found")
 *
 * // 직접 생성
 * SimpleToolResult(
 *     success = true,
 *     message = "Operation completed",
 *     data = resultData
 * )
 * ```
 *
 * @property success 작업 성공 여부
 * @property message 성공 메시지
 * @property errorMessage 에러 설명
 * @property data 선택적 결과 페이로드
 */
data class SimpleToolResult(
    override val success: Boolean,
    override val message: String? = null,
    override val errorMessage: String? = null,
    val data: Any? = null
) : ToolResult {
    companion object {
        /**
         * 성공 결과를 생성한다.
         *
         * @param message 성공 메시지
         * @param data 선택적 결과 데이터
         */
        fun success(message: String, data: Any? = null) = SimpleToolResult(
            success = true,
            message = message,
            data = data
        )

        /**
         * 실패 결과를 생성한다.
         *
         * @param errorMessage 에러 설명
         */
        fun failure(errorMessage: String) = SimpleToolResult(
            success = false,
            errorMessage = errorMessage
        )
    }
}

package com.arc.reactor.tool

/**
 * Tool 실행 결과 인터페이스
 *
 * 모든 Tool의 반환 타입이 구현해야 하는 표준 인터페이스.
 */
interface ToolResult {
    /** 성공 여부 */
    val success: Boolean

    /** 사용자에게 표시할 메시지 */
    val message: String?

    /** 에러 메시지 (실패 시) */
    val errorMessage: String?

    fun isSuccess(): Boolean = success && errorMessage == null
    fun isFailure(): Boolean = !isSuccess()
    fun displayMessage(): String = if (isSuccess()) message.orEmpty() else errorMessage.orEmpty()
}

/**
 * 아이템 카운트를 포함하는 결과
 */
interface CountableToolResult : ToolResult {
    val totalCount: Int
    fun hasItems(): Boolean = totalCount > 0
    fun isEmpty(): Boolean = totalCount == 0
}

/**
 * 기본 Tool 결과 구현
 */
data class SimpleToolResult(
    override val success: Boolean,
    override val message: String? = null,
    override val errorMessage: String? = null,
    val data: Any? = null
) : ToolResult {
    companion object {
        fun success(message: String, data: Any? = null) = SimpleToolResult(
            success = true,
            message = message,
            data = data
        )

        fun failure(errorMessage: String) = SimpleToolResult(
            success = false,
            errorMessage = errorMessage
        )
    }
}

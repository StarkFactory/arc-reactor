package com.arc.reactor.agent.model

/**
 * 텍스트 Flow 내에서 발행되는 스트리밍 이벤트 마커.
 *
 * 실행기가 텍스트 토큰과 함께 특수 마커 문자열을 발행한다.
 * 컨트롤러 계층이 이 마커를 파싱하여 프론트엔드용
 * 타입화된 SSE 이벤트(tool_start, tool_end, error)로 변환한다.
 *
 * LLM 출력과의 충돌을 방지하기 위해 null 바이트 접두사(\u0000)를 사용한다.
 *
 * @see com.arc.reactor.agent.impl.StreamingReActLoopExecutor 루프에서 마커 발행
 * @see com.arc.reactor.agent.impl.StreamingCompletionFinalizer 완료 시 에러 마커 발행
 */
object StreamEventMarker {

    private const val PREFIX = "\u0000__arc__"
    private const val TOOL_START = "${PREFIX}tool_start:"
    private const val TOOL_END = "${PREFIX}tool_end:"
    private const val ERROR = "${PREFIX}error:"

    /** 도구 실행 시작 마커를 생성한다. */
    fun toolStart(toolName: String): String = "$TOOL_START$toolName"

    /** 도구 실행 완료 마커를 생성한다. */
    fun toolEnd(toolName: String): String = "$TOOL_END$toolName"

    /** 에러 마커를 생성한다. */
    fun error(message: String): String = "$ERROR$message"

    /** 주어진 텍스트가 마커인지 확인한다. */
    fun isMarker(text: String): Boolean = text.startsWith(PREFIX)

    /**
     * 마커 문자열을 (이벤트 타입, 페이로드) 쌍으로 파싱한다.
     * 마커가 아니면 null을 반환한다.
     */
    fun parse(text: String): Pair<String, String>? = when {
        text.startsWith(TOOL_START) -> "tool_start" to text.removePrefix(TOOL_START)
        text.startsWith(TOOL_END) -> "tool_end" to text.removePrefix(TOOL_END)
        text.startsWith(ERROR) -> "error" to text.removePrefix(ERROR)
        else -> null
    }
}

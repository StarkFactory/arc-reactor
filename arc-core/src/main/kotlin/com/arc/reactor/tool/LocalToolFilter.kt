package com.arc.reactor.tool

/**
 * LocalTool 빈이 LLM에 제공되기 전에 필터링하는 확장 포인트.
 *
 * 구현체는 모듈별 가시성 정책을 적용할 수 있다
 * (예: 스코프 기반 노출 제어).
 */
fun interface LocalToolFilter {
    /** 도구 목록을 필터링하여 LLM에 노출할 도구만 반환한다. */
    fun filter(tools: List<LocalTool>): List<LocalTool>
}

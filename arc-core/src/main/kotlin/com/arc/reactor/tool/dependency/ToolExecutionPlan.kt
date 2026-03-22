package com.arc.reactor.tool.dependency

/**
 * DAG 기반 도구 실행 계획.
 *
 * 위상 정렬 결과를 계층(layer) 단위로 표현하며,
 * 같은 계층의 도구는 병렬 실행이 가능하고 이전 계층의 도구가 모두 완료된 후 실행된다.
 *
 * @property layers 실행 순서대로 정렬된 계층 목록
 * @property totalTools 실행 계획에 포함된 전체 도구 수
 * @see ToolExecutionLayer 개별 실행 계층
 * @see ToolDependencyGraph.getExecutionPlan 실행 계획 생성
 */
data class ToolExecutionPlan(
    val layers: List<ToolExecutionLayer>,
    val totalTools: Int
)

/**
 * 단일 실행 계층 — 같은 계층의 도구는 병렬 실행 가능.
 *
 * @property index 0부터 시작하는 계층 번호
 * @property toolNames 이 계층에서 실행할 도구 이름 집합
 * @property isParallel 병렬 실행 가능 여부 (도구가 2개 이상이면 true)
 */
data class ToolExecutionLayer(
    val index: Int,
    val toolNames: Set<String>,
    val isParallel: Boolean = toolNames.size > 1
)

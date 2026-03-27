package com.arc.reactor.tool.dependency

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 도구 의존성 DAG(방향 비순환 그래프) 인터페이스.
 *
 * 도구 간 의존 관계를 선언하고, DAG 기반으로
 * 병렬/순차 실행 순서를 자동 결정하는 실행 계획을 생성한다.
 *
 * @see DefaultToolDependencyGraph 기본 구현
 * @see ToolExecutionPlan 실행 계획 결과
 */
interface ToolDependencyGraph {

    /**
     * 도구 의존성을 등록한다.
     *
     * @param dependency 등록할 의존성 정보
     */
    fun addDependency(dependency: ToolDependency)

    /**
     * 단일 의존성을 간편하게 등록한다.
     *
     * @param tool 의존하는 도구 이름
     * @param dependsOn 선행 도구 이름
     */
    fun addDependency(tool: String, dependsOn: String)

    /**
     * 지정된 도구의 선행 의존 도구 집합을 반환한다.
     *
     * @param toolName 조회할 도구 이름
     * @return 선행 도구 이름 집합 (의존성 없으면 빈 집합)
     */
    fun getDependencies(toolName: String): Set<String>

    /**
     * 요청된 도구 집합에 대해 위상 정렬 기반 실행 계획을 생성한다.
     *
     * @param requestedTools 실행할 도구 이름 집합
     * @return 계층별로 정렬된 실행 계획
     * @throws IllegalStateException 순환 의존성이 존재할 경우
     */
    fun getExecutionPlan(requestedTools: Set<String>): ToolExecutionPlan

    /**
     * 그래프의 무결성을 검증한다.
     *
     * 순환 의존성, 자기 참조 등의 문제를 검출하여 메시지 목록으로 반환한다.
     *
     * @return 발견된 문제 메시지 목록 (문제 없으면 빈 리스트)
     */
    fun validate(): List<String>
}

/**
 * [ToolDependencyGraph]의 기본 인메모리 구현.
 *
 * 인접 리스트(adjacency list)로 의존 관계를 저장하고,
 * Kahn 알고리즘 기반 위상 정렬로 실행 계층을 생성한다.
 * 순환 의존성은 DFS로 검출한다.
 */
class DefaultToolDependencyGraph : ToolDependencyGraph {

    /** 도구 → 선행 도구 집합 (인접 리스트) */
    private val dependencies =
        mutableMapOf<String, MutableSet<String>>()

    override fun addDependency(dependency: ToolDependency) {
        val deps = dependencies.getOrPut(dependency.toolName) {
            mutableSetOf()
        }
        deps.addAll(dependency.dependsOn)
        // 선행 도구도 그래프에 노드로 등록
        for (dep in dependency.dependsOn) {
            dependencies.putIfAbsent(dep, mutableSetOf())
        }
        logger.debug {
            "의존성 추가: ${dependency.toolName} → ${dependency.dependsOn}"
        }
    }

    override fun addDependency(tool: String, dependsOn: String) {
        addDependency(
            ToolDependency(
                toolName = tool,
                dependsOn = setOf(dependsOn)
            )
        )
    }

    override fun getDependencies(toolName: String): Set<String> {
        return dependencies[toolName]?.toSet() ?: emptySet()
    }

    override fun getExecutionPlan(
        requestedTools: Set<String>
    ): ToolExecutionPlan {
        if (requestedTools.isEmpty()) {
            return ToolExecutionPlan(layers = emptyList(), totalTools = 0)
        }
        val subDeps = buildSubGraph(requestedTools)
        val cycle = detectCycles(subDeps)
        if (cycle != null) {
            throw IllegalStateException(
                "순환 의존성: ${cycle.joinToString(" → ")}"
            )
        }
        val layers = buildTopologicalOrder(subDeps)
        logger.debug {
            "실행 계획 생성: ${layers.size}개 계층, " +
                "도구=${requestedTools}"
        }
        return ToolExecutionPlan(
            layers = layers,
            totalTools = requestedTools.size
        )
    }

    override fun validate(): List<String> {
        val errors = mutableListOf<String>()
        errors += validateDependency()
        val cycle = detectCycles(dependencies)
        if (cycle != null) {
            errors += "순환 의존성 발견: ${cycle.joinToString(" → ")}"
        }
        return errors
    }

    /**
     * 개별 의존성의 유효성을 검증한다.
     *
     * 자기 참조 의존성을 검출하여 오류 메시지 목록으로 반환한다.
     */
    private fun validateDependency(): List<String> {
        return dependencies
            .filter { (tool, deps) -> tool in deps }
            .map { (tool, _) -> "자기 참조 의존성: $tool → $tool" }
    }

    /**
     * 요청된 도구만으로 부분 그래프를 구성한다.
     *
     * 요청 범위 밖의 의존성은 무시하여
     * 실행 계획에 불필요한 도구가 포함되지 않도록 한다.
     */
    private fun buildSubGraph(
        tools: Set<String>
    ): Map<String, Set<String>> {
        return tools.associateWith { tool ->
            (dependencies[tool] ?: emptySet())
                .filter { it in tools }.toSet()
        }
    }

    /**
     * Kahn 알고리즘 기반 위상 정렬로 실행 계층을 생성한다.
     *
     * 선행 의존이 모두 충족된 도구를 같은 계층에 배치하고,
     * 해당 도구를 완료 처리한 뒤 다음 계층을 반복 생성한다.
     */
    private fun buildTopologicalOrder(
        subDeps: Map<String, Set<String>>
    ): List<ToolExecutionLayer> {
        val remaining = subDeps.keys.toMutableSet()
        val satisfied = mutableSetOf<String>()
        val layers = mutableListOf<ToolExecutionLayer>()
        var layerIndex = 0

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { tool ->
                val deps = subDeps[tool] ?: emptySet()
                deps.all { it in satisfied }
            }.toSet()
            layers += ToolExecutionLayer(
                index = layerIndex++,
                toolNames = ready
            )
            satisfied.addAll(ready)
            remaining.removeAll(ready)
        }
        return layers
    }

    /**
     * DFS 기반 순환 의존성 검출.
     *
     * @return 순환 경로 (없으면 null)
     */
    private fun detectCycles(
        graph: Map<String, Set<String>>
    ): List<String>? {
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun dfs(node: String): List<String>? {
            if (node in stack) {
                val cycleStart = path.indexOf(node)
                return path.subList(cycleStart, path.size) + node
            }
            if (node in visited) return null
            visited += node
            stack += node
            path += node
            for (dep in graph[node] ?: emptySet()) {
                val cycle = dfs(dep)
                if (cycle != null) return cycle
            }
            stack -= node
            path.removeAt(path.lastIndex)
            return null
        }

        for (node in graph.keys) {
            val cycle = dfs(node)
            if (cycle != null) return cycle
        }
        return null
    }
}

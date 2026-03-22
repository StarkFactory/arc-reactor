package com.arc.reactor.tool.dependency

/**
 * 도구 간 의존성 선언.
 *
 * 특정 도구가 다른 도구의 출력을 필요로 하는 관계를 표현한다.
 * [ToolDependencyGraph]에 등록하면 DAG 기반 실행 계획을 자동으로 생성할 수 있다.
 *
 * @property toolName 이 도구의 이름
 * @property dependsOn 이 도구가 실행 전에 완료되어야 하는 도구 이름 집합
 * @property description 의존성이 존재하는 이유 (문서화 용도)
 * @see ToolDependencyGraph 의존성 그래프 관리
 */
data class ToolDependency(
    val toolName: String,
    val dependsOn: Set<String>,
    val description: String = ""
)

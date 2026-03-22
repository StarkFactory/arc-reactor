package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.dependency.DefaultToolDependencyGraph
import com.arc.reactor.tool.dependency.ToolDependencyGraph
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 도구 의존성 DAG 자동 설정.
 *
 * `arc.reactor.tool-dependency.enabled=true`일 때 활성화되며,
 * 도구 간 의존 관계를 관리하는 [ToolDependencyGraph] 빈을 등록한다.
 *
 * @see ToolDependencyGraph 도구 의존성 그래프 인터페이스
 * @see DefaultToolDependencyGraph 기본 인메모리 구현
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.tool-dependency", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class ToolDependencyConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun toolDependencyGraph(): ToolDependencyGraph {
        return DefaultToolDependencyGraph()
    }
}

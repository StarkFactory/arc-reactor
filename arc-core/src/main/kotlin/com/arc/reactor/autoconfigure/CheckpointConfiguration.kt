package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.checkpoint.CheckpointStore
import com.arc.reactor.agent.checkpoint.InMemoryCheckpointStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 실행 체크포인트 자동 설정.
 *
 * `arc.reactor.checkpoint.enabled=true`일 때 활성화되며,
 * ReAct 루프의 중간 상태를 저장하는 [CheckpointStore] 빈을 등록한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.checkpoint", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class CheckpointConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun checkpointStore(properties: AgentProperties): CheckpointStore {
        return InMemoryCheckpointStore(
            maxCheckpointsPerRun = properties.checkpoint.maxCheckpointsPerRun
        )
    }
}

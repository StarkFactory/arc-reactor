package com.arc.reactor.autoconfigure

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.micrometer.context.ContextRegistry
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.util.context.ReactorContextAccessor

/**
 * [ReactorContextPropagationConfiguration]의 동작을 검증한다.
 *
 * fat JAR 환경에서 ServiceLoader가 [ReactorContextAccessor]를 로드하지 못할 때
 * 명시적 등록이 올바르게 동작하는지 확인한다.
 */
class ReactorContextPropagationConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(ReactorContextPropagationConfiguration::class.java)
        )

    @Test
    fun `설정 로드 후 ContextRegistry에 ReactorContextAccessor가 등록되어 있다`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()

            val hasReactorAccessor = ContextRegistry.getInstance()
                .getContextAccessors()
                .any { it is ReactorContextAccessor }

            hasReactorAccessor.shouldBeTrue()
        }
    }

    @Test
    fun `ReactorContextPropagationConfiguration 빈이 컨텍스트에 등록된다`() {
        contextRunner.run { context ->
            context.startupFailure.shouldBeNull()

            context.getBeansOfType(ReactorContextPropagationConfiguration::class.java)
                .isNotEmpty().shouldBeTrue()
        }
    }

    @Test
    fun `중복 등록 시에도 예외 없이 정상 시작한다`() {
        // ReactorContextAccessor가 이미 ServiceLoader로 등록된 환경을 재현
        // (테스트 환경에서는 ServiceLoader가 정상 동작하므로 init에서 중복 등록 시도)
        contextRunner.run { firstContext ->
            firstContext.startupFailure.shouldBeNull()

            // 동일 설정을 다시 로드해도 IllegalArgumentException을 catch하여 정상 시작
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(ReactorContextPropagationConfiguration::class.java)
                )
                .run { secondContext ->
                    secondContext.startupFailure.shouldBeNull()
                }
        }
    }
}

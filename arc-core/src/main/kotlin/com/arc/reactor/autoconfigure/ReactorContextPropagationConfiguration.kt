package com.arc.reactor.autoconfigure

import io.micrometer.context.ContextRegistry
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Configuration
import reactor.util.context.ReactorContextAccessor

private val logger = KotlinLogging.logger {}

/**
 * Reactor 컨텍스트 전파를 위한 [ReactorContextAccessor] 명시적 등록.
 *
 * ## 배경
 * Spring Boot 3.x는 [reactor.core.publisher.Hooks.enableAutomaticContextPropagation]을 호출하여
 * Reactor 파이프라인 내에서 ThreadLocal 컨텍스트(MDC, Tracing 등)를 자동 전파한다.
 * 이때 [ContextRegistry]가 [ReactorContextAccessor]를 ServiceLoader로 검색하는데,
 * Spring Boot fat JAR(nested JAR) 환경에서 SPI 파일이 정상 로드되지 않는 경우가 있다.
 *
 * ## 증상
 * `IllegalStateException: No ContextAccessor for contextType: class reactor.util.context.Context2`
 * — 모든 `suspend fun` 컨트롤러 엔드포인트에서 500 오류 발생.
 *
 * ## 해결
 * fat JAR classloader 이슈와 무관하게 [ReactorContextAccessor]를 명시적으로 등록한다.
 * ServiceLoader가 이미 정상 등록한 환경(IDE/테스트)에서는 중복 등록을 건너뛴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ReactorContextAccessor::class, ContextRegistry::class)
class ReactorContextPropagationConfiguration {

    init {
        try {
            ContextRegistry.getInstance().registerContextAccessor(ReactorContextAccessor())
            logger.info { "ReactorContextAccessor explicitly registered with ContextRegistry" }
        } catch (_: IllegalArgumentException) {
            logger.debug { "ReactorContextAccessor already registered (ServiceLoader)" }
        }
    }
}
